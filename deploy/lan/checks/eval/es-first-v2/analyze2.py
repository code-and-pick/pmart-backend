#!/usr/bin/env python3
"""ES-First 2차 검증 분석 — es_first_eval2.py 의 기록으로 검색 비교·ES 종료 규칙·벡터 보완을 잰다.

  python analyze2.py <out 폴더>            # dev+regression 으로 가중치·규칙·임계값을 고르고 config.json 에 고정
  python analyze2.py <out 폴더> --final    # config.json 을 그대로 final 에 한 번 적용(고르지 않음)

미확정(uncertain) 문항은 확정 집계에서 빼고 따로 센다. 분모를 늘 함께 적는다.
"""
import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from es_first_eval2 import DOC_FLOW, boosted, correct, doc_hit, rrf  # noqa: E402

BOOSTS = [1.0, 1.2, 1.5, 2.0]
# ES 에서 끝낼 때 돌려주는 것: 상위 3 문서 카드(계획 §2-2 "찾기 → 문서 카드" — 2026-11-28 dev 에서 1위 청크 하나로는 잘못 끝냄 0 규칙이 없음을 확인)
CARDS = 3
TAUS = [round(0.40 + 0.01 * i, 2) for i in range(31)]


def rules():
    out = [("NEVER", lambda f, h: False)]
    for g in (2, 3, 4, 5, 6, 8):
        out.append((f"gap>={g}", lambda f, h, g=g: f["gap"] >= g))
    for c in (0.6, 0.7, 0.8, 0.9, 1.0):
        out.append((f"cov>={c}", lambda f, h, c=c: f["coverage"] >= c))
        for r in (0.1, 0.15, 0.2, 0.3):
            out.append((f"cov>={c}&relGap>={r}", lambda f, h, c=c, r=r: f["coverage"] >= c and f["relGap"] >= r))
        out.append((f"FAQ&cov>={c}", lambda f, h, c=c: bool(h) and h[0].get("type") == "FAQ" and f["coverage"] >= c))
        for t in (0.5, 1.0):
            out.append((f"cov>={c}&title>={t}", lambda f, h, c=c, t=t: f["coverage"] >= c and f["titleMatch"] >= t))
    return out


def pct(n, d):
    return f"{n}/{d} ({100 * n / d:.0f}%)" if d else f"{n}/0"


def confirmed(r):
    return not r["uncertain"]


def ranked(r, boost):
    return boosted(r["bm25"], r["role"], boost)


def retrieval_table(recs, boost):
    g = [r for r in recs if r["label"] == "GROUNDED" and confirmed(r)]
    rows = []
    for name, lst in (("BM25", lambda r: r["bm25"]), (f"BM25+가중치 ×{boost}", lambda r: ranked(r, boost)),
                      ("벡터만", lambda r: r["vector"]), ("BM25+벡터(RRF)", lambda r: rrf(ranked(r, boost), r["vector"]))):
        h1 = sum(1 for r in g if correct(r, (lst(r) or [None])[0]))
        h3 = sum(1 for r in g if any(correct(r, x) for x in lst(r)[:3]))
        d1 = sum(1 for r in g if doc_hit(r, (lst(r) or [None])[0]))
        d3 = sum(1 for r in g if any(doc_hit(r, x) for x in lst(r)[:3]))
        rows.append(f"| {name} | {pct(h1, len(g))} | {pct(h3, len(g))} | {pct(d1, len(g))} | {pct(d3, len(g))} |")
    return "| 조건 | 근거 청크 1위 | 근거 청크 3위 안 | 문서 1위 | 문서 3위 안 |\n|---|---|---|---|---|\n" + "\n".join(rows)


def simulate(recs, rule, boost, tau, cards=1):
    """ES-First 흐름: 규칙이 받아들이면 BM25(가중치) 1위 청크 하나로 끝, 아니면 질문 임베딩 → RRF 상위 3 → 벡터 1위 코사인 < tau 면 답변 불가."""
    out = []
    for r in recs:
        if r["label"] not in DOC_FLOW:
            continue
        hits = ranked(r, boost)
        accept = bool(hits) and rule(r["features"], hits)
        res = {"id": r["id"], "set": r["set"], "role": r["role"], "label": r["label"], "uncertain": r["uncertain"], "accepted": accept,
               "q": r["q"]}
        if accept:
            res["returned"] = hits[:cards]
            res["ok"] = any(correct(r, h) for h in hits[:cards])
            res["latencyMs"] = r["latency"]["es"]
            res["models"] = 0
        else:
            fused = rrf(hits, r["vector"])
            cos1 = r["vector"][0]["cos"] if r["vector"] else 0
            no_answer = cos1 < tau
            res.update({"returned": [] if no_answer else fused[:3], "noAnswer": no_answer, "cos1": cos1,
                        "bm25Top1Ok": correct(r, hits[0]) if hits else False,
                        "bm25Top3Ok": any(correct(r, x) for x in hits[:3]),
                        "fusedTop3Ok": any(correct(r, x) for x in fused[:3]),
                        "fusedTop1Ok": correct(r, fused[0]) if fused else False,
                        "latencyMs": r["latency"]["es"] + r["latency"]["embed"] + r["latency"]["pgvector"],
                        "models": 1 + (0 if no_answer else 1)})  # 임베딩 1 + (답 생성 1 — 추정, 이번에 돌리지 않음)
        out.append(res)
    return out


