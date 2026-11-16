"""LAN 라이더 배송 처음부터 끝까지 확인(실제 API — 게이트웨이 + 라이더 시험 토큰). 토큰·개인정보(주소·전화)는 출력하지 않는다.

시험 라이더 가입 → 운행 시작·신규배차 ON → 위치 올리기(MFC 근처) → (배차 실패로 멈춘 배송이면 운영자가 다시 배차) → AI 추천 제안 받기·수락
→ 가게 도착 → 포장 QR 인계(허가·확정 → PackageHandedOver) → 픽업 완료(PICKED_UP) → 전달지 조회(BR-24) → [선택] 위치 끊김 3분 →
RiderLocationStale → 고객 도착 → 증빙 사진 → 전달 완료(DeliveryCompleted) → 수행료 → 운행 종료.

실행: uv run --no-project --with cryptography python -I deploy/lan/checks/rider_e2e.py <deliveryId> [--stale] [--rider RD-…]
필요:
- deploy/lan/.secrets/rider-auth-signing-key.pem·rider-auth-signing-kid — LAN rider-auth-service 서명키(PC 1 init-secrets.sh 가 만듦, git 제외).
  라이더 앱·Google 로그인 없이 시험 라이더 토큰(typ=RIDER, 주체 RD-claude-…, 15분)을 만든다(kickoff §6 시험 라이더 토큰 결정 — LAN 전용, 운영 금지).
- deploy/lan/.secrets/keycloak-claude-dev-password — 멈춘 배송 다시 배차(OPERATOR_ADMIN, staff_auth.py).
- 배송은 점원 배차 요청이 끝난(출고 READY·포장 밀봉) 실제 주문이어야 한다. 배차 가능 시간 08~24시(Asia/Seoul).
- --rider RD-…: 이미 그 라이더에게 배정된 배송을 이어서(가입·배차·수락 건너뜀).
- --stale: 픽업 뒤 위치를 3분 30초 멈춰 RiderLocationStale 을 확인 — outbox 행은 CDC 가 읽은 뒤 남지 않으므로(늘 0건) 사건과 같은
  트랜잭션에서 올라가는 tracking `rider_signal.alert_count` 를 본다. PC 5 postgres 를 docker 로 읽기만 한다.
- 포장 ID 는 실제로는 포장 QR 에서 읽는다. 응답 packageIds 는 이미 인계된 포장뿐이라 execution `shipment_package` 를 읽기만 해서 대신한다.
2026-11-15 처음 작성, 2026-11-16 LAN 첫 실행(PC 5 — #15 배포 뒤 첫 라이더 흐름 확인).
"""
import base64
import json
import os
import random
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from staff_auth import staff_token  # noqa: E402

GW = os.environ.get("PMART_GATEWAY", "http://192.168.0.40:8080/api/v1")
SECRETS = os.path.join(HERE, "..", ".secrets")
ISSUER = os.environ.get("PMART_RIDER_ISSUER", "http://rider-auth-service:8510")
MFC = os.environ.get("PMART_MFC", "MFC-SEED-01")
MFC_LAT, MFC_LNG = float(os.environ.get("PMART_MFC_LAT", "35.158501")), float(os.environ.get("PMART_MFC_LNG", "129.062021"))  # seed-data.json 센터 좌표(2026-11-16 부산진구지점 실제 주소)
SRC = "claude-rider-e2e"
PG = ["docker", "exec", "pmart-lan-postgres-1", "psql", "-p", "15432", "-U", "pmart", "-tA"]
results = []
sys.stdout.reconfigure(encoding="utf-8")  # -I 는 PYTHONIOENCODING 을 무시한다


def secret(name):
    with open(os.path.join(SECRETS, name), encoding="utf-8") as f:
        return f.read().strip()


