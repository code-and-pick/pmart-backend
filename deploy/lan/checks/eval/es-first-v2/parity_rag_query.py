#!/usr/bin/env python3
"""rag-query-service(Java SearchConfidenceEvaluator) ↔ 2차 검증 기록(Python) 대조 — 같은 색인·같은 설정에서 문항마다 지표·끝난 곳이 같은지.

  python parity_rag_query.py --url http://localhost:8517 --out <폴더>
"""
import argparse
import json
import os
import statistics
import sys
import time
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from analyze2 import rules, simulate  # noqa: E402

V = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", "..", "docs/rag/verification/2026-11-28/es-first-v2"))


def call(url, scope, q):
    body = json.dumps({"scope": scope, "question": q}).encode("utf-8")
    req = urllib.request.Request(url + "/api/v1/internal/rag-searches", data=body, headers={
        "Content-Type": "application/json; charset=utf-8", "Idempotency-Key": str(uuid.uuid4()), "X-Source-System": "es-first-parity"})
    t = time.perf_counter()
    r = json.load(urllib.request.urlopen(req, timeout=120))
    return r, (time.perf_counter() - t) * 1000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8517")
    ap.add_argument("--out", default=V)
    a = ap.parse_args()
    cfg = json.load(open(os.path.join(V, "config.json")))
    rule = dict(rules())[cfg["rule"]]
    recs = json.load(open(os.path.join(V, "records-dev.json"), encoding="utf-8")) + json.load(open(os.path.join(V, "records-final.json"), encoding="utf-8"))
    py = {x["id"] + "|" + x["set"]: x for x in simulate(recs, rule, cfg["boost"], cfg["tau"], cfg["cards"])}
    rows, lat = [], []
    for r in recs:
        if r["label"] not in ("GROUNDED", "NO_GROUNDING", "NEEDS_CLARIFICATION"):
            continue
        j, ms = call(a.url, r["role"].upper(), r["q"])
        lat.append(ms)
        f = j["decision"]["features"]
        p = py[r["id"] + "|" + r["set"]]
        p_exit = "ES" if p["accepted"] else ("NO_ANSWER" if p.get("noAnswer") else "HYBRID")
        rows.append({"id": r["id"], "set": r["set"], "role": r["role"], "label": r["label"],
                     "javaExit": j["exit"], "pythonExit": p_exit,
                     "coverage": [f["coverage"], r["features"]["coverage"]], "relGap": [f["relGap"], r["features"]["relGap"]],
                     "javaCos1": j.get("vectorTop1Cosine"), "pythonCos1": p.get("cos1"),
                     "javaEvidence": [e["documentId"] + " | " + e["sectionPath"].split(">")[-1].strip() for e in j["evidence"]],
                     "phraseMatch": f["phraseMatch"], "faqQuestionMatch": f["faqQuestionMatch"], "titleMatch": f["titleMatch"],
                     "millis": j["millis"], "httpMs": round(ms, 1)})
    same_exit = sum(1 for x in rows if x["javaExit"] == x["pythonExit"])
    same_feat = sum(1 for x in rows if abs(x["coverage"][0] - x["coverage"][1]) < 1e-6 and abs(x["relGap"][0] - x["relGap"][1]) < 1e-6)
    lat.sort()
    p = lambda q: lat[min(len(lat) - 1, int(round(q * (len(lat) - 1))))]
    by = {}
    for x in rows:
        by[x["javaExit"]] = by.get(x["javaExit"], 0) + 1
    lines = [f"# rag-query-service ↔ 2차 검증(Python) 대조 — {len(rows)}문항(문서 흐름: 근거 있음·근거 없음·추가 질문)", "",
             f"- 지표(핵심어 비율·상대 점수 차) 같음: {same_feat}/{len(rows)}",
             f"- 끝난 곳(ES·HYBRID·NO_ANSWER) 같음: {same_exit}/{len(rows)}",
             f"- Java 끝난 곳: {by}",
             f"- HTTP 지연(질문 1개, 순차): p50 {p(0.5):.0f} ms · p95 {p(0.95):.0f} ms", ""]
    diffs = [x for x in rows if x["javaExit"] != x["pythonExit"] or abs(x["coverage"][0] - x["coverage"][1]) >= 1e-6 or abs(x["relGap"][0] - x["relGap"][1]) >= 1e-6]
    if diffs:
        lines.append("## 다른 문항")
        for x in diffs:
            lines.append(f"- {x['set']} {x['id']}: Java {x['javaExit']} / Python {x['pythonExit']} · coverage {x['coverage']} · relGap {x['relGap']} · cos1 Java {x['javaCos1']} / Python {x['pythonCos1']}")
    open(os.path.join(a.out, "parity-rag-query.md"), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    json.dump(rows, open(os.path.join(a.out, "parity-rag-query.json"), "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=1)
    sys.stdout.reconfigure(encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
