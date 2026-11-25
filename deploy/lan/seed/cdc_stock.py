"""칠곡 DC `CDC01` 시작 재고 — MFC 와 같은 실제 LAN SKU 로(사용자 2026-11-25 "MFC랑 같은 sku로 해서 연동되게"). chilgok-dc.md CDC-7.

단계(모두 API — DB 직접 입력 없음, ADR-0014):
  1. SKU 목록: cdc-inventory `GET /cdcs/CDC01/sku-handlings`(게이트웨이, 운영자 claude-cdc-sup) — 상품 사건으로 만든 투영 전부.
     ACTIVE 만, 온도대는 `GET /internal/skus/{sku}` 의 storageType(AMBIENT·CHILLED·FROZEN, 없으면 건너뛰고 알림).
  2. 칠곡 취급 켜기: `POST /cdcs/CDC01/sku-handling-batches`(500행씩, 이미 켜진 것은 바뀌지 않음).
  3. 안전량 0: `POST /internal/safety-stocks`(시뮬레이터와 같은 값 — 0 도 값이다, 미설정과 다름).
  4. 시작 재고: `POST /internal/initial-balances`(2,000줄씩, 센터·SKU·LOT 1회). 칸 배치 규칙(시뮬레이터 catalog 와 같음):
     - SKU 하나 = 피킹 선반 칸 하나(온도대 구역의 PICK_FACE, 코드 순) + 팔레트 랙 기둥 한 칸(같은 구역의 같은 통로·베이·슬롯 1~4단, 코드 순).
       랙 기둥이 선반 칸보다 적은 구역(예 냉동 H)은 기둥이 다 차면 선반에만 둔다.
       구역(R-22 글자): FROZEN → H, CHILLED → G, AMBIENT → A·B·C·D·E·F 차례(상품 분류 근거가 없어 SKU 번호 순으로 채움 — 구역별 상품 성격은 맞추지 않음).
     - LOT: 선반·랙 1·2단 = 먼저 끝나는 LOT(`<sku>-A`), 랙 3·4단 = 다음 LOT(`<sku>-B`). 식별 VERIFIED·품질 RELEASED.
     - 수량 = 그 칸 maxQty(위치 사본 값). 소비기한은 아래 [시험값](업무 규칙 아님 — 실제 상품별 기한 자료가 없어서).
  위치 목록 = docs/simulator/cdc/layout/chilgok-locations.json(위치는 cdc_locations.py 로 먼저 넣는다).

  python -I deploy/lan/seed/cdc_stock.py                                   # 계획만(LAN 에서 SKU 를 읽어 칸 배치·수량 합)
  python -I deploy/lan/seed/cdc_stock.py --skus-file skus.json             # 계획만, SKU 를 파일로([{"sku":..,"storageType":..}]) — LAN 없이
  PMART_CDC_INVENTORY=http://<호스트>:8601/api/v1 python -I deploy/lan/seed/cdc_stock.py --apply \\
      --stock-approval <초기 보유 승인> --safety-approval <안전량 승인>

- 묶음마다 멱등 키가 정해져 있어(`cdc-stock-<단계>-<SKU 목록 지문>[-<기준일>]-<번호>`) 다시 돌려도 안전하다. 소비기한은 기준일(`--base-date YYYY-MM-DD`,
  없으면 오늘)에서 센다 — 중간에 멈춘 것을 다른 날 이어서 돌릴 때는 처음 날짜를 준다(PR #460 검토). SKU 가 늘면 지문이 바뀌어 새 키가 되고,
  이미 넣은 LOT 은 409 INITIAL_BALANCE_EXISTS 로 멈춘다 — 늘어난 SKU 만 넣으려면 `--only-new`(이미 재고가 있는 SKU 를 스냅샷으로 빼고 넣음).
- 승인 번호(초기 보유·안전량)는 지어내지 않는다 — 인자로 받는다. 모든 값은 사용자 제공 설계 가정·시험 데이터다(chilgok-dc.md §1).
"""
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
TABLE = os.path.join(HERE, "..", "..", "..", "docs", "simulator", "cdc", "layout", "chilgok-locations.json")
CENTER = "CDC01"
ZONES = {"FROZEN": ["H"], "CHILLED": ["G"], "AMBIENT": ["A", "B", "C", "D", "E", "F"]}  # R-22 구역 글자
# [시험값] 오늘부터 소비기한까지 일수(LOT A, LOT B) — 업무 규칙이 아니다
SHELF_LIFE = {"FROZEN": (180, 270), "CHILLED": (14, 21), "AMBIENT": (180, 365)}
HANDLING_BATCH, SAFETY_BATCH, STOCK_BATCH = 500, 2000, 2000


