"""LAN 확인 — 2026-11-16~17 라이더 앱·고객앱 요청으로 만든 기능(실제 API·게이트웨이). 토큰·주소·전화는 출력하지 않는다.

배포된 것만 본다 — 아직 배포 전인 기능은 HTTP 404·501 로 "배포 전"이라 적고 넘어간다(실패로 세지 않는다).

  A. 픽업지 목록(#70)        라이더 GET /mfc-pickup-points — 운영 중 MFC, 승인 정보 없음
  B. 라이더 문의(#71)        라이더 POST·GET /inquiries(SETTLEMENT), 남의 문의 404, 관리자 보기, (--ops 있으면) 지원센터 답변
  C. 라이더 알림함(#78)      관리자가 시험 라이더 배차 제한(기한) → 풀기 → 라이더 GET /me/notifications 에 두 건(사유 없음)
  D. 도착 예정·구독(#75·#81)  --delivery 가 있으면 관리자 GET /deliveries/{id}/eta·/stream 첫 이벤트, (--customer-token-file) 고객 본인 조회
  E. 전달 증빙(#80)          --delivery 가 완료 배송이고 --customer-token-file 이 있으면 GET /deliveries/{id}/delivery-proof·사진 한 장
  F. 운행시간(#115)          라이더 GET /riders/me/operating-time — 새 라이더는 0초, 기간 31일 넘으면 400
  G. 수행료 조정 대기(#119)  관리자 GET /rider-earnings/adjustment-queue 200, 라이더 403, 없는 수행료 조정 404
  H. 탈퇴(#118)              새 시험 라이더 가입 → POST /riders/me/withdrawals 202 APPLIED → 결과 COMPLETED → /riders/me 404
                             → (rider-auth SubjectBlocked 뒤) 같은 토큰 401

실행: uv run --no-project --with cryptography python -I deploy/lan/checks/rider_app_features_e2e.py
        [--delivery DLV-…] [--customer-token-file 파일] [--ops 직원계정]
필요: rider_e2e.py 와 같다(라이더 서명키·claude-dev 비밀번호). --ops 는 RIDER_OPS 역할을 줄 수 있는 시험 직원 계정(staff_auth.py).
2026-11-17 처음 작성(PC 5 — PR #70·#71·#75·#76·#78·#80·#81·#82 배포 뒤 확인용).
"""
import os
import sys
import time
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from rider_e2e import GW, MFC, check, http, phone, rider_token, summary  # noqa: E402
from staff_auth import staff_token  # noqa: E402


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def not_deployed(name, status):
    if status in (404, 501):
        print(f"  --   {name} — 배포 전(HTTP {status}), 넘어감")
        return True
    return False


def signup(token):
    s, b = http("POST", "/riders/signups", token, {
        "phone": phone(), "name": "시험라이더", "vehicleType": "BICYCLE",
        "deliveryAreas": [{"mfcId": MFC}], "consents": [{"consentType": "TERMS", "version": "2026-11"}]})
    check("시험 라이더 가입(자전거 — 면허 없음)", s == 201, f"HTTP {s} {b.get('code', '')}")
    return b


def pickup_points(token):
    s, b = http("GET", "/mfc-pickup-points", token)
    if not_deployed("A 픽업지 목록", s):
        return
    ids = [i.get("centerId") for i in b.get("items", [])]
    check("A 픽업지 목록 — 라이더 200, 시험 지점 있음", s == 200 and MFC in ids, f"HTTP {s} {len(ids)}곳")
    check("A 승인 정보 없음", "approvalRef" not in str(b) and "approvedBy" not in str(b))