def b64(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def rider_token(rider_id):
    """LAN rider-auth 서명키로 시험 라이더 토큰(15분). 실제 발급과 같은 iss·aud·typ·kid."""
    key = serialization.load_pem_private_key(secret("rider-auth-signing-key.pem").encode(), password=None)
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT", "kid": secret("rider-auth-signing-kid")}
    claims = {"iss": ISSUER, "sub": rider_id, "aud": ["pmart-api"], "typ": "RIDER", "roles": [], "centers": [],
              "sid": "claude-e2e-" + uuid.uuid4().hex[:12], "iat": now, "exp": now + 900, "jti": uuid.uuid4().hex}
    signing = b64(json.dumps(header).encode()) + "." + b64(json.dumps(claims).encode())
    sig = key.sign(signing.encode(), padding.PKCS1v15(), hashes.SHA256())
    return signing + "." + b64(sig)


def http(method, path, token, body=None, version=None, key=None, raw=None, content_type=None):
    headers = {"Accept": "application/json, application/problem+json", "Authorization": "Bearer " + token}
    data = None
    if raw is not None:
        data, headers["Content-Type"] = raw, content_type
    elif body is not None:
        data, headers["Content-Type"] = json.dumps(body).encode(), "application/json"
    if method != "GET":
        headers["Idempotency-Key"] = key or str(uuid.uuid4())
        headers["X-Source-System"] = SRC
    if version is not None:
        headers["X-Expected-Version"] = str(version)
    req = urllib.request.Request(GW + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            text = r.read().decode() or "{}"
            return r.status, json.loads(text)
    except urllib.error.HTTPError as e:
        text = e.read().decode() or "{}"
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, {"raw": text[:200]}


def check(name, ok, detail=""):
    results.append((name, ok))
    print(("  OK   " if ok else "  FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)
    return ok


def need(name, ok, detail=""):
    if not check(name, ok, detail):
        summary()
        sys.exit(1)


def summary():
    bad = [n for n, ok in results if not ok]
    print(f"\n결과: {len(results) - len(bad)}/{len(results)} 통과" + (" — 실패: " + ", ".join(bad) if bad else ""))


def pg(db, sql):
    out = subprocess.run(PG + ["-d", db, "-c", sql], capture_output=True, text=True, encoding="utf-8", timeout=30)
    return out.stdout.strip()


def phone():
    return "010" + "".join(random.choice("0123456789") for _ in range(8))


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    delivery = sys.argv[1]
    stale = "--stale" in sys.argv
    resume = sys.argv[sys.argv.index("--rider") + 1] if "--rider" in sys.argv else None
    rider = resume or "RD-claude-" + uuid.uuid4().hex[:12]
    token = rider_token(rider)
    print(f"배송 {delivery}, 시험 라이더 {rider}" + (" (이어서)" if resume else ""))
    admin = staff_token("claude-dev")
    admin = admin[0] if isinstance(admin, tuple) else admin

    def locate(lat=MFC_LAT + 0.001, lng=MFC_LNG + 0.001):
        st, _ = http("POST", "/riders/me/locations", token, {"deviceId": "claude-e2e", "points": [
            {"lat": lat, "lng": lng, "accuracyM": 10, "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}]})
        return st

    if not resume:
        assign(delivery, rider, token, admin, locate)
    else:
        need("위치 올리기(MFC 약 150m)", locate() in (200, 202), "")

    # 4. 가게 도착 — 배정은 사건(DeliveryAssigned)으로 execution 에 가므로 보일 때까지 기다린다
    v, until = {}, time.time() + 30
    while time.time() < until:
        s, v = http("GET", f"/deliveries/{delivery}", token)
        if s == 200 and v.get("riderId") == rider:
            break
        time.sleep(1)
    need("수행 배정 반영(execution)", s == 200 and v.get("riderId") == rider, f"HTTP {s} {v.get('code', '')} 상태 {v.get('state')}")
    if v.get("state") == "ASSIGNED":
        s, b = http("POST", f"/deliveries/{delivery}/store-arrivals", token, {}, version=v.get("version"))
        check("가게 도착", s in (200, 202), f"HTTP {s} {b.get('code', '')} {str(b.get('detail', ''))[:80]}")

    rest(delivery, rider, token, admin, locate, stale)


def assign(delivery, rider, token, admin, locate):
    # 1. 가입·운행 시작
    s, b = http("POST", "/riders/signups", token, {
        "phone": phone(), "name": "시험라이더", "vehicleType": "MOTORCYCLE",
        "deliveryAreas": [{"mfcId": MFC}], "consents": [{"consentType": "TERMS", "version": "2026-11"}]})
    need("라이더 가입", s == 201, f"HTTP {s} {b.get('code', '')} {b.get('detail', '')}")
    s, b = http("PUT", "/riders/me/state", token, {"status": "ON_DUTY", "newDispatchEnabled": True, "mfcId": MFC},
                version=b.get("version", 0))
    need("운행 시작·신규배차 ON", s == 200 and b.get("newDispatchEnabled") is True, f"HTTP {s} {b.get('code', '')}")

    need("위치 올리기(MFC 약 150m)", locate() in (200, 202), "")

    # 2. 멈춘 배송이면 운영자가 다시 배차
    s, d = http("GET", f"/deliveries/{delivery}/dispatch", admin)
    need("배차 조회(운영자)", s == 200, f"HTTP {s} {d.get('code', '')}")
    print(f"     배차 상태 {d.get('state')}")
    if d.get("state") == "DISPATCH_FAILED":
        s, b = http("POST", f"/deliveries/{delivery}/assignment-cancellations", admin,
                    {"requestedBy": "STORE_STAFF", "reason": "STORE_REQUEST", "reasonDetail": "LAN 라이더 시험 — 주소 버그 고친 뒤 다시 배차",
                     "reassign": True})
        need("배차 실패 배송 다시 배차", s in (200, 202), f"HTTP {s} {b.get('code', '')} {b.get('detail', '')[:80]}")

    # 3. 제안 기다리기(위치는 20초마다)
    offer = None
    until = time.time() + 300
    while time.time() < until and offer is None:
        locate()
        s, b = http("GET", "/offers", token)
        for o in (b.get("items") or []) if s == 200 else []:
            if o.get("deliveryId") == delivery:
                offer = o
        if offer is None:
            time.sleep(20)
    if offer is None:
        s, d = http("GET", f"/deliveries/{delivery}/dispatch", admin)
        need("AI 추천 제안 받기", False, f"5분 안에 없음 — 배차 상태 {d.get('state')}")
    check("AI 추천 제안 받기", True, f"예상 배달료 {offer.get('fee', offer.get('estimatedFee'))}")
    s, b = http("POST", f"/offers/{offer['offerId']}/responses", token, {"response": "ACCEPT"})
    need("제안 수락 → 배정", s == 200 and b.get("outcome", "ACCEPTED") in ("ACCEPTED", "ASSIGNED"),
         f"HTTP {s} outcome {b.get('outcome')} {b.get('lateReason', '')}")


def rest(delivery, rider, token, admin, locate, stale):
    # 5. 포장 QR 인계(MFC) — QR = PKG:<packageId>:<버전>
    # 응답 packageIds 는 이미 인계된 포장뿐이다. 실제로는 포장에 붙은 QR 에서 읽으므로, 시험은 execution 이 받은 출고 포장(읽기만)으로 대신한다.
    s, v = http("GET", f"/deliveries/{delivery}", token)
    shipment = pg("delivery_execution_service", f"SELECT shipment_id FROM execution_delivery WHERE delivery_id = '{delivery}'")
    raw = pg("delivery_execution_service", f"SELECT package_ids FROM shipment_package WHERE shipment_id = '{shipment}'")
    packages = [p for p in (json.loads(raw) if raw else []) if p not in (v.get("packageIds") or [])]
    need("출고 포장 목록(QR 대신)", len(packages) > 0 or v.get("state") == "PICKED_UP", f"{len(packages)}개 상태 {v.get('state')}")
    for pkg in packages:
        auth = None
        for ver in (1, 2, 3):
            s, b = http("POST", f"/packages/{pkg}/handover-authorizations", token,
                        {"riderId": rider, "deliveryId": delivery, "scannedCode": f"PKG:{pkg}:{ver}"})
            if s == 201:
                auth = b
                break
            if b.get("code") != "PACKAGE_INVALIDATED":
                break
        need("인계 허가(QR)", auth is not None, f"HTTP {s} {b.get('code', '')} {str(b.get('detail', ''))[:100]}")
        s, b = http("POST", f"/packages/{pkg}/handover-commits", token,
                    {"handoverAuthorizationId": auth["handoverAuthorizationId"], "fenceVersion": auth["fenceVersion"],
                     "riderId": rider})
        need("인계 확정", s in (201, 202) and b.get("status", "APPLIED") in ("APPLIED", "ACCEPTED", "UNKNOWN_RESULT"),
             f"HTTP {s} {b.get('status', '')} {b.get('code', '')}")

    state = None
    until = time.time() + 60
    while time.time() < until and state != "PICKED_UP":
        time.sleep(2)
        s, v = http("GET", f"/deliveries/{delivery}", token)
        state = v.get("state")
    need("픽업 완료(PackageHandedOver → PICKED_UP)", state == "PICKED_UP", f"상태 {state}")

    # 6. 전달지(BR-24) — 값은 출력하지 않고 있는지만
    s, b = http("GET", f"/deliveries/{delivery}/drop-info", token)
    check("전달지 조회", s == 200 and bool(b.get("roadAddress")) and bool(b.get("receiverPhone")),
          f"HTTP {s} 칸 {sorted(k for k, x in b.items() if x)}")

    # 7. [선택] 위치 끊김 3분
    if stale:
        print("     위치 올리기를 3분 30초 멈춥니다…", flush=True)
        time.sleep(210)
        found = pg("delivery_tracking_service", f"SELECT alert_count FROM rider_signal WHERE rider_id = '{rider}'")
        check("위치 끊김 3분 → RiderLocationStale", found not in ("", "0"), f"끊김 알림 {found or 0}번(rider_signal)")
        locate()

    # 8. 고객 도착 → 사진 → 완료
    locate(35.1529, 129.0596)
    s, v = http("GET", f"/deliveries/{delivery}", token)
    s, b = http("POST", f"/deliveries/{delivery}/customer-arrivals", token, {}, version=v.get("version"))
    check("고객 도착", s in (200, 202), f"HTTP {s} {b.get('code', '')}")
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
    boundary = "----claude" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"door.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + png + f"\r\n--{boundary}--\r\n".encode()
    s, ph = http("POST", f"/deliveries/{delivery}/evidence-photos", token, raw=body,
                 content_type="multipart/form-data; boundary=" + boundary)
    need("증빙 사진 올리기", s in (200, 201) and ph.get("photoRef"), f"HTTP {s} {ph.get('code', '')}")
    s, v = http("GET", f"/deliveries/{delivery}", token)
    s, b = http("POST", f"/deliveries/{delivery}/completions", token, {
        "method": "CONTACTLESS", "evidence": {"photoRefs": [ph["photoRef"]],
                                               "takenAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                                               "placement": "FRONT_DOOR"}}, version=v.get("version"))
    need("전달 완료", s in (200, 202), f"HTTP {s} {b.get('code', '')} {str(b.get('detail', ''))[:80]}")
    s, v = http("GET", f"/deliveries/{delivery}", token)
    check("수행 상태 COMPLETED", v.get("state") == "COMPLETED", f"상태 {v.get('state')}")
    time.sleep(5)
    s, d = http("GET", f"/deliveries/{delivery}/dispatch", admin)
    check("배차 CLOSED(완료 사건 반영)", d.get("state") == "CLOSED", f"상태 {d.get('state')}")
    s, e = http("GET", "/riders/me/earnings", token)
    items = e.get("items") or []
    check("수행료 기록", s == 200 and any(i.get("deliveryId") == delivery for i in items),
          f"{[(i.get('settlementState'), i.get('total')) for i in items if i.get('deliveryId') == delivery]}")

    # 9. 운행 종료
    s, me = http("GET", "/riders/me/state", token)
    s, b = http("PUT", "/riders/me/state", token, {"status": "OFF_DUTY", "newDispatchEnabled": False},
                version=me.get("version"))
    check("운행 종료", s == 200, f"HTTP {s} {b.get('code', '')}")
    summary()


if __name__ == "__main__":
    main()
