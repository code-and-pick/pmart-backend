#!/usr/bin/env python3
"""ES-First ① 검색 평가 — 새 RAG 의 ES 색인(rag-operator·rag-customer, chunking-service ADR-0096)에 평가셋 질문을 BM25 로 바로 던져
기대 문서가 몇 위에 나오는지 잰다. 모델·임베딩·답 생성 없음(계획 §2-3 ① 충족 판정의 바탕).

  python es_first_eval.py --es http://localhost:19200 --out <폴더>

- 운영자: services/ai-assistant-service/src/integrationTest/resources/eval/answers.json(44) — sources·sections(옛 경로 → 새 저장소 경로로 바꿔 비교)
- 고객: deploy/lan/checks/eval/customer-answers.json(44) — doc(문서 이름, 여러 개면 |). 안내 문서만 / 안내 또는 같은 주제 FAQ(FAQ_TOPIC) 두 기준
- 질의: multi_match(content, sectionPath^2, title) + status=ACTIVE, 상위 10 청크 → 문서 단위 순위(처음 나온 순서)
- 근거 있는 질문: 기대 문서가 문서 순위 1위·3위 안·5위 안인지, 기대 조(운영자 sections)가 청크 1위·3위 안인지
- 근거 없는 질문(NONE): 1위 점수 — 근거 있는 질문의 1위 점수와 섞이는지(점수만으로 ① 에서 끝낼 수 있는지)
"""
import argparse
import json
import os
import statistics
import sys
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
OPERATOR_SET = os.path.join(ROOT, "services/ai-assistant-service/src/integrationTest/resources/eval/answers.json")
CUSTOMER_SET = os.path.join(ROOT, "deploy/lan/checks/eval/customer-answers.json")


def new_path(old):
    """옛 저장소 경로 → pmart-all-docs 경로."""
    if old.startswith("docs/rag/rag-docs/customer_app/"):
        return "customer/customer_app/" + old.split("/")[-1]
    if old.startswith("docs/rag/rag-docs/"):
        return "operator/" + old[len("docs/rag/rag-docs/"):]
    if old.startswith("docs/business/customer-faq/"):
        return "customer/faq/" + old.split("/")[-1]
    return old


# 고객 FAQ 문서 ↔ 같은 주제 고객 앱 안내 문서(파일 이름으로 맞춘 대응 — 2026-11-28). 고객 색인에는 둘 다 있고, 승인 FAQ 도 고객에게 맞는
# 근거다(계획 §8 D2). "안내 또는 같은 주제 FAQ" 채점에 쓴다.
FAQ_TOPIC = {
    "01-customer-center": ["05-customer-center"],
    "02-cancel": ["08-order-cancel"],
    "03-delivery-status": ["09-delivery-status"],
    "04-not-received": ["10-not-received-misdelivery"],
    "05-missing-wrong-item": ["12-missing-damaged-item", "10-not-received-misdelivery"],
    "06-damage-quality": ["12-missing-damaged-item"],
    "07-refund": ["14-refund"],
    "08-return-exchange": ["13-return-exchange"],
    "09-shortage": ["11-shortage"],
    "10-address-area": ["07-delivery-area-address"],
    "11-payment-fees": ["06-order-payment-fees"],
    "12-account": ["01-signup-login", "02-withdrawal"],
    "13-alcohol": ["06-order-payment-fees"],
}


def customer_match(doc_field, with_faq):
    """기대 문서(여러 개면 | 로) — 안내 문서 경로, with_faq 면 같은 주제 FAQ 도."""
    wanted = set(doc_field.split("|"))

    def ok(path):
        name = path.rsplit("/", 1)[-1][:-3]
        if path.startswith("customer/customer_app/") and name in wanted:
            return True
        return with_faq and path.startswith("customer/faq/") and bool(wanted & set(FAQ_TOPIC.get(name, [])))

    return ok