def rider_inquiry(rider, token, admin, ops):
    s, b = http("POST", "/inquiries", token, {"category": "SETTLEMENT", "title": "LAN 시험", "body": "LAN 시험 문의(자동)"})
    if not_deployed("B 라이더 문의", s) or (s == 403 and b.get("code") == "FORBIDDEN"):
        print("  --   B 라이더 문의 — 이전 버전(라이더 403), 넘어감")
        return None
    ok = check("B 라이더 문의 남기기", s == 201 and b.get("requesterType") == "RIDER", f"HTTP {s} {b.get('code', '')}")
    if not ok:
        return None
    iid = b["inquiryId"]
    s, b = http("GET", f"/inquiries/{iid}", rider_token("RD-claude-other-" + uuid.uuid4().hex[:6]))
    check("B 다른 라이더 404", s == 404, f"HTTP {s}")
    s, b = http("POST", "/inquiries", token, {"category": "ORDER", "body": "x"})
    check("B 라이더가 고객 분류 400", s == 400, f"HTTP {s}")
    s, b = http("GET", f"/inquiries/{iid}", admin)
    check("B 관리자 보기", s == 200, f"HTTP {s}")
    if ops:
        s, b = http("POST", f"/inquiries/{iid}/replies", ops, {"body": "LAN 시험 답변(자동)"}, version=0)
        check("B 지원센터 답변 → ANSWERED", s == 200 and b.get("status") == "ANSWERED", f"HTTP {s} {b.get('code', '')}")
    return iid


def rider_notices(rider, token, admin):
    s, b = http("GET", "/me/notifications?size=20", token)
    if not_deployed("C 라이더 알림함", s):
        return
    before = len(b.get("items", []))
    s, cur = http("GET", f"/riders/{rider}/restrictions", admin)
    v = cur.get("version", 0)
    until = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 3600))
    s, b = http("POST", f"/riders/{rider}/restrictions", admin,
                {"restriction": "DISPATCH_RESTRICTED", "reason": "LAN 시험(자동) — 민감한 사유 문구", "until": until}, version=v)
    if not check("C 배차 제한(관리자)", s in (200, 201), f"HTTP {s} {b.get('code', '')}"):
        return
    s, cur = http("GET", f"/riders/{rider}/restrictions", admin)
    s, b = http("POST", f"/riders/{rider}/restrictions", admin,
                {"restriction": "NORMAL", "reason": "LAN 시험 끝(자동)"}, version=cur.get("version", 0))
    check("C 제한 풀기", s in (200, 201), f"HTTP {s} {b.get('code', '')}")
    items, deadline = [], time.time() + 60
    while time.time() < deadline:
        s, b = http("GET", "/me/notifications?size=20", token)
        items = b.get("items", [])
        if len(items) >= before + 2:
            break
        time.sleep(3)
    titles = [i.get("title") for i in items]
    if len(items) == before:
        print("  --   C 라이더 알림 0건 — notification 이 riderEvents 를 아직 받지 않음(설정 바인딩 전?) — 확인 필요")
    check("C 알림 두 건(제한·풀림)", "새 배차가 제한되었어요" in titles and "계정 제한이 풀렸어요" in titles, str(titles[:4]))
    check("C 사유 문구 없음", "민감한 사유 문구" not in str(items))


def operating_time(token):
    s, b = http("GET", "/riders/me/operating-time", token)
    if not_deployed("F 운행시간", s):
        return
    check("F 운행시간 — 새 라이더 0초", s == 200 and b.get("totalSeconds") == 0 and len(b.get("days", [])) == 1,
          f"HTTP {s} {b.get('totalSeconds')}")
    s, b = http("GET", "/riders/me/operating-time?from=2026-01-01&to=2026-11-17", token)
    check("F 기간 31일 넘으면 400", s == 400, f"HTTP {s}")


def adjustment_queue(token, admin):
    s, b = http("GET", "/rider-earnings/adjustment-queue?size=5", admin)
    if not_deployed("G 수행료 조정 대기", s):
        return
    check("G 조정 대기 — 관리자 200", s == 200 and "items" in b, f"HTTP {s} {len(b.get('items', []))}건")
    s, b = http("GET", "/rider-earnings/adjustment-queue", token)
    check("G 라이더 403", s == 403, f"HTTP {s}")
    s, b = http("POST", "/rider-earnings/ERN-none/adjustments", admin, {"fault": "CUSTOMER", "reason": "LAN 시험"})
    check("G 없는 수행료 조정 404", s == 404, f"HTTP {s} {b.get('code', '')}")


