#!/usr/bin/env python3
"""ES 결과를 믿기 어려울 때 하이브리드로 넘어가는지 — 실제 사슬(ES·embedding-service·rag-query-service) 문항별 기록.

  python hybrid_live.py --pick <문항 목록 json> --out <md 경로>

문항마다: ES 만 썼을 때(BM25 상위 3, 가중치 정렬) 근거가 맞는지 / rag-query-service 의 판정 이유·벡터 호출·끝난 곳·최종 근거가 맞는지.
"""
import argparse
import json
import os
import sys
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from es_first_eval2 import bm25, boosted, correct, load_questions  # noqa: E402


def ask(url, scope, q):
    req = urllib.request.Request(url + "/api/v1/internal/rag-searches", data=json.dumps({"scope": scope, "question": q}).encode("utf-8"),
                                 headers={"Content-Type": "application/json; charset=utf-8", "Idempotency-Key": str(uuid.uuid4()),
                                          "X-Source-System": "hybrid-live"})
    return json.load(urllib.request.urlopen(req, timeout=120))


def short(h):
    return h["documentId" if "documentId" in h else "doc"].split("/")[-1][:24] + " " + (h.get("sectionPath") or h.get("section", "")).split(">")[-1].strip()[:20]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--es", default="http://localhost:19200")
    ap.add_argument("--url", default="http://localhost:8517")
    ap.add_argument("--pick", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    qs = {(q["id"], q["set"]): q for q in load_questions()}
    lines = ["| 구분 | 문항 | ES 만(상위 3) 근거 | 판정(이유) | 벡터 호출 | 끝난 곳 | 최종 근거 | 최종 근거 맞음 |", "|---|---|---|---|---|---|---|---|"]
    rows = []
    for p in json.load(open(a.pick, encoding="utf-8")):
        q = qs[(p["id"], p["set"])]
        hits, _ = bm25(a.es, q["role"], q["q"])
        es3 = boosted(hits, q["role"], 1.5)[:3]
        r = ask(a.url, q["role"].upper(), q["q"])
        ev = [{"doc": e["documentId"], "section": e["sectionPath"]} for e in r["evidence"]]
        grounded = q["label"] == "GROUNDED"
        es_ok = any(correct(q, h) for h in es3) if grounded else None
        final_ok = (any(correct(q, h) for h in ev) if grounded else r["exit"] == "NO_ANSWER")
        rows.append({**p, "esTop3": [short(h) for h in es3], "esOk": es_ok, "exit": r["exit"], "reason": r["decision"]["reason"],
                     "vectorCalls": r["embeddingCalls"], "cos1": r.get("vectorTop1Cosine"), "evidence": [short(e) for e in r["evidence"]], "finalOk": final_ok})
        es_cell = ("O" if es_ok else "X") if grounded else "—(근거 없음)"
        fin = "O" if final_ok else "X"
        cos = f" cos {r['vectorTop1Cosine']:.3f}" if r.get("vectorTop1Cosine") is not None else ""
        lines.append(f"| {p['kind']} | {p['id']} {q['q'][:26]} | {es_cell} {', '.join(short(h) for h in es3[:1])} | {r['decision']['reason']} | {r['embeddingCalls']}{cos} | {r['exit']} | {', '.join(short(e) for e in r['evidence'][:2]) or '—'} | {fin} |")
    g = [x for x in rows if x["esOk"] is not None]
    n = [x for x in rows if x["esOk"] is None]
    lines += ["", f"- 근거 있는 질문 {len(g)}: ES 만(상위 3) 맞음 {sum(1 for x in g if x['esOk'])} → 하이브리드 뒤 맞음 {sum(1 for x in g if x['finalOk'])}",
              f"- 근거 없는 질문 {len(n)}: 답변 불가로 끝남 {sum(1 for x in n if x['finalOk'])}",
              f"- ES 에서 끝난 것 {sum(1 for x in rows if x['exit'] == 'ES')} · 벡터 호출 {sum(x['vectorCalls'] for x in rows)}/{len(rows)}"]
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    open(a.out, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    json.dump(rows, open(a.out.replace(".md", ".json"), "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=1)
    sys.stdout.reconfigure(encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