def load_locations():
    with open(TABLE, encoding="utf-8") as f:
        d = json.load(f)
    cols = d["columns"]
    return [dict(zip(cols, r)) for r in d["rows"]]


def parts(code):
    c, area, floor, aisle, bay, level, slot = code.split("-")
    return area, floor, int(aisle), int(bay), int(level), int(slot)


def plan(skus, locations, today):
    """skus: [(sku, storageType)] SKU 번호 순. → (줄 목록, 건너뛴 SKU, 구역별 SKU 수)."""
    shelves = defaultdict(list)  # 구역 → 선반 칸(코드 순)
    columns = defaultdict(dict)  # 구역 → (층, 통로, 베이, 슬롯) → {단: 칸}
    for loc in sorted(locations, key=lambda x: x["locationCode"]):
        area, floor, aisle, bay, level, slot = parts(loc["locationCode"])
        if loc["locationType"] == "PICK_FACE":
            shelves[area].append(loc)
        elif loc["locationType"] == "RESERVE":
            columns[area].setdefault((floor, aisle, bay, slot), {})[level] = loc
    free_cols = {a: [v for _, v in sorted(cs.items()) if set(v) >= {1, 2, 3, 4}] for a, cs in columns.items()}
    used = Counter()
    lines, skipped, per_zone = [], [], Counter()
    for sku, temp in skus:
        zone = next((z for z in ZONES.get(temp or "", []) if used[z] < len(shelves[z])), None)
        if zone is None:
            skipped.append((sku, temp, "온도대 없음" if temp not in ZONES else "칸 모자람"))
            continue
        i = used[zone]
        used[zone] += 1
        per_zone[zone] += 1
        shelf = shelves[zone][i]
        cols = free_cols.get(zone, [])
        col = cols[i] if i < len(cols) else None  # 랙 기둥이 모자라면 선반만
        life_a, life_b = SHELF_LIFE[temp]
        lot_a = (f"{sku}-A", (today + timedelta(days=life_a)).isoformat())
        lot_b = (f"{sku}-B", (today + timedelta(days=life_b)).isoformat())
        places = [(shelf, lot_a)]
        if col is not None:
            places += [(col[1], lot_a), (col[2], lot_a), (col[3], lot_b), (col[4], lot_b)]
        for loc, (lot, exp) in places:
            lines.append({
                "sku": sku, "lot": lot, "lotIdentification": "VERIFIED", "expiresAt": exp,
                "locationCode": loc["locationCode"], "qty": int(loc["maxQty"] or 1), "qualityState": "RELEASED",
            })
    return lines, skipped, per_zone