def metrics(sim):
    s = [x for x in sim if not x["uncertain"]]
    acc = [x for x in s if x["accepted"]]
    acc_ok = [x for x in acc if x.get("ok")]
    fa_neg = [x for x in acc if x["label"] != "GROUNDED"]
    fa_wrong = [x for x in acc if x["label"] == "GROUNDED" and not x.get("ok")]
    fb = [x for x in s if not x["accepted"]]
    fb_g = [x for x in fb if x["label"] == "GROUNDED"]
    unnecessary = [x for x in fb_g if x["bm25Top1Ok"]]
    pool_miss = [x for x in fb_g if not x["bm25Top3Ok"]]
    recovered = [x for x in pool_miss if x["fusedTop3Ok"]]
    pool_rank = [x for x in fb_g if x["bm25Top3Ok"] and not x["bm25Top1Ok"]]
    rank_fixed = [x for x in pool_rank if x["fusedTop1Ok"]]
    neg = [x for x in s if x["label"] == "NO_GROUNDING"]
    neg_ok = [x for x in neg if not x["accepted"] and x.get("noAnswer")]
    g_lost = [x for x in fb_g if x.get("noAnswer")]
    g_all = [x for x in s if x["label"] == "GROUNDED"]
    ev3 = [x for x in g_all if (x["accepted"] and x.get("ok")) or (not x["accepted"] and not x.get("noAnswer") and x["fusedTop3Ok"])]
    lat = sorted(x["latencyMs"] for x in s)
    p = lambda q: lat[min(len(lat) - 1, int(round(q * (len(lat) - 1))))] if lat else 0
    return {
        "n": len(s), "uncertainExcluded": len(sim) - len(s),
        "acceptance": pct(len(acc), len(s)), "precision": pct(len(acc_ok), len(acc)),
        "falseAccept_noGrounding": [x["id"] for x in fa_neg], "falseAccept_wrongEvidence": [x["id"] for x in fa_wrong],
        "unnecessaryFallback": pct(len(unnecessary), len(fb_g)), "ragRecovery_newEvidence": pct(len(recovered), len(pool_miss)),
        "ragRecovery_rankFix": pct(len(rank_fixed), len(pool_rank)), "noAnswerAccuracy": pct(len(neg_ok), len(neg)),
        "groundedDeclaredNoAnswer": [x["id"] for x in g_lost], "evidenceHit@3": pct(len(ev3), len(g_all)),
        "embeddingCalls": pct(sum(1 for x in s if not x["accepted"]), len(s)),
        "generationCalls(est)": pct(sum(1 for x in s if not x["accepted"] and not x.get("noAnswer")), len(s)),
        "latencyMs_p50": round(p(0.5), 1), "latencyMs_p95": round(p(0.95), 1),
        "falseAcceptCount": len(fa_neg) + len(fa_wrong), "acceptCount": len(acc), "accOk": len(acc_ok),
    }


# 채점상 오답이지만 원문으로는 같은 근거(고정 회귀셋 라벨은 고치지 않고 따로 적는다 — 2026-11-28 원문 확인)
EQUIVALENT = {
    "D01": "customer/faq/02-cancel.md '취소하면 봉투값도 돌려받나요?' — '일부만 취소되면 봉투값은 환불되지 않아요'",
    "B07": "operator/rider_app/02-operation-dispatch.md 제3조 (배차 방식) — AI 추천·일반 배차모드",
}


def choose(recs):
    """dev+regression 으로: 가중치(근거 청크 3위 안·1위 최대), 규칙(잘못 끝냄 0 에서 종료 최대), tau(근거 없음 판정 + 근거 유지의 합 최대)."""
    g = [r for r in recs if r["label"] == "GROUNDED" and confirmed(r)]
    best_b = max(BOOSTS, key=lambda b: (sum(1 for r in g if correct(r, ranked(r, b)[0])), -abs(b - 1.0)))
    cands = []
    for name, rule in rules():
        m = metrics(simulate(recs, rule, best_b, 0.0, CARDS))
        cands.append((m["falseAcceptCount"] == 0, m["acceptCount"], -len(name), name, rule, m))
    cands.sort(key=lambda x: (x[0], x[1], x[2]), reverse=True)
    _, _, _, rname, rule, _ = cands[0]
    best_t, best_v = 0.0, -1
    for t in TAUS:
        m = simulate(recs, rule, best_b, t, CARDS)
        s = [x for x in m if not x["uncertain"] and not x["accepted"]]
        v = sum(1 for x in s if x["label"] == "NO_GROUNDING" and x["noAnswer"]) + sum(1 for x in s if x["label"] == "GROUNDED" and not x["noAnswer"])
        if v > best_v:
            best_t, best_v = t, v
    return best_b, rname, best_t, cands


