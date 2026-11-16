"""LAN 오배송 처리 끝까지 확인(ADR-0065 — 실제 API·게이트웨이). 토큰·주소·전화는 출력하지 않는다.

완료된 배송 하나(배차 CLOSED)를 오배송으로 확인하고, 귀책·고객 선택대로 흘러가는지 본다. 배송마다 오배송 사건은 하나라 시나리오마다 새로 완료된
배송이 필요하다(고객 주문 → MFC → rider_e2e.py 로 전달 완료).

  고객 잘못·다시 배송(기본): 상담원이 실제 집 주소 기록(order) → 오배송 사건(case, fault CUSTOMER·REDELIVER) → 배차가 다시 배송을 만들고
    잘못 둔 곳 ↔ 실제 집 직선거리로 판정
    · 200m 미만: 원래 기사에게 배달료 0원 제안 → 수락 → 잘못 둔 곳에서 사진·받음(PICKED_UP) → 전달지 = 실제 집 → 고객 도착 → 사진 → 완료
    · 200m 이상: 결제 대기(배차 안 함). --customer-token-file 이 있으면 고객 결제 대기 목록·(--decline) 거절, 없으면 --wait-timeout 으로
      30분 기한을 기다려 다시 배송 취소 + MFC 회수 배송이 생기는지 본다.
  라이더·시스템 잘못(--fault RIDER|SYSTEM, --resolution RESHIP|REFUND): MFC 회수 배송, 원래 기사 배달료(RIDER 면 미지급·SYSTEM 이면 그대로).
  --run-recovery: 생긴 회수 배송을 새 시험 라이더로 끝까지(제안 수락 → 사진 → 회수 → MFC 반납).

실행: uv run --no-project --with cryptography python -I deploy/lan/checks/misdelivery_e2e.py <완료된 deliveryId>
        [--fault CUSTOMER|RIDER|SYSTEM] [--resolution REDELIVER|REFUND|RESHIP] [--home near|far]
        [--customer-token-file 파일] [--decline] [--wait-timeout] [--run-recovery]
필요: rider_e2e.py 와 같다(라이더 서명키·claude-dev 비밀번호) + deploy/lan/.secrets/kakao-rest-api-key(--home 주소 고르기 — 카카오 좌표→주소).
  실제 집 주소는 잘못 둔 곳(원래 배송 도착 좌표) 그대로(near) 또는 약 1km 북쪽(far)의 도로명주소를 카카오에서 골라 쓴다(출력하지 않음).
  배차·수행 DB(PC 5 postgres)는 docker 로 읽기만 한다. 고객 토큰은 PC 5 가 만들 수 없어 선택(고객 앱에서 꺼낸 토큰 파일).
2026-11-16 처음 작성(PC 5 — #49~#53 LAN 배포 뒤). 같은 날 LAN 실행: 고객 잘못·near(직선 3m) — 원래 기사 0원 제안·수락 → 잘못 둔 곳
  사진·받음 → 전달지 = 실제 집 → 완료 → 배차 CLOSED·수행료 0원, MFC 회수 없음(DLV-74422b51 → 다시 배송 DLV-b7a28db2).
"""
import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import rider_e2e as r  # noqa: E402 — 라이더 토큰·HTTP·판정·DB 읽기
from rider_e2e import check, http, need, pg, rider_token, summary  # noqa: E402
from staff_auth import staff_token  # noqa: E402

DISPATCH, EXECUTION = "delivery_dispatch_service", "delivery_execution_service"
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


