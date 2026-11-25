"""칠곡 DC `CDC01` 통로별 피킹 방식(cdc-location 0.5.0 `POST /cdcs/CDC01/aisle-pick-methods`) — 묶음 피킹 설계 §7 결정 3.

규칙(시뮬레이터 R-19 회신 2026-11-25 확정, 시뮬레이터 src/domain/pickMethod.ts 와 같음):
  - DPS          : 5층 A(냉동) 선반 통로 61~89
  - BOX_PICK     : 팔레트 랙 통로 01~60 중 PICK_FACE(1단 박스 피킹면)가 있는 통로 — 칠곡 5층 C·D, 7층 G(R-21 — 7층 H 랙은 오토스토어 보충 보관만)
  - DISTRIBUTION : 바닥 레인 통로 93(6층 야채)
  - LINE         : B(냉장)·F·G 선반 통로 61~89 — 6층 라인 DAS 와 상온 DAS 존(5층 F·7층 G, 시뮬레이터 2657c94 — "상온·냉장·냉동 모두 휠소터 DAS")
  - FC           : 그 밖 선반 통로 61~89 전부(5층 E, 7층 H 오토스토어 61~62)
  - 방식 없음    : PICK_FACE 가 없는 통로(2단 이상만 있는 랙, 90·91·92) — 넣지 않는다(cdc-outbound 는 단건으로 만든다).
통로 목록은 docs/simulator/cdc/layout/chilgok-locations.json 에서 PICK_FACE 가 있는 (구역·층·통로)로 뽑는다.
cdc-location 은 PICK_FACE 가 하나도 없는 통로를 400 NOT_PICK_FACE_AISLE 로 거절한다 — **위치 목록을 LAN 에 먼저 넣은 뒤** 돌린다
(사용자 2026-11-25: LAN 에는 위치 전환 때 같이 넣는다. 옛 목록 dd3d185 에는 랙 1단 피킹면·통로 93 이 없다).

  python -I deploy/lan/seed/cdc_pick_methods.py            # 계획만(방식별 통로 수·목록)
  python -I deploy/lan/seed/cdc_pick_methods.py --apply    # 게이트웨이로 넣기(운영자 claude-cdc-sup, 500행씩)

- 묶음마다 멱등 키가 정해져 있어(`cdc-pick-methods-<지문>-<번호>`) 다시 돌려도 안전하다. 값이 바뀌면 지문이 바뀌어 새 키로 덮어쓴다(설정 = 마지막 값).
- 바뀐 값은 다음 Wave 릴리스부터 쓰인다(이미 만든 작업은 그대로).
"""
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
TABLE = os.path.join(HERE, "..", "..", "..", "docs", "simulator", "cdc", "layout", "chilgok-locations.json")
CENTER = "CDC01"
BATCH = 500


def method_of(area, floor, aisle):
    if 1 <= aisle <= 60:
        return "BOX_PICK"
    if aisle == 93:
        return "DISTRIBUTION"
    if 61 <= aisle <= 89:
        if area == "A":
            return "DPS"
        if area in ("B", "F", "G"):
            return "LINE"
        return "FC"
    return None


def plan():
    with open(TABLE, encoding="utf-8") as f:
        d = json.load(f)
    cols = d["columns"]
    aisles = set()
    for r in d["rows"]:
        row = dict(zip(cols, r))
        if row["locationType"] != "PICK_FACE":
            continue
        _, area, floor, aisle, _, _, _ = row["locationCode"].split("-")
        aisles.add((area, floor, aisle))
    items = []
    for area, floor, aisle in sorted(aisles):
        m = method_of(area, floor, int(aisle))
        if m is not None:
            items.append({"area": area, "floor": floor, "aisle": aisle, "pickMethod": m})
    return items


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
    items = plan()
    by = Counter(i["pickMethod"] for i in items)
    print(f"통로 {len(items)}개 — " + ", ".join(f"{k} {v}" for k, v in sorted(by.items())))
    for m in sorted(by):
        names = [f"{i['area']}-{i['floor']}-{i['aisle']}" for i in items if i["pickMethod"] == m]
        print(f"  {m}: {' '.join(names)}")
    if "--apply" not in sys.argv:
        print("계획만 — 넣으려면 --apply(위치 목록을 LAN 에 먼저 넣은 뒤)")
        return
    sys.path.insert(0, os.path.join(HERE, "..", "checks"))
    import staff_auth  # noqa: E402
    token, _ = staff_auth.staff_token("claude-cdc-sup")
    fp = hashlib.sha256(json.dumps(items, sort_keys=True).encode("utf-8")).hexdigest()[:12]
    for n in range(0, len(items), BATCH):
        chunk = items[n:n + BATCH]
        st, res = http("POST", f"{staff_auth.GW}/cdcs/{CENTER}/aisle-pick-methods",
                       {"reason": "칠곡 통로 피킹 방식(R-19 회신)", "items": chunk},
                       {"Authorization": "Bearer " + token, "X-Source-System": "pmart-seed",
                        "Idempotency-Key": f"cdc-pick-methods-{fp}-{n // BATCH:03d}"})
        if st != 200:
            sys.exit(f"설정 실패 HTTP {st} {res}")
        print(f"  {n + len(chunk)}/{len(items)} 반영(setCount {res.get('setCount')})")
    print("끝")


if __name__ == "__main__":
    main()