def http(method, url, body=None, headers=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    h = {"Content-Type": "application/json", **(headers or {})}
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def fetch_skus(gw, token, inv):
    out, cursor = [], None
    while True:
        q = {"size": 200}
        if cursor:
            q["cursor"] = cursor
        st, page = http("GET", f"{gw}/cdcs/{CENTER}/sku-handlings?" + urllib.parse.urlencode(q),
                        headers={"Authorization": "Bearer " + token})
        if st != 200:
            sys.exit(f"SKU 목록 실패 HTTP {st} {page}")
        for it in page.get("items", []):
            if it.get("skuState") == "ACTIVE":
                st, p = http("GET", f"{inv}/internal/skus/{it['sku']}", headers={"X-Source-System": "pmart-seed"})
                out.append((it["sku"], p.get("storageType") if st == 200 else None))
        cursor = (page.get("page") or {}).get("nextCursor")
        if not cursor:
            return sorted(out)


def main():
    for s in (sys.stdout, sys.stderr):
        s.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글 출력
    args = sys.argv
    # 소비기한 기준일 — 이어서 돌릴 때 같은 날을 줘야 같은 본문(같은 멱등 키)이 된다
    today = date.fromisoformat(args[args.index("--base-date") + 1]) if "--base-date" in args else date.today()
    inv = os.environ.get("PMART_CDC_INVENTORY")
    if "--skus-file" in args:
        with open(args[args.index("--skus-file") + 1], encoding="utf-8") as f:
            skus = sorted((x["sku"], x.get("storageType")) for x in json.load(f))
        token, gw = None, None
    else:
        sys.path.insert(0, os.path.join(HERE, "..", "checks"))
        import staff_auth  # noqa: E402
        if not inv:
            sys.exit("PMART_CDC_INVENTORY(cdc-inventory 주소, 예 http://192.168.0.222:8601/api/v1)가 필요합니다.")
        gw = staff_auth.GW
        token, _ = staff_auth.staff_token("claude-cdc-sup")
        skus = fetch_skus(gw, token, inv)
    if "--only-new" in args and inv:
        st, snap = http("GET", f"{inv}/internal/snapshots?cdcId={CENTER}", headers={"X-Source-System": "pmart-seed"})
        have = {i["sku"] for i in snap.get("items", [])} if st == 200 else set()
        skus = [s for s in skus if s[0] not in have]
    lines, skipped, per_zone = plan(skus, load_locations(), today)
    fp = hashlib.sha256("|".join(s for s, _ in skus).encode()).hexdigest()[:12]
    print(f"기준일 {today.isoformat()} — 이어서 돌릴 때는 --base-date {today.isoformat()}")
    print(f"센터 {CENTER} · SKU {len(skus)}(배치 {sum(per_zone.values())}, 건너뜀 {len(skipped)}) · 재고 줄 {len(lines)} · 합 {sum(l['qty'] for l in lines):,}개 · 지문 {fp}")
    for z in sorted(per_zone):
        print(f"  구역 {z}: {per_zone[z]} SKU")
    for s in skipped[:20]:
        print(f"  건너뜀 {s[0]} ({s[1]}) — {s[2]}")
    if "--apply" not in args:
        print("계획만 — 넣으려면 --apply --stock-approval <승인> --safety-approval <승인> 와 PMART_CDC_INVENTORY")
        return
    if token is None:
        sys.exit("--apply 는 LAN 에서 SKU 를 읽을 때만(--skus-file 과 함께 쓰지 않음).")
    for flag in ("--stock-approval", "--safety-approval"):
        if flag not in args:
            sys.exit(f"{flag} <승인 번호> 가 필요합니다(지어내지 않음).")
    stock_ref = args[args.index("--stock-approval") + 1]
    safety_ref = args[args.index("--safety-approval") + 1]
    placed = sorted({l["sku"] for l in lines})
    src = {"X-Source-System": "pmart-seed"}
    # 2) 취급 켜기
    for n in range(0, len(placed), HANDLING_BATCH):
        chunk = placed[n:n + HANDLING_BATCH]
        st, b = http("POST", f"{gw}/cdcs/{CENTER}/sku-handling-batches",
                     {"reason": "칠곡 시작 재고(cdc_stock.py)", "items": [{"sku": s, "enabled": True} for s in chunk]},
                     {**src, "Authorization": "Bearer " + token, "Idempotency-Key": f"cdc-stock-handling-{fp}-{n // HANDLING_BATCH:03d}"})
        if st != 200:
            sys.exit(f"취급 켜기 실패 HTTP {st} {json.dumps(b, ensure_ascii=False)[:600]}")
    print(f"취급 켜기 {len(placed)}")
    # 3) 안전량 0
    for n in range(0, len(placed), SAFETY_BATCH):
        chunk = placed[n:n + SAFETY_BATCH]
        st, b = http("POST", f"{inv}/internal/safety-stocks",
                     {"cdcId": CENTER, "approvalRef": safety_ref, "lines": [{"sku": s, "safetyQty": 0} for s in chunk]},
                     {**src, "Idempotency-Key": f"cdc-stock-safety-{fp}-{n // SAFETY_BATCH:03d}"})
        if st != 200:
            sys.exit(f"안전량 실패 HTTP {st} {json.dumps(b, ensure_ascii=False)[:600]}")
    print(f"안전량 0 {len(placed)}")
    # 4) 시작 재고 — 한 LOT 의 줄이 두 묶음에 갈라지지 않게 SKU 단위로 끊는다
    batches, cur = [], []
    for sku in placed:
        mine = [l for l in lines if l["sku"] == sku]
        if len(cur) + len(mine) > STOCK_BATCH:
            batches.append(cur)
            cur = []
        cur.extend(mine)
    if cur:
        batches.append(cur)
    total = 0
    for n, chunk in enumerate(batches):
        st, b = http("POST", f"{inv}/internal/initial-balances",
                     {"cdcId": CENTER, "approvalRef": stock_ref, "lines": chunk},
                     {**src, "Idempotency-Key": f"cdc-stock-initial-{fp}-{today.isoformat()}-{n:03d}"})
        if st != 200:
            hint = ""
            if b.get("code") == "INITIAL_BALANCE_EXISTS":
                hint = " — 이미 넣은 LOT 입니다. 처음 돌린 날의 --base-date 로 다시 돌리거나 --only-new 로 새 SKU 만"
            sys.exit(f"시작 재고 묶음 {n} 실패 HTTP {st} {json.dumps(b, ensure_ascii=False)[:800]}{hint}")
        total += b.get("appliedQty", 0)
        print(f"시작 재고 묶음 {n} ({len(chunk)}줄) 반영")
    print(f"끝: {total:,}개(재생된 묶음 포함)")


if __name__ == "__main__":
    main()
