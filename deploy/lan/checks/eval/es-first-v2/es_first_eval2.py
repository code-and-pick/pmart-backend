#!/usr/bin/env python3
"""ES-First 2차 검증(2026-11-28) — 검색 비교와 ES 종료 판정.

원칙(사용자·검토 합의):
- 기존 88문항(regression)은 고정 회귀셋. 새 문항은 질문군 단위 dev/final(questions.json). 규칙·가중치는 dev(+회귀 관찰)로만 고르고
  final 은 설정을 고정한 뒤 한 번만 돌린다(--final). final 결과를 보고 규칙을 고치면 그 결과는 '최종 검증'이라 부르지 않는다.
- 미확정(uncertain) 문항은 확정 정답 집계에서 뺀다(따로 센다).
- ES 종료 성공 = '실제로 돌려준 근거'(1위 청크)로 답할 수 있음. 정답이 3위에 있어도 1위만 돌려주고 끝냈으면 실패.
- 원래 BM25 점수(raw)와 가중치를 더한 최종 점수(final)를 따로 기록한다 — 가중치로 커진 점수 차이를 답변 가능성으로 읽지 않는다.

  python es_first_eval2.py --es http://localhost:19200 --pg localhost:25433 --ollama http://192.168.0.34:11434 --out <폴더> [--final]
"""
import argparse
import json
import math
import os
import statistics
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", ".."))
OPERATOR_SET = os.path.join(ROOT, "services/ai-assistant-service/src/integrationTest/resources/eval/answers.json")
CUSTOMER_SET = os.path.join(ROOT, "deploy/lan/checks/eval/customer-answers.json")
NEW_SET = os.path.join(HERE, "questions.json")
QUERY_INSTRUCTION = {
    "operator": "Instruct: Given a question about company policy or operating procedures, retrieve the document passages that answer it\nQuery: ",
    "customer": "Instruct: Given a customer's question to an online grocery store's customer center, retrieve the guide passages that answer it\nQuery: ",
}
ALIAS = {"operator": "rag-operator", "customer": "rag-customer"}
FAQ_TOPIC = {
    "01-customer-center": ["05-customer-center"], "02-cancel": ["08-order-cancel"], "03-delivery-status": ["09-delivery-status"],
    "04-not-received": ["10-not-received-misdelivery"], "05-missing-wrong-item": ["12-missing-damaged-item", "10-not-received-misdelivery"],
    "06-damage-quality": ["12-missing-damaged-item"], "07-refund": ["14-refund"], "08-return-exchange": ["13-return-exchange"],
    "09-shortage": ["11-shortage"], "10-address-area": ["07-delivery-area-address"], "11-payment-fees": ["06-order-payment-fees"],
    "12-account": ["01-signup-login", "02-withdrawal"], "13-alcohol": ["06-order-payment-fees"],
}
DOC_FLOW = ("GROUNDED", "NO_GROUNDING", "NEEDS_CLARIFICATION")  # 문서 검색 흐름에 들어오는 라벨(실시간·상품·범위 밖은 앞단 라우팅 몫)


def new_path(old):
    if old.startswith("docs/rag/rag-docs/customer_app/"):
        return "customer/customer_app/" + old.split("/")[-1]
    if old.startswith("docs/rag/rag-docs/"):
        return "operator/" + old[len("docs/rag/rag-docs/"):]
    if old.startswith("docs/business/customer-faq/"):
        return "customer/faq/" + old.split("/")[-1]
    return old