def arg(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def wait(what, fn, seconds=60, every=2):
    until = time.time() + seconds
    while time.time() < until:
        v = fn()
        if v:
            return v
        time.sleep(every)
    return None


def road_address(lat, lng):
    """카카오 좌표→주소. 그 점에 도로명이 없으면 조금씩 옮겨 본다. (도로명, 상세 없음)."""
    key = r.secret("kakao-rest-api-key")
    for dlat in (0, 0.0003, -0.0003, 0.0006):
        q = urllib.parse.urlencode({"x": lng, "y": lat + dlat})
        req = urllib.request.Request("https://dapi.kakao.com/v2/local/geo/coord2address.json?" + q,
                                     headers={"Authorization": "KakaoAK " + key})
        with urllib.request.urlopen(req, timeout=10) as res:
            docs = json.loads(res.read().decode()).get("documents") or []
        road = (docs[0].get("road_address") or {}).get("address_name") if docs else None
        if road:
            return road
    return None


def photo(delivery, token):
    boundary = "----claude" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"spot.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + PNG + f"\r\n--{boundary}--\r\n".encode()
    s, ph = http("POST", f"/deliveries/{delivery}/evidence-photos", token, raw=body,
                 content_type="multipart/form-data; boundary=" + boundary)
    need("증빙 사진 올리기", s in (200, 201) and ph.get("photoRef"), f"HTTP {s} {ph.get('code', '')}")
    return ph["photoRef"]


def on_duty(rider, token, signup=False):
    if signup:
        s, b = http("POST", "/riders/signups", token, {
            "phone": r.phone(), "name": "시험라이더", "vehicleType": "MOTORCYCLE",
            "deliveryAreas": [{"mfcId": r.MFC}], "consents": [{"consentType": "TERMS", "version": "2026-11"}]})
        need("시험 라이더 가입", s == 201, f"HTTP {s} {b.get('code', '')}")
    s, me = http("GET", "/riders/me/state", token)
    if me.get("status") != "ON_DUTY" or not me.get("newDispatchEnabled"):
        s, b = http("PUT", "/riders/me/state", token, {"status": "ON_DUTY", "newDispatchEnabled": True, "mfcId": r.MFC},
                    version=me.get("version", 0))
        need(f"운행 시작·신규배차 ON({rider[:16]}…)", s == 200, f"HTTP {s} {b.get('code', '')}")


def locate(token, lat, lng):
    s, _ = http("POST", "/riders/me/locations", token, {"deviceId": "claude-e2e", "points": [
        {"lat": lat, "lng": lng, "accuracyM": 10, "recordedAt": now_iso()}]})
    return s


def take_offer(delivery, token, near, expect_fee=None):
    """제안이 올 때까지 위치를 올리며 기다린다(5분) → 수락."""
    offer = None
    until = time.time() + 300
    while time.time() < until and offer is None:
        locate(token, *near)
        s, b = http("GET", "/offers", token)
        offer = next((o for o in (b.get("items") or []) if o.get("deliveryId") == delivery), None) if s == 200 else None
        if offer is None:
            time.sleep(15)
    need("제안 받기", offer is not None,
         "" if offer else "5분 안에 없음 — 배차 " + pg(DISPATCH, f"SELECT state FROM delivery WHERE delivery_id = '{delivery}'"))
    fee = (offer.get("summary") or {}).get("estimatedFee")
    if expect_fee is not None:
        check(f"제안 배달료 {expect_fee}원", fee == expect_fee, f"제안 {fee}")
    s, b = http("POST", f"/offers/{offer['offerId']}/responses", token, {"response": "ACCEPT"})
    need("제안 수락 → 배정", s == 200 and b.get("outcome", "ASSIGNED") in ("ACCEPTED", "ASSIGNED"),
         f"HTTP {s} {b.get('outcome')}")
    v = wait("수행 배정", lambda: (lambda st, x: x if st == 200 and x.get("state") == "ASSIGNED" else None)(
        *http("GET", f"/deliveries/{delivery}", token)), 30)
    need("수행 배정 반영(execution)", v is not None)
    return v


def redeliver(delivery, rider, token, spot, home):
    """다시 배송: 잘못 둔 곳에서 사진·받음 → 전달지 = 실제 집 → 고객 도착 → 사진 → 완료."""
    v = take_offer(delivery, token, spot, expect_fee=0)
    s, b = http("POST", f"/deliveries/{delivery}/store-arrivals", token, {}, version=v.get("version"))
    check("가게 도착은 막힘(409)", s == 409, f"HTTP {s} {b.get('code', '')}")
    ref = photo(delivery, token)
    s, v = http("GET", f"/deliveries/{delivery}", token)
    s, b = http("POST", f"/deliveries/{delivery}/returns/pickups", token, {"items": [], "evidenceRefs": [ref]},
                version=v.get("version"))
    need("잘못 둔 곳에서 받음 → PICKED_UP", s in (200, 202), f"HTTP {s} {b.get('code', '')} {str(b.get('detail', ''))[:80]}")
    s, v = http("GET", f"/deliveries/{delivery}", token)
    check("수행 상태 PICKED_UP", v.get("state") == "PICKED_UP", f"상태 {v.get('state')}")
    s, d = http("GET", f"/deliveries/{delivery}/drop-info", token)
    check("전달지 = 상담원이 기록한 실제 집(값은 출력 안 함)", s == 200 and d.get("roadAddress") == home and bool(d.get("receiverPhone")),
          f"HTTP {s} 주소 일치 {d.get('roadAddress') == home}")
    s, b = http("POST", f"/deliveries/{delivery}/customer-arrivals", token, {}, version=v.get("version"))
    check("고객 도착", s in (200, 202), f"HTTP {s} {b.get('code', '')}")
    ref = photo(delivery, token)
    s, v = http("GET", f"/deliveries/{delivery}", token)
    s, b = http("POST", f"/deliveries/{delivery}/completions", token, {
        "method": "CONTACTLESS", "evidence": {"photoRefs": [ref], "takenAt": now_iso(), "placement": "FRONT_DOOR"}},
        version=v.get("version"))
    need("전달 완료", s in (200, 202), f"HTTP {s} {b.get('code', '')} {str(b.get('detail', ''))[:80]}")
    closed = wait("배차 CLOSED", lambda: pg(DISPATCH, f"SELECT state FROM delivery WHERE delivery_id = '{delivery}'") == "CLOSED", 30)
    check("배차 CLOSED", bool(closed))
    s, e = http("GET", "/riders/me/earnings", token)
    mine = [(i.get("settlementState"), i.get("total")) for i in (e.get("items") or []) if i.get("deliveryId") == delivery]
    check("다시 배송 수행료 0원(추가 운행료 없음)", any(t == 0 for _, t in mine), f"{mine}")


def recover(recovery, mfc_spot):
    """회수 배송을 새 시험 라이더로: 제안 수락 → 사진 → 회수 → MFC 반납."""
    rider = "RD-claude-" + uuid.uuid4().hex[:12]
    token = rider_token(rider)
    on_duty(rider, token, signup=True)
    pickup = pg(DISPATCH, f"SELECT pickup_lat || ',' || pickup_lng FROM delivery WHERE delivery_id = '{recovery}'")
    spot = tuple(float(x) for x in pickup.split(",")) if pickup and "," in pickup else mfc_spot
    v = take_offer(recovery, token, spot)
    ref = photo(recovery, token)
    s, v = http("GET", f"/deliveries/{recovery}", token)
    s, b = http("POST", f"/deliveries/{recovery}/returns/pickups", token, {"items": [], "evidenceRefs": [ref]},
                version=v.get("version"))
    need("회수(RETURN_PICKED_UP)", s in (200, 202), f"HTTP {s} {b.get('code', '')}")
    locate(token, *mfc_spot)
    s, v = http("GET", f"/deliveries/{recovery}", token)
    s, b = http("POST", f"/deliveries/{recovery}/returns/store-deliveries", token, {"evidenceRefs": [ref]},
                version=v.get("version"))
    need("MFC 반납", s in (200, 202), f"HTTP {s} {b.get('code', '')}")
    closed = wait("회수 배차 CLOSED", lambda: pg(DISPATCH, f"SELECT state FROM delivery WHERE delivery_id = '{recovery}'") == "CLOSED", 30)
    check("회수 배송 CLOSED", bool(closed))
    s, me = http("GET", "/riders/me/state", token)
    http("PUT", "/riders/me/state", token, {"status": "OFF_DUTY", "newDispatchEnabled": False}, version=me.get("version"))


def main():
    if len(sys.argv) < 2 or sys.argv[1].startswith("--"):
        print(__doc__)
        sys.exit(2)
    origin = sys.argv[1]
    fault = arg("--fault", "CUSTOMER")
    resolution = arg("--resolution", "REDELIVER" if fault == "CUSTOMER" else "RESHIP")
    row = pg(DISPATCH, "SELECT state || '|' || order_id || '|' || coalesce(rider_id, '') || '|' || drop_lat || '|' || drop_lng"
                       f" || '|' || mfc_lat || '|' || mfc_lng FROM delivery WHERE delivery_id = '{origin}'")
    need("원래 배송 배차 기록", bool(row), origin)
    state, order, orig_rider, dlat, dlng, mlat, mlng = row.split("|")
    need("원래 배송 전달 완료(배차 CLOSED)", state == "CLOSED", f"상태 {state}")
    spot, mfc_spot = (float(dlat), float(dlng)), (float(mlat), float(mlng))
    admin, _ = staff_token("claude-dev")
    print(f"원래 배송 {origin}, 원래 기사 {orig_rider}, 귀책 {fault}·{resolution}")

    decision = {"fault": fault, "resolution": resolution}
    home = None
    if fault == "CUSTOMER" and resolution == "REDELIVER":
        far = arg("--home", "near") == "far"
        home = road_address(spot[0] + (0.009 if far else 0), spot[1])
        need("실제 집 주소 고르기(카카오 좌표→주소)", bool(home), "far(약 1km)" if far else "near(잘못 둔 곳)")
        s, b = http("POST", f"/orders/lookups/{order}/redelivery-destinations", admin,
                    {"deliveryId": origin, "roadAddress": home, "reason": "LAN 시험 — 고객 주소 오입력 통화 확인"})
        need("상담원 실제 주소 기록(order)", s == 201 and b.get("destinationId") and not b.get("roadAddress"),
             f"HTTP {s} {b.get('code', '')} 응답에 주소 {bool(b.get('roadAddress'))}")
        decision["redeliveryDestinationId"] = b["destinationId"]

    s, c = http("POST", "/cases", admin, {
        "kind": "MISDELIVERY", "subjectType": "DELIVERY", "subjectRef": origin, "centerId": r.MFC,
        "summary": "LAN 시험 — 오배송 확인(misdelivery_e2e)", "misdelivery": decision})
    need("오배송 사건 열기(case)", s == 201 and c.get("caseId"), f"HTTP {s} {c.get('code', '')} {str(c.get('detail', ''))[:80]}")
    case = c["caseId"]

    if fault == "CUSTOMER" and resolution == "REDELIVER":
        def judged():
            out = pg(DISPATCH, "SELECT delivery_id || '|' || straight_distance_m || '|' || payment_required FROM delivery"
                               f" WHERE case_id = '{case}' AND purpose = 'MISDELIVERY_REDELIVERY' AND straight_distance_m IS NOT NULL")
            return out.split("|") if out else None

        rd = wait("다시 배송", judged, 120, 3)
        need("다시 배송 생김·200m 판정", rd is not None, "" if rd else "2분 안에 좌표 준비 안 됨")
        redelivery, meters, pay = rd[0], int(rd[1]), rd[2] == "t"
        check("MFC 회수 배송 없음", pg(DISPATCH, f"SELECT count(*) FROM delivery WHERE case_id = '{case}' AND type = 'RETURN'") == "0")
        print(f"     다시 배송 {redelivery}, 잘못 둔 곳 ↔ 실제 집 직선 {meters}m → {'결제 대기' if pay else '원래 기사'}")
        check("200m 판정과 결제 필요가 맞음", pay == (meters >= 200), f"{meters}m 결제 {pay}")
        if not pay:
            token = rider_token(orig_rider)
            on_duty(orig_rider, token)
            redeliver(redelivery, orig_rider, token, spot, home)
            s, me = http("GET", "/riders/me/state", token)
            http("PUT", "/riders/me/state", token, {"status": "OFF_DUTY", "newDispatchEnabled": False}, version=me.get("version"))
        else:
            time.sleep(20)
            check("결제 전 제안 없음", pg(DISPATCH, f"SELECT count(*) FROM dispatch_offer WHERE delivery_id = '{redelivery}'") == "0")
            token_file = arg("--customer-token-file")
            if token_file:
                with open(token_file, encoding="utf-8") as f:
                    customer = f.read().strip()
                s, b = http("GET", f"/redelivery-payments?orderId={order}", customer)
                item = next(iter(b.get("items") or []), {})
                check("고객 결제 대기 목록", s == 200 and item.get("status") == "WAITING_PAYMENT" and item.get("amount") == 9000,
                      f"HTTP {s} {item.get('status')} {item.get('amount')} 결제 시도 {bool(item.get('paymentAttemptId'))}")
                if "--decline" in sys.argv and item:
                    s, b = http("POST", f"/redelivery-payments/{item['redeliveryPaymentId']}/declines", customer, {})
                    check("고객이 회수 후 환불 고름", s == 200 and b.get("status") == "DECLINED", f"HTTP {s} {b.get('status')}")
            if "--decline" in sys.argv or "--wait-timeout" in sys.argv:
                limit = 120 if "--decline" in sys.argv else 35 * 60
                if limit > 120:
                    print("     결제 기한(30분)을 기다립니다…", flush=True)
                gone = wait("다시 배송 취소", lambda: pg(DISPATCH, f"SELECT state FROM delivery WHERE delivery_id = '{redelivery}'")
                            == "CANCELLED_BEFORE_PICKUP", limit, 10)
                check("결제 안 됨 → 다시 배송 취소", bool(gone))
                rec = pg(DISPATCH, f"SELECT delivery_id FROM delivery WHERE case_id = '{case}' AND type = 'RETURN'")
                check("결제 안 됨 → MFC 회수 배송", bool(rec), rec)
                if rec and "--run-recovery" in sys.argv:
                    recover(rec, mfc_spot)
    else:
        rec = wait("회수 배송", lambda: pg(DISPATCH, f"SELECT delivery_id FROM delivery WHERE case_id = '{case}' AND type = 'RETURN'"), 60)
        check("MFC 회수 배송", bool(rec), rec or "")
        if orig_rider:
            token = rider_token(orig_rider)
            time.sleep(5)
            s, e = http("GET", "/riders/me/earnings", token)
            mine = [i for i in (e.get("items") or []) if i.get("deliveryId") == origin]
            got = [(i.get("settlementState"), i.get("calculable"), i.get("total")) for i in mine]
            withheld = any(i.get("settlementState") == "ADJUSTED" or i.get("calculable") is False for i in mine)
            if fault == "RIDER":
                check("라이더 잘못 → 원래 배달료 미지급", withheld or not mine, f"{got}")
            else:
                check("시스템 잘못 → 원래 배달료 그대로", not withheld, f"{got}")
        if rec and "--run-recovery" in sys.argv:
            recover(rec, mfc_spot)
        if resolution == "RESHIP":
            print("     재발송은 MFC 점원이 피킹·포장하면 배송(purpose MISDELIVERY_RESHIPMENT)이 생긴다 — 이 각본은 거기까지 안 간다")

    s, cs = http("GET", f"/cases/{case}", admin)
    check("사건 조회", s == 200, f"상태 {cs.get('state')} refs {[x.get('refType') for x in cs.get('refs') or []]}")
    summary()


if __name__ == "__main__":
    main()
