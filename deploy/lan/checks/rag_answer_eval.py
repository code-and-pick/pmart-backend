"""운영자 RAG 답변 정확도 평가(ADR-0051): 정답 사실 목록(eval/answers.json)으로 LAN 답을 채점한다.

실행: python -I deploy/lan/checks/rag_answer_eval.py [보고서 폴더]
  기본 보고서 폴더: docs/rag/verification/<오늘>/answer-eval/ (report.md · answers.json)
필요: deploy/lan/.secrets/keycloak-claude-dev-password·keycloak-admin-password(직원 토큰은 staff_auth.py — employee-auth-service 시험용 교환). 토큰·비밀번호는 출력하지 않는다.

채점(질문마다):
- 정답: ANSWER 는 HTTP 200 + 근거 있는 답(answerMode GENERATIVE·BASELINE_EXTRACTIVE) + facts 묶음을 모두 담음.
        NONE 은 answerMode NONE, REALTIME 은 실시간 거절. forbidden 표현이 있으면 오답.
- 근거 적중(따로 셈): 인용 근거에 sources 문서가 하나라도 있음.
- 주제 이탈(따로 셈): 근거 있는 답인데 sources 문서를 하나도 인용하지 않음 — 다른 주제 문서로 답했을 가능성.
"""
import datetime
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from staff_auth import staff_token  # noqa: E402 — 직원 토큰(employee-auth-service, 2026-11-13)
ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
SECRETS = os.path.join(ROOT, "deploy", "lan", ".secrets")
QFILE = os.path.join(ROOT, "services", "ai-assistant-service", "src", "integrationTest", "resources", "eval", "answers.json")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    ROOT, "docs", "rag", "verification", datetime.date.today().isoformat(), "answer-eval")


def read_secret(name):
    with open(os.path.join(SECRETS, name), encoding="utf-8") as f:
        return f.read().strip()


def http(method, url, token=None, body=None):
    headers = {"Accept": "application/json, application/problem+json", "X-Source-System": "backoffice-web"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        headers["Idempotency-Key"] = str(uuid.uuid4())
        data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw[:200].decode(errors="replace")}


def norm(s):
    return " ".join((s or "").split())


def grade(q, st, r):
    mode = r.get("answerMode")
    text = norm(r.get("answer"))
    cites = r.get("citations") or []
    paths = [c.get("path") or "" for c in cites]
    missing = [g for g in q.get("facts", []) if not any(alt in text for alt in g)]
    bad = [f for f in q.get("forbidden", []) if f in text]
    if st != 200:
        ok = False
    elif q["expect"] == "ANSWER":
        ok = mode in ("GENERATIVE", "BASELINE_EXTRACTIVE") and bool(cites) and not missing and not bad
    elif q["expect"] == "NONE":
        ok = mode == "NONE" and not bad
    else:  # REALTIME
        ok = mode == "NONE" and r.get("retrievalMode") in (None, "NONE") and not bad
    src_hit = any(p.startswith(s) for p in paths for s in q.get("sources", []))
    off_topic = q["expect"] == "ANSWER" and bool(cites) and q.get("sources") and not src_hit
    return ok, missing, bad, src_hit, off_topic, mode, text, paths, cites