# ---------------- 문항 ----------------
def load_questions():
    qs = []
    for q in json.load(open(OPERATOR_SET, encoding="utf-8"))["questions"]:
        label = {"ANSWER": "GROUNDED", "FIND": "GROUNDED", "NONE": "NO_GROUNDING", "PRODUCT": "PRODUCT", "REALTIME": "REALTIME"}[q["expect"]]
        qs.append({"id": q["id"], "set": "regression", "role": "operator", "q": q["q"], "label": label, "kind": q.get("intent") or q["expect"],
                   "docs": sorted({new_path(s) for s in q.get("sources", [])}),
                   "sections": [[new_path(d), t] for d, t in q.get("sections", [])], "level": "chunk" if q.get("sections") else "doc",
                   "uncertain": False, "group": q["id"]})
    cus = json.load(open(CUSTOMER_SET, encoding="utf-8"))
    for q in (cus["questions"] if isinstance(cus, dict) else cus):
        state = q.get("state")
        label = {"ANSWERED": "GROUNDED", "NO_GROUNDING": "NO_GROUNDING", "OUT_OF_SCOPE": "OUT_OF_SCOPE",
                 "REALTIME_DATA_REQUIRED": "REALTIME"}[state]
        docs = []
        if label == "GROUNDED":
            names = q["doc"].split("|")
            docs = ["customer/customer_app/" + n + ".md" for n in names]
            docs += ["customer/faq/" + f + ".md" for f, apps in FAQ_TOPIC.items() if set(apps) & set(names)]  # 같은 주제 승인 FAQ 도 근거
        qs.append({"id": q["id"], "set": "regression", "role": "customer", "q": q["q"], "label": label, "kind": q.get("faq"),
                   "docs": docs, "sections": [], "level": "doc", "uncertain": False, "group": q["id"]})
    for q in json.load(open(NEW_SET, encoding="utf-8"))["questions"]:
        qs.append({"id": q["id"], "set": q["split"], "role": q["role"], "q": q["q"], "label": q["label"], "kind": q["type"],
                   "docs": sorted({e["doc"] for e in q["evidence"]}) if q["label"] == "GROUNDED" else [],
                   "sections": [[e["doc"], e["section"]] for e in q["evidence"]] if q["label"] == "GROUNDED" else [],
                   "level": "chunk", "uncertain": q["uncertain"], "group": q["group"]})
    return qs


def correct(q, hit):
    """돌려준 청크 하나로 답할 수 있는가(청크 수준 — 조항이 있으면 조항까지, 없으면 문서)."""
    if not hit or q["label"] != "GROUNDED":
        return False
    if q["sections"]:
        return any(hit["doc"] == d and s in hit["section"] for d, s in q["sections"])
    return hit["doc"] in q["docs"]


def doc_hit(q, hit):
    return bool(hit) and q["label"] == "GROUNDED" and hit["doc"] in q["docs"]


# ---------------- 검색 ----------------
def post(url, body, timeout=60):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers={"Content-Type": "application/json; charset=utf-8"})
    return json.load(urllib.request.urlopen(req, timeout=timeout))


def bm25(es, role, q, size=20):
    t = time.perf_counter()
    r = post(f"{es}/{ALIAS[role]}/_search", {
        "size": size, "_source": ["documentId", "sectionPath", "documentType", "heading", "chunkId"],
        "query": {"bool": {"must": {"multi_match": {"query": q, "fields": ["content", "sectionPath^2", "title"]}},
                           "filter": {"term": {"status": "ACTIVE"}}}}})
    ms = (time.perf_counter() - t) * 1000
    hits = [{"doc": h["_source"]["documentId"], "section": h["_source"].get("sectionPath", ""), "heading": h["_source"].get("heading", ""),
             "type": h["_source"].get("documentType"), "chunk": h["_source"]["chunkId"], "raw": round(h["_score"], 4)} for h in r["hits"]["hits"]]
    return hits, ms


def boosted(hits, role, factor):
    """운영자 검색에서 POLICY 문서에 곱 가중치(상위 20 창 안에서 다시 정렬). raw 는 그대로 두고 final 을 따로."""
    out = []
    for h in hits:
        f = factor if (role == "operator" and h["type"] == "POLICY") else 1.0
        out.append(dict(h, final=round(h["raw"] * f, 4)))
    return sorted(out, key=lambda h: -h["final"])


def analyze(es, text, cache={}):
    if text in cache:
        return cache[text]
    r = post(f"{es}/rag-operator/_analyze", {"analyzer": "rag_nori", "text": text})
    toks = {t["token"] for t in r["tokens"] if len(t["token"]) > 1 or t["token"].isdigit()}
    cache[text] = toks
    return toks


def features(es, q, hits):
    """CPU 지표: 점수(raw), 차이(절대·상대), 핵심어 비율(질문 낱말 중 1위 청크 제목 경로+본문에 있는 비율), 제목 일치(제목 경로 안 비율)."""
    if not hits:
        return {"top1": 0, "gap": 0, "relGap": 0, "coverage": 0, "titleMatch": 0}
    s1 = hits[0]["raw"]
    s2 = hits[1]["raw"] if len(hits) > 1 else 0.0
    qt = analyze(es, q)
    sec = analyze(es, hits[0]["section"])
    return {"top1": s1, "gap": round(s1 - s2, 4), "relGap": round((s1 - s2) / s1, 4) if s1 else 0,
            "titleMatch": round(len(qt & sec) / len(qt), 4) if qt else 0, "qTokens": sorted(qt)}


