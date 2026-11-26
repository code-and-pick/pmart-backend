"""칠곡 DC 간선 운송사·정규차·기사 시드(fleet-service 0.2.0 — TMS 1단계, 설계 docs/cdc/design/2026-11-26-tms-first-stage.md §2).

지금 공급 MFC 5곳이 든 고정 노선 4개(RT-04 부산·RT-07 세종·RT-09 춘천·RT-10 목포, TMS 정본 4.3) × 온도대 3(상온·냉장·냉동)마다
정규차 한 대·기사 한 명 = 차량 12대·기사 12명. 운송사 하나. 모두 [가정](정본 4.4 5톤 기본값 20 R/T·5,000kg) — 실제 값이 생기면 여기만 고친다.
노선 × 온도대 → 차량·기사 연결은 planning 시드(tms_routes.py)가 한다. 이 스크립트를 먼저 돌린다.

  python -I deploy/lan/seed/tms_fleet.py            # 계획만
  python -I deploy/lan/seed/tms_fleet.py --apply    # 게이트웨이로 넣기(운영자 claude-cdc-sup — CDC_SUPERVISOR)

- 항목마다 멱등 키가 정해져 있어(`tms-fleet-<ID>`) 다시 돌려도 안전하다(이미 있으면 409 ALREADY_EXISTS 를 "있음"으로 센다).
- 기사는 표시 이름만 넣는다(실명·연락처 없음).
"""
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CARRIER = {"carrierId": "CR-01", "name": "칠곡 간선 운송(가정)"}
ROUTES = ["RT-04", "RT-07", "RT-09", "RT-10"]
TEMPS = [("AMBIENT", "A"), ("CHILLED", "C"), ("FROZEN", "F")]


def plan():
    vehicles, drivers = [], []
    for r in ROUTES:
        n = r.split("-")[1]
        for temp, t in TEMPS:
            vehicles.append({
                "vehicleId": f"VH-5T-{n}{t}",
                "carrierId": CARRIER["carrierId"],
                "plateNo": None,
                "contractType": "REGULAR",
                "tempClass": temp,
                "tonnage": 5,
                "maxRt": 20,
                "maxWeightKg": 5000,
            })
            drivers.append({
                "driverId": f"DR-{n}{t}",
                "carrierId": CARRIER["carrierId"],
                "displayName": f"기사 {n}{t}",
            })
    return vehicles, drivers


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
    vehicles, drivers = plan()
    print(f"운송사 1({CARRIER['carrierId']}) · 차량 {len(vehicles)} · 기사 {len(drivers)}")
    for v in vehicles:
        print(f"  {v['vehicleId']} {v['tempClass']} {v['maxRt']} R/T {v['maxWeightKg']}kg")
    if "--apply" not in sys.argv:
        print("계획만 — 넣으려면 --apply(fleet-service LAN 배치·게이트웨이 라우트 뒤)")
        return
    sys.path.insert(0, os.path.join(HERE, "..", "checks"))
    import staff_auth  # noqa: E402
    token, _ = staff_auth.staff_token("claude-cdc-sup")
    made = had = 0
    for path, body, ident in (
        [("/carriers", CARRIER, CARRIER["carrierId"])]
        + [("/vehicles", {k: v for k, v in x.items() if v is not None}, x["vehicleId"]) for x in vehicles]
        + [("/drivers", x, x["driverId"]) for x in drivers]
    ):
        st, res = http("POST", f"{staff_auth.GW}{path}", body,
                       {"Authorization": "Bearer " + token, "X-Source-System": "pmart-seed",
                        "Idempotency-Key": f"tms-fleet-{ident}"})
        if st == 201:
            made += 1
        elif st == 409 and res.get("code") == "ALREADY_EXISTS":
            had += 1
        else:
            sys.exit(f"{path} {ident} 실패 HTTP {st} {res}")
    print(f"끝 — 새로 {made}, 이미 있음 {had}")


if __name__ == "__main__":
    main()
