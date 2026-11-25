"""칠곡 CDC LAN 확인 — 피킹 선반 먼저 할당·Wave 릴리스(cdc-outbound → cdc-location)·정리(미출발 취소). cdc 세션 2026-11-25.

확인하는 것:
  1. 할당이 같은 LOT 이면 피킹 선반(통로 61~)을 먼저 잡는지(cdc-inventory 0.5.0 — cdc-location 위치 조회).
  2. Wave 릴리스가 202 인지(cdc-outbound 0.9.0 부터 릴리스 때 cdc-location 위치 조회 — 닿지 않으면 503).
  3. 뒷정리: 미출발 취소 FULL(TRANSFER_CANCELLED) → TRF CANCELLED·할당 해제·작업 CANCELLED·Wave CANCELLED.
LAN 에 남는 것: 취소된 시험 TRF 하나(`TRF-LANCHK-…`)와 그 Wave·작업 기록(모두 CANCELLED), 재고 원장의 할당·해제 기록. 현물은 바뀌지 않는다.

  python -I deploy/lan/checks/cdc_outbound_e2e.py            # 계획만 — 고를 SKU·LOT·칸을 보인다(읽기만)
  python -I deploy/lan/checks/cdc_outbound_e2e.py --apply    # 위 1~3 실행(운영자 claude-cdc-sup)

주소: PMART_CDC_INVENTORY(기본 http://192.168.0.222:8601/api/v1), PMART_CDC_OUTBOUND(기본 http://192.168.0.222:8603/api/v1),
게이트웨이는 staff_auth.GW. 내부 경로는 토큰 없이 X-Source-System 만(ADR-0026). 토큰·비밀번호는 출력하지 않는다.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
INV = os.environ.get("PMART_CDC_INVENTORY", "http://192.168.0.222:8601/api/v1")
OUT = os.environ.get("PMART_CDC_OUTBOUND", "http://192.168.0.222:8603/api/v1")
CENTER = "CDC01"
MFC = "MFC-BJG-01"
SRC = {"X-Source-System": "claude-lan-check"}


def http(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    h = {"Content-Type": "application/json", **SRC, **(headers or {})}
    if method == "POST":
        h.setdefault("Idempotency-Key", str(uuid.uuid4()))
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else {}
        except ValueError:
            return e.code, {"raw": raw[:200].decode("utf-8", "replace")}


def aisle(code):
    p = code.split("-")
    return int(p[3]) if len(p) == 7 and p[3].isdigit() else -1


def pick_candidate():
    """같은 LOT 이 피킹 선반(61~89)과 팔레트 랙(01~60)에 모두 있고 선반에 예약 안 된 현물이 있는 SKU 하나."""
    st, snap = http("GET", f"{INV}/internal/snapshots?cdcId={CENTER}")
    if st != 200:
        sys.exit(f"스냅샷 실패 HTTP {st} {snap}")
    for item in snap.get("items", []):
        if not item.get("handlingActive") or item.get("available") in (None, 0):
            continue
        for lot in item.get("lots", []):
            locs = lot.get("locations", [])
            shelf = [l for l in locs if 61 <= aisle(l["locationCode"]) <= 89 and l.get("onHand", 0) - l.get("reserved", 0) > 0]
            rack = [l for l in locs if 1 <= aisle(l["locationCode"]) <= 60 and l.get("onHand", 0) - l.get("reserved", 0) > 0]
            if shelf and rack:
                return item["sku"], lot["lot"], shelf[0]["locationCode"], rack[0]["locationCode"]
    sys.exit("같은 LOT 이 선반·랙 모두에 있는 SKU 를 찾지 못했다 — 시작 재고 시드를 확인한다")


def check(ok, msg):
    print(("  OK   " if ok else "  FAIL ") + msg)
    return ok


def main():
    for s in (sys.stdout, sys.stderr):
        s.reconfigure(encoding="utf-8")
    sku, lot, shelf, rack = pick_candidate()
    print(f"고른 SKU {sku} LOT {lot} — 선반 {shelf} / 랙 {rack}")
    if "--apply" not in sys.argv:
        print("계획만 — 실행하려면 --apply (LAN 에 취소된 시험 TRF 하나가 남는다)")
        return
    sys.path.insert(0, HERE)
    import staff_auth  # noqa: E402
    token, _ = staff_auth.staff_token("claude-cdc-sup")
    auth = {"Authorization": "Bearer " + token}
    gw = staff_auth.GW
    trf = "TRF-LANCHK-" + time.strftime("%Y%m%d%H%M%S")
    due = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 86400))
    all_ok = True

    # 1) 인수 → 할당(피킹 선반 먼저)
    body = {"trfId": trf, "trfVersion": 1, "cdcId": CENTER, "mfcId": MFC, "approvalRef": "LANCHK",
            "deadlineMeaning": "ARRIVAL", "dueAt": due, "lines": [{"sku": sku, "approvedQty": 1}]}
    st, r = http("POST", f"{OUT}/internal/transfer-intakes", body)
    all_ok &= check(st == 202, f"TRF 인수 {trf} → HTTP {st}")
    st, intake = http("GET", f"{OUT}/internal/transfer-intakes/{trf}")
    alc = intake.get("allocationId")
    all_ok &= check(intake.get("state") == "ALLOCATED", f"할당 상태 {intake.get('state')} {intake.get('allocationError') or ''}")
    if alc:
        st, a = http("GET", f"{INV}/internal/allocations/{alc}")
        lots = (a.get("lines") or [{}])[0].get("lots", [])
        first = lots[0]["locationCode"] if lots else None
        all_ok &= check(first is not None and 61 <= aisle(first) <= 89, f"할당 칸 {first} — 피킹 선반(통로 61~) 먼저")

    # 2) Wave 생성·릴리스(cdc-outbound → cdc-location)
    wave = None
    if intake.get("state") == "ALLOCATED":
        st, w = http("POST", f"{gw}/waves", {"cdcId": CENTER, "trfIds": [trf]}, auth)
        wave = w.get("waveId")
        all_ok &= check(st == 201, f"Wave 생성 HTTP {st} {wave or w.get('code', '')}")
        if wave:
            st, rel = http("POST", f"{gw}/waves/{wave}/releases", None, auth)
            all_ok &= check(st == 202, f"Wave 릴리스 HTTP {st} {rel.get('code', '')} — 503 이면 cdc-outbound → cdc-location 주소 확인(pmart.outbound.cdc-location-base-url)")
            st, tasks = http("GET", f"{gw}/cdcs/{CENTER}/tasks?waveId={wave}", None, auth)
            items = tasks.get("items", [])
            modes = sorted({t.get("pickMode") for t in items})
            all_ok &= check(st == 200 and len(items) >= 1, f"작업 {len(items)}개 pickMode {modes}")

    # 3) 뒷정리 — 미출발 취소 FULL
    st, c = http("POST", f"{gw}/cdcs/{CENTER}/transfers/{trf}/pre-departure-cancellations",
                 {"reason": "TRANSFER_CANCELLED", "scope": "FULL", "sourceRef": "LANCHK"}, auth)
    all_ok &= check(st == 202 and c.get("status") == "APPLIED", f"정리(미출발 취소) HTTP {st} {c.get('status') or c.get('code', '')}")
    st, after = http("GET", f"{OUT}/internal/transfer-intakes/{trf}")
    all_ok &= check(after.get("state") == "CANCELLED", f"TRF 최종 상태 {after.get('state')}")
    if alc:
        st, a = http("GET", f"{INV}/internal/allocations/{alc}")
        all_ok &= check(a.get("state") == "RELEASED", f"할당 최종 상태 {a.get('state')}")
    print("끝 — 모두 통과" if all_ok else "끝 — 실패가 있다(위 FAIL)")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