def coverage(es, q, chunk_text):
    qt = analyze(es, q)
    ct = analyze(es, chunk_text)
    return round(len(qt & ct) / len(qt), 4) if qt else 0


def chunk_text(es, role, chunk_id):
    r = post(f"{es}/{ALIAS[role]}/_search", {"size": 1, "_source": ["content", "sectionPath"], "query": {"term": {"chunkId": chunk_id}}})
    h = r["hits"]["hits"]
    return (h[0]["_source"]["sectionPath"] + "\n" + h[0]["_source"]["content"]) if h else ""


def embed(ollama, role, q):
    t = time.perf_counter()
    r = post(f"{ollama}/api/embed", {"model": "qwen3-embedding:0.6b", "input": [QUERY_INSTRUCTION[role] + q],
                                     "options": {"num_gpu": 0, "num_ctx": 2048}, "keep_alive": "24h"}, timeout=120)
    return r["embeddings"][0], (time.perf_counter() - t) * 1000


def vector(conn, role, vec, size=10):
    t = time.perf_counter()
    lit = "[" + ",".join(f"{x:.7f}" for x in vec) + "]"
    rows = conn.run(
        "SELECT document_id, section_path, chunk_id, 1 - (embedding <=> CAST(:v AS vector)) AS cos FROM chunk_vector "
        "WHERE access_scope = :s AND is_active ORDER BY embedding <=> CAST(:v AS vector) LIMIT :n",
        v=lit, s=role.upper(), n=size)
    ms = (time.perf_counter() - t) * 1000
    return [{"doc": d, "section": sp, "chunk": c, "cos": round(float(cos), 4)} for d, sp, c, cos in rows], ms


def rrf(a, b, k=60, size=10):
    score, info = {}, {}
    for lst in (a, b):
        for i, h in enumerate(lst[:10]):
            score[h["chunk"]] = score.get(h["chunk"], 0) + 1.0 / (k + i + 1)
            info.setdefault(h["chunk"], h)
    return [dict(info[c], rrf=round(s, 5)) for c, s in sorted(score.items(), key=lambda x: -x[1])[:size]]


# ---------------- 실행 ----------------
def run(args):
    sys.path.insert(0, args.pylib)
    import pg8000.native as pg
    host, port = args.pg.split(":")
    conn = pg.Connection("embedding_service", password="e2e-local", host=host, port=int(port), database="embedding_service")
    qs = [q for q in load_questions() if args.final or q["set"] != "final"]
    if args.final:
        qs = [q for q in qs if q["set"] == "final"]
    recs = []
    for q in qs:
        hits, es_ms = bm25(args.es, q["role"], q["q"])
        f = features(args.es, q["q"], hits)
        f["coverage"] = coverage(args.es, q["q"], chunk_text(args.es, q["role"], hits[0]["chunk"])) if hits else 0
        vec, emb_ms = embed(args.ollama, q["role"], q["q"])
        vhits, pg_ms = vector(conn, q["role"], vec)
        recs.append({**q, "bm25": hits[:10], "features": f, "vector": vhits,
                     "latency": {"es": round(es_ms, 1), "embed": round(emb_ms, 1), "pgvector": round(pg_ms, 1)}})
        print(q["set"], q["id"], q["label"], f.get("gap"), f.get("coverage"), file=sys.stderr)
    return recs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--es", default="http://localhost:19200")
    ap.add_argument("--pg", default="localhost:25433")
    ap.add_argument("--ollama", default="http://192.168.0.34:11434")
    ap.add_argument("--pylib", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--final", action="store_true")
    a = ap.parse_args()
    recs = run(a)
    os.makedirs(a.out, exist_ok=True)
    name = "records-final.json" if a.final else "records-dev.json"
    with open(os.path.join(a.out, name), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(recs, fh, ensure_ascii=False, indent=1)
    print("records", len(recs), "->", name)


if __name__ == "__main__":
    main()