def main():
    # import 만으로 30문항 평가가 돌지 않게(2026-11-16 PC 4 — 함수만 쓰려고 import 했다가 PC 2 ollama 로 전체 평가가 돌았다)
    token, _ = staff_token("claude-dev")

    with open(QFILE, encoding="utf-8") as f:
        questions = json.load(f)["questions"]

    rows, raw = [], []
    for q in questions:
        t0 = time.time()
        st, r = http("POST", f"{GW}/assistant/policy-questions", token, {"question": q["q"]})
        secs = time.time() - t0
        ok, missing, bad, src_hit, off_topic, mode, text, paths, cites = grade(q, st, r)
        rows.append((q, ok, missing, bad, src_hit, off_topic, mode, secs, st))
        raw.append({"id": q["id"], "q": q["q"], "http": st, "seconds": round(secs, 1), "answerMode": mode,
                    "retrievalMode": r.get("retrievalMode"), "answer": r.get("answer"),
                    "citations": [{"path": c.get("path"), "section": c.get("section"), "authority": c.get("authority")}
                                  for c in cites], "model": r.get("model")})
        print(f"{'OK  ' if ok else 'FAIL'} {q['id']} {secs:4.1f}s {mode or '-':20} 근거적중={'Y' if src_hit else '-'}"
              f"{' 주제이탈' if off_topic else ''}{' 빠짐=' + str(missing) if missing else ''}{' 금지=' + str(bad) if bad else ''}")

    n = len(rows)
    ans = [x for x in rows if x[0]["expect"] == "ANSWER"]
    correct = sum(1 for x in rows if x[1])
    ans_correct = sum(1 for x in ans if x[1])
    src = sum(1 for x in ans if x[4])
    off = sum(1 for x in ans if x[5])
    gen = sum(1 for x in ans if x[6] == "GENERATIVE")
    ext = sum(1 for x in ans if x[6] == "BASELINE_EXTRACTIVE")
    secs = sorted(x[7] for x in rows)
    model = raw[0].get("model") if raw else None

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "answers.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(raw, f, ensure_ascii=False, indent=1)
    lines = [
        f"# 운영자 RAG 답변 정확도 평가 ({datetime.datetime.now():%Y-%m-%d %H:%M})", "",
        f"- 대상: LAN 게이트웨이 `{GW}` · 모델 `{json.dumps(model, ensure_ascii=False)}`",
        f"- 질문 정의: `services/ai-assistant-service/src/integrationTest/resources/eval/answers.json` (정답 사실은 원문을 읽고 먼저 정함)",
        f"- **전체 정답 {correct}/{n}** · 근거로 답해야 하는 질문 {ans_correct}/{len(ans)} · 근거 적중 {src}/{len(ans)} · 주제 이탈 {off}",
        f"- 답 방식(근거 질문): 생성 {gen} · 발췌로 전환 {ext} · 그 밖 {len(ans) - gen - ext}",
        f"- 응답 시간: 중앙 {secs[n // 2]:.1f}초 · 최대 {secs[-1]:.1f}초", "",
        "| id | 결과 | 시간 | 답 방식 | 근거 적중 | 빠진 사실 · 금지 표현 | 질문 |", "|---|---|---|---|---|---|---|",
    ]
    for q, ok, missing, bad, src_hit, off_topic, mode, s, st in rows:
        note = "; ".join(["빠짐 " + "/".join(g) for g in missing] + ["금지 " + x for x in bad]) or ""
        lines.append(f"| {q['id']} | {'정답' if ok else '**오답**'} | {s:.1f}s | {mode or f'HTTP {st}'} | "
                     f"{'Y' if src_hit else ('주제 이탈' if off_topic else '-')} | {note} | {q['q']} |")
    lines += ["", "## 오답 상세", ""]
    for (q, ok, missing, bad, src_hit, off_topic, mode, s, st), r in zip(rows, raw):
        if ok:
            continue
        lines += [f"### {q['id']} — {q['q']}", f"- 기대: {q['expect']} · 정답 근거: {q.get('basis', '')}",
                  f"- 답({mode}): {norm(r['answer'])[:400]}",
                  "- 인용: " + ("; ".join(f"{c['path']} § {c['section']}" for c in r["citations"][:4]) or "없음"), ""]
    with open(os.path.join(OUT, "report.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print(f"---- 정답 {correct}/{n} (근거 질문 {ans_correct}/{len(ans)}, 근거 적중 {src}/{len(ans)}, 주제 이탈 {off}) → {OUT}")


if __name__ == "__main__":
    main()
