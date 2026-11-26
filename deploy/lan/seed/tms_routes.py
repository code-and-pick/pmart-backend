"""칠곡 DC 고정 노선과 노선 × 온도대 정규차(transport-planning 0.2.0 — TMS 1단계, 설계 docs/cdc/design/2026-11-26-tms-first-stage.md §2).

지금 공급 MFC 5곳(CDC-6)이 든 정본 4.3 노선만, 정차는 등록된 MFC 만:
  RT-04 부산진구 → 부산남구 · RT-07 세종 · RT-09 춘천 · RT-10 목포
출차 시각·구간 분은 [가정](정본 3.3 — 첫 MFC 도착 05:00~06:00, 마지막 도착 ≤ 07:30). 실측 행렬은 routing 단계 때 바꾼다.
정규차·기사는 fleet 시드(tms_fleet.py)의 ID — 그 스크립트를 먼저 돌린다.

  python -I deploy/lan/seed/tms_routes.py            # 계획만(노선별 계획 도착)
  python -I deploy/lan/seed/tms_routes.py --apply    # 게이트웨이로 넣기(운영자 claude-cdc-sup — CDC_SUPERVISOR)

- 멱등 키가 정해져 있어(`tms-route-<노선>`, `tms-route-<노선>-<온도대>`) 다시 돌려도 안전하다(이미 있는 노선은 409 ALREADY_EXISTS 를 "있음"으로 센다).
"""
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
CENTER = "CDC01"
# (노선, 이름, 출차 HH:mm, [(MFC, 구간 분)], 복귀 분) — 모두 [가정]
ROUTES = [
    ("RT-04", "부산(부산진구·부산남구)", "03:00", [("MFC-BJG-01", 120), ("MFC-BNG-01", 20)], 130),
    ("RT-07", "세종", "03:20", [("MFC-SEJ-01", 130)], 130),
    ("RT-09", "춘천", "01:50", [("MFC-CHC-01", 210)], 210),
    ("RT-10", "목포", "01:40", [("MFC-MOKPO-01", 220)], 220),
]
TEMPS = [("AMBIENT", "A"), ("CHILLED", "C"), ("FROZEN", "F")]


def arrivals(dep, legs):
    t = datetime.strptime(dep, "%H:%M")
    out = []
    for mfc, leg in legs:
        t = t + timedelta(minutes=leg)
        out.append((mfc, t.strftime("%H:%M")))
        t = t + timedelta(minutes=30)  # 하차 30분
    return out


def http(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    h = {"Content-Type": "application/json", **(headers or {})}
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main():
    for s in (sys.stdout, sys.stderr):
        s.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글 출력
    for rid, name, dep, legs, ret in ROUTES:
        arr = ", ".join(f"{m} {t}" for m, t in arrivals(dep, legs))
        print(f"{rid} {name} 출차 {dep} → {arr} (복귀 +{ret}분)")
    if "--apply" not in sys.argv:
        print("계획만 — 넣으려면 --apply(transport-planning·fleet LAN 배치·tms_fleet.py 뒤)")
        return
    sys.path.insert(0, os.path.join(HERE, "..", "checks"))
    import staff_auth  # noqa: E402
    token, _ = staff_auth.staff_token("claude-cdc-sup")
    hdr = {"Authorization": "Bearer " + token, "X-Source-System": "pmart-seed"}
    for rid, name, dep, legs, ret in ROUTES:
        body = {"routeId": rid, "name": name, "departureTime": dep, "returnMinutes": ret,
                "stops": [{"mfcId": m, "legMinutes": l} for m, l in legs]}
        st, res = http("POST", f"{staff_auth.GW}/cdcs/{CENTER}/routes", body,
                       {**hdr, "Idempotency-Key": f"tms-route-{rid}"})
        if st not in (201,) and not (st == 409 and res.get("code") == "ALREADY_EXISTS"):
            sys.exit(f"노선 {rid} 실패 HTTP {st} {res}")
        n = rid.split("-")[1]
        for temp, t in TEMPS:
            st, res = http("POST", f"{staff_auth.GW}/cdcs/{CENTER}/routes/{rid}/regular-vehicles",
                           {"tempClass": temp, "vehicleId": f"VH-5T-{n}{t}", "driverId": f"DR-{n}{t}"},
                           {**hdr, "Idempotency-Key": f"tms-route-{rid}-{temp}"})
            if st != 200:
                sys.exit(f"정규차 {rid} {temp} 실패 HTTP {st} {res}")
        print(f"  {rid} 노선·정규차 3대")
    print("끝")


if __name__ == "__main__":
    main()