def main():
    out = sys.argv[1]
    final = "--final" in sys.argv
    if not final:
        recs = json.load(open(os.path.join(out, "records-dev.json"), encoding="utf-8"))
        boost, rname, tau, cands = choose(recs)
        json.dump({"boost": boost, "rule": rname, "tau": tau, "cards": CARDS, "chosenOn": "dev+regression"}, open(os.path.join(out, "config.json"), "w"), indent=1)
    else:
        recs = json.load(open(os.path.join(out, "records-final.json"), encoding="utf-8"))
        cfg = json.load(open(os.path.join(out, "config.json")))
        boost, rname, tau = cfg["boost"], cfg["rule"], cfg["tau"]
    rule = dict(rules())[rname]
    lines = [f"설정: 가중치 POLICY ×{boost} · ES 종료 규칙 `{rname}`(종료 때 상위 {CARDS} 카드) · 답변 불가 기준 벡터 1위 코사인 < {tau}"
             + (" (dev+regression 으로 고름)" if not final else " (config.json 고정값 — final 에서 고르지 않음)"), ""]
    sets = ["final"] if final else ["regression", "dev"]
    report = {}
    for st in sets:
        rs = [r for r in recs if r["set"] == st]
        for role in ("operator", "customer"):
            rr = [r for r in rs if r["role"] == role]
            if not rr:
                continue
            lines.append(f"## {st} · {role}")
            lines.append(f"- 문항 {len(rr)}: " + ", ".join(f"{k} {sum(1 for r in rr if r['label'] == k)}" for k in
                                                         ("GROUNDED", "NO_GROUNDING", "NEEDS_CLARIFICATION", "REALTIME", "PRODUCT", "OUT_OF_SCOPE")
                                                         if any(r["label"] == k for r in rr))
                         + f" · 미확정 {sum(1 for r in rr if r['uncertain'])}(집계 제외)")
            lines.append("")
            lines.append(retrieval_table(rr, boost))
            lines.append("")
            m = metrics(simulate(rr, rule, boost, tau, CARDS))
            report[f"{st}/{role}"] = m
            for k, v in m.items():
                if k not in ("falseAcceptCount", "acceptCount", "accOk"):
                    lines.append(f"- {k}: {v}")
            lines.append("")
    if not final:
        lines.append("## 규칙 후보 상위(잘못 끝냄 0 → 종료 많은 순) — dev+regression")
        for ok, n, _, name, _, m in cands[:12]:
            lines.append(f"- `{name}`: 잘못 끝냄 {m['falseAcceptCount']} · 종료 {m['acceptance']} · 정확 {m['precision']}")
        lines.append("")
        lines.append("## 돌려주는 방식 비교 — 1위 청크 하나 vs 상위 3 카드(계획 §2-2 찾기 → 문서 카드)")
        for cards in (1, 3):
            best = None
            for name, rule in rules():
                m = metrics(simulate(recs, rule, boost, 0.0, cards))
                if m["falseAcceptCount"] == 0 and (best is None or m["acceptCount"] > best[1]["acceptCount"]):
                    best = (name, m)
            lines.append(f"- 카드 {cards}개: 잘못 끝냄 0 인 규칙 중 종료가 가장 많은 것 `{best[0]}` — 종료 {best[1]['acceptance']}")
            for name in ("cov>=0.7", "cov>=0.8", "cov>=0.8&relGap>=0.15", "cov>=1.0", "cov>=1.0&relGap>=0.15", "FAQ&cov>=0.8"):
                m = metrics(simulate(recs, dict(rules())[name], boost, 0.0, cards))
                lines.append(f"  - `{name}`: 종료 {m['acceptance']}, 정확 {m['precision']}, 근거 없음 수락 {m['falseAccept_noGrounding']}, 잘못된 근거 {m['falseAccept_wrongEvidence']}")
        lines.append("")
        lines.append("## 채점상 오답이지만 원문으로는 같은 근거(고정 회귀셋 — 라벨은 그대로)")
        for k, v in EQUIVALENT.items():
            lines.append(f"- {k}: {v}")
        lines.append("")
        lines.append("## 단일 지표 규칙 — 점수 차이만(gap) 쓸 때의 실제 잘못 끝냄")
        for name in ("gap>=2", "gap>=3", "gap>=4", "gap>=6"):
            m = metrics(simulate(recs, dict(rules())[name], boost, 0.0))
            lines.append(f"- `{name}`: 종료 {m['acceptance']}, 잘못 끝냄 — 근거 없음 수락 {m['falseAccept_noGrounding']}, 잘못된 근거 {m['falseAccept_wrongEvidence']}")
    name = "report-final.md" if final else "report-dev.md"
    open(os.path.join(out, name), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    json.dump(report, open(os.path.join(out, name.replace(".md", ".json")), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    sys.stdout.reconfigure(encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
