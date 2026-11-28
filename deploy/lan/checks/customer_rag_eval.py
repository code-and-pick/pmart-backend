"""LAN 고객 RAG 답변 평가(eval/customer-answers.json). 기계 채점 — 사람이 답을 다시 읽어 판정한다.

실행: python -I deploy/lan/checks/customer_rag_eval.py [보고서.json]
고객 토큰(2026-11-28): auth-service 개발용 고객 토큰 `POST :8501/api/v1/internal/dev-customer-tokens`(허용 목록 계정만, 15분)으로
12분마다 새로 받는다. 계정 = 사용자 카카오 계정(사용자 2026-11-28 "이걸 고객 토큰으로 쓰자" — 실제 계정이라 질문·답이 그 계정 상담 기록으로 남는다).
다른 계정은 PMART_CUSTOMER_ACCOUNT, 토큰을 직접 주려면 PMART_CUSTOMER_TOKEN_FILE=<파일>(예전 .secrets/customer-token 은 15분 지나면 못 쓰니 기본으로 읽지 않는다).
요청 제한(1분 5개)에 걸리지 않게 질문 사이 13초 쉰다(44문항 약 10분). 같은 고객으로 하루 50개를 넘기지 않게 한다.
faq 표시(D2 판정용)별 정답을 따로 센다.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.stdout.reconfigure(encoding="utf-8")
GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
HERE = os.path.dirname(__file__)
AUTH = os.environ.get("PMART_AUTH_URL", "http://192.168.0.40:8501/api/v1")
ACCOUNT = os.environ.get("PMART_CUSTOMER_ACCOUNT", "CU-5525caf2-f20d-4038-94e3-80048c5e85f5")
TOKEN_FILE = os.environ.get("PMART_CUSTOMER_TOKEN_FILE")
_tok = {"value": None, "at": 0.0}


def customer_token():
    if TOKEN_FILE:
        return open(TOKEN_FILE, encoding="utf-8").read().strip()
    if _tok["value"] and time.time() - _tok["at"] < 12 * 60:
        return _tok["value"]
    req = urllib.request.Request(
        f"{AUTH}/internal/dev-customer-tokens", json.dumps({"accountId": ACCOUNT}).encode(),
        {"Content-Type": "application/json", "Idempotency-Key": str(uuid.uuid4()),
         "X-Source-System": "customer-app", "X-Device-Id": "pc1-rag-eval"}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        _tok["value"] = json.loads(r.read())["accessToken"]
    _tok["at"] = time.time()
    return _tok["value"]


qs = json.load(open(os.path.join(HERE, "eval", "customer-answers.json"), encoding="utf-8"))["questions"]
out_path = sys.argv[1] if len(sys.argv) > 1 else None


def ask(q):
    h = {"Content-Type": "application/json", "Accept": "application/json", "X-Source-System": "customer-app",
         "Idempotency-Key": str(uuid.uuid4()), "Authorization": "Bearer " + customer_token()}
    req = urllib.request.Request(f"{GW}/customer-assistant/questions", json.dumps({"question": q}).encode(), h, method="POST")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=70) as r:
            return r.status, json.loads(r.read()), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}"), time.time() - t0


rows, good = [], 0
for i, c in enumerate(qs):
    if i:
        time.sleep(13)
    st, b, took = ask(c["q"])
    state = b.get("state", f"HTTP{st}")
    answer = b.get("answer", "") or ""
    docs = [x.get("documentId", "").rsplit("/", 1)[-1] for x in b.get("citations", [])]
    ok_state = state == c["state"]
    ok_doc = "doc" not in c or any(d.startswith(p) for d in docs for p in c["doc"].split("|"))
    missing = [f for f in c.get("facts", []) if not any(alt in answer for alt in f.split("|"))]
    ok = ok_state and ok_doc and not missing
    good += ok
    rows.append({"id": c["id"], "faq": c.get("faq"), "q": c["q"], "want": c["state"], "state": state, "mode": b.get("answerMode"),
                 "answerId": b.get("answerId"), "docs": docs, "missing": missing, "ok": ok,
                 "tookSec": round(took, 1), "answer": answer})
    print(f"{'OK  ' if ok else 'FAIL'} {c['id']} {state:<22} {took:4.1f}s {','.join(docs[:2]):<40} "
          f"{'빠짐:' + str(missing) if missing else ''} | {answer[:90].replace(chr(10), ' ')}")
print(f"---- 기계 채점 {good} / {len(qs)}")
labels = ["EXACT", "PARAPHRASE", "NEAR", "WEAK", "NONE"]
print("     faq 표시별: " + " · ".join(
    f"{k} {sum(1 for r in rows if r['faq'] == k and r['ok'])}/{sum(1 for r in rows if r['faq'] == k)}"
    for k in labels if any(r["faq"] == k for r in rows)))
if out_path:
    json.dump(rows, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