def withdrawal():
    rider = "RD-claude-wd-" + uuid.uuid4().hex[:10]
    token = rider_token(rider)
    signup(token)
    s, b = http("POST", "/riders/me/withdrawals", token, {"reason": "LAN 시험 탈퇴(자동)"})
    if not_deployed("H 탈퇴", s):
        return
    ok = check("H 탈퇴 — 202 APPLIED", s == 202 and b.get("status") == "APPLIED", f"HTTP {s} {b.get('code', '')}")
    if not ok:
        return
    rid = b.get("requestId")
    s, b = http("GET", f"/riders/me/withdrawals/{rid}", token)
    check("H 결과 COMPLETED", s == 200 and b.get("state") == "COMPLETED", f"HTTP {s} {b.get('state')}")
    s, b = http("GET", "/riders/me", token)
    check("H 탈퇴 뒤 /riders/me 404(또는 이미 401)", s in (404, 401), f"HTTP {s}")
    status, deadline = None, time.time() + 30
    while time.time() < deadline:
        status, _ = http("GET", "/me/notifications?size=1", token)
        if status == 401:
            break
        time.sleep(3)
    check("H 세션 끊김 — 같은 토큰 401(SubjectBlocked)", status == 401, f"HTTP {status}")


def eta(delivery, admin, customer):
    s, b = http("GET", f"/deliveries/{delivery}/eta", admin)
    if not_deployed("D 도착 예정 조회", s):
        return
    check("D 관리자 도착 예정 조회", s == 200 and b.get("stage") is not None,
          f"HTTP {s} 단계 {b.get('stage')} 도착 {b.get('dropEta', {}).get('status')}")
    req = urllib.request.Request(GW + f"/deliveries/{delivery}/stream", headers={
        "Authorization": "Bearer " + admin, "Accept": "text/event-stream"})
    first = None
    try:
        with urllib.request.urlopen(req, timeout=15) as res:
            for raw in res:
                line = raw.decode().strip()
                if line.startswith("event: "):
                    first = line[7:]
                    break
    except Exception as e:  # noqa: BLE001 — 게이트웨이 버퍼링·시간 초과도 결과로 적는다
        first = f"오류 {type(e).__name__}"
    check("D 실시간 구독 첫 이벤트(게이트웨이 통과)", first in ("ETA_UPDATED", "STREAM_END"), str(first))
    if customer:
        s, b = http("GET", f"/deliveries/{delivery}/eta", customer)
        check("D 고객 본인 조회(픽업 전 라이더 좌표 없음)",
              s == 200 and (b.get("stage") in ("TO_CUSTOMER", "AT_CUSTOMER") or not b.get("riderLocation")),
              f"HTTP {s} 단계 {b.get('stage')}")


def proof(delivery, customer):
    s, b = http("GET", f"/deliveries/{delivery}/delivery-proof", customer)
    if not_deployed("E 전달 증빙", s) and b.get("code") != "DELIVERY_NOT_FOUND":
        return
    if s == 409:
        print("  --   E 전달 증빙 — 아직 완료 전(409)")
        return
    ok = check("E 전달 증빙(고객 본인)", s == 200 and b.get("photoRefs"), f"HTTP {s} {b.get('code', '')}")
    if ok:
        ref = b["photoRefs"][0]
        req = urllib.request.Request(GW + f"/deliveries/{delivery}/evidence-photos/{ref}",
                                     headers={"Authorization": "Bearer " + customer})
        try:
            with urllib.request.urlopen(req, timeout=20) as res:
                check("E 완료 사진 보기", res.status == 200 and res.headers.get("Content-Type", "").startswith("image/"))
        except Exception as e:  # noqa: BLE001
            check("E 완료 사진 보기", False, type(e).__name__)


def main():
    delivery = arg("--delivery")
    tf = arg("--customer-token-file")
    customer = open(tf, encoding="utf-8").read().strip() if tf else None
    ops_user = arg("--ops")
    rider = "RD-claude-" + uuid.uuid4().hex[:12]
    token = rider_token(rider)
    admin = staff_token("claude-dev")
    admin = admin[0] if isinstance(admin, tuple) else admin
    ops = None
    if ops_user:
        ops = staff_token(ops_user, roles=["RIDER_OPS"], centers=[])
        ops = ops[0] if isinstance(ops, tuple) else ops
    print(f"시험 라이더 {rider}" + (f", 배송 {delivery}" if delivery else ""))
    signup(token)
    pickup_points(token)
    rider_inquiry(rider, token, admin, ops)
    rider_notices(rider, token, admin)
    operating_time(token)
    adjustment_queue(token, admin)
    withdrawal()
    if delivery:
        eta(delivery, admin, customer)
        if customer:
            proof(delivery, customer)
    summary()


if __name__ == "__main__":
    main()