def search(es, alias, q, size=10):
    body = {
        "size": size,
        "_source": ["documentId", "sectionPath", "heading"],
        "query": {"bool": {
            "must": {"multi_match": {"query": q, "fields": ["content", "sectionPath^2", "title"]}},
            "filter": {"term": {"status": "ACTIVE"}}}},
    }
    req = urllib.request.Request(f"{es}/{alias}/_search", data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    hits = json.load(urllib.request.urlopen(req, timeout=30))["hits"]["hits"]
    return [{"doc": h["_source"]["documentId"], "section": h["_source"].get("sectionPath", ""),
             "heading": h["_source"].get("heading", ""), "score": round(h["_score"], 3)} for h in hits]


def doc_rank(hits, want):
    """문서 단위 순위(처음 나온 차례, 1부터). 없으면 None."""
    seen = []
    for h in hits:
        if h["doc"] not in seen:
            seen.append(h["doc"])
    for i, d in enumerate(seen):
        if want(d):
            return i + 1
    return None


def chunk_rank(hits, want):
    for i, h in enumerate(hits):
        if want(h):
            return i + 1
    return None


def evaluate(es):
    rows = []
    ops = json.load(open(OPERATOR_SET, encoding="utf-8"))["questions"]
    for q in ops:
        hits = search(es, "rag-operator", q["q"])
        srcs = {new_path(s) for s in q.get("sources", [])}
        secs = [(new_path(d), t) for d, t in q.get("sections", [])]
        grounded = q["expect"] in ("ANSWER", "FIND")
        rows.append({
            "set": "operator", "id": q["id"], "q": q["q"], "kind": q.get("intent") or q["expect"], "grounded": grounded,
            "docRank": doc_rank(hits, lambda d: d in srcs) if grounded and srcs else None,
            "sectionRank": chunk_rank(hits, lambda h: any(h["doc"] == d and t in h["section"] for d, t in secs)) if grounded and secs else None,
            "top1": hits[0] if hits else None, "top2Score": hits[1]["score"] if len(hits) > 1 else None,
            "top3": hits[:3]})
    cus = json.load(open(CUSTOMER_SET, encoding="utf-8"))
    cus = cus["questions"] if isinstance(cus, dict) else cus
    for q in cus:
        hits = search(es, "rag-customer", q["q"])
        doc = q.get("doc")
        grounded = q.get("state") == "ANSWERED" and bool(doc)
        rows.append({
            "set": "customer", "id": q["id"], "q": q["q"], "kind": q.get("faq") or q.get("state"), "grounded": grounded,
            "docRank": doc_rank(hits, customer_match(doc, False)) if grounded else None,
            "docOrFaqRank": doc_rank(hits, customer_match(doc, True)) if grounded else None,
            "sectionRank": None,
            "top1": hits[0] if hits else None, "top2Score": hits[1]["score"] if len(hits) > 1 else None,
            "top3": hits[:3]})
    return rows


def pct(n, d):
    return f"{n}/{d} ({100 * n / d:.0f}%)" if d else "-"


def summarize(rows):
    out = []
    for s in ("operator", "customer"):
        rs = [r for r in rows if r["set"] == s]
        g = [r for r in rs if r["grounded"]]
        n = [r for r in rs if not r["grounded"] and r["kind"] in ("NONE",)]
        top = lambda k: sum(1 for r in g if r["docRank"] is not None and r["docRank"] <= k)
        out.append(f"### {s} — 근거 있는 질문 {len(g)}")
        out.append(f"- 기대 문서 1위 {pct(top(1), len(g))} · 3위 안 {pct(top(3), len(g))} · 5위 안 {pct(top(5), len(g))} · 10청크 안에 없음 {sum(1 for r in g if r['docRank'] is None)}")
        if s == "customer":
            tf = lambda k: sum(1 for r in g if r.get("docOrFaqRank") is not None and r["docOrFaqRank"] <= k)
            out.append(f"- 안내 문서 또는 같은 주제 FAQ 1위 {pct(tf(1), len(g))} · 3위 안 {pct(tf(3), len(g))} · 5위 안 {pct(tf(5), len(g))} · 10청크 안에 없음 {sum(1 for r in g if r.get('docOrFaqRank') is None)}")
        sec = [r for r in g if r["sectionRank"] is not None or (s == "operator")]
        if s == "operator":
            sg = [r for r in g if r.get("sectionRank") is not None]
            with_sec = [r for r in rows if r["set"] == "operator" and r["grounded"]]
            out.append(f"- 기대 조(청크) 1위 {pct(sum(1 for r in sg if r['sectionRank'] == 1), len(with_sec))} · 3위 안 {pct(sum(1 for r in sg if r['sectionRank'] <= 3), len(with_sec))}")
        kinds = sorted({r["kind"] for r in g})
        for k in kinds:
            kg = [r for r in g if r["kind"] == k]
            key = "docOrFaqRank" if s == "customer" else "docRank"
            out.append(f"  - {k}: 1위 {pct(sum(1 for r in kg if r[key] == 1), len(kg))}, 3위 안 {pct(sum(1 for r in kg if r[key] and r[key] <= 3), len(kg))}")
        gs = [r["top1"]["score"] for r in g if r["top1"]]
        ns = [r["top1"]["score"] for r in n if r["top1"]]
        if gs and ns:
            out.append(f"- 1위 점수: 근거 있음 중앙 {statistics.median(gs):.2f} (최소 {min(gs):.2f}, 최대 {max(gs):.2f}) / 근거 없음(NONE {len(ns)}) 중앙 {statistics.median(ns):.2f} (최소 {min(ns):.2f}, 최대 {max(ns):.2f})")
            over = sum(1 for x in ns if x >= min(gs))
            out.append(f"- 근거 없는 질문 중 근거 있는 질문의 가장 낮은 1위 점수({min(gs):.2f}) 이상인 것 {over}/{len(ns)} — 점수 하나로는 가를 수 없는 정도")
        out.append("")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--es", default="http://localhost:9200")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    rows = evaluate(a.es)
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "records.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    s = summarize(rows)
    with open(os.path.join(a.out, "summary.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(s + "\n")
    sys.stdout.reconfigure(encoding="utf-8")
    print(s)


if __name__ == "__main__":
    main()
