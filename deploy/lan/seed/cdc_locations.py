"""칠곡 DC `CDC01` 위치(랙·피킹 선반·출고대기·승강기 문·지하 접안)를 cdc-location 에 넣는다(2026-11-25 cdc 세션).

위치 목록 = docs/simulator/cdc/layout/chilgok-locations.json(시뮬레이터 배치 내보내기 사본, 38,926칸 — R-21 f3248e5, 오토스토어 H-7-61 포함).
cdc-location 0.2.0 `POST /internal/location-batches` 로 2,000행씩, ACTIVE 로. 묶음마다 멱등 키가 정해져 있어
(`cdc-loc-<사본 출처 커밋>-<묶음 번호>`) 중간에 멈춰도 다시 돌리면 이미 들어간 묶음은 재생되고 나머지만 들어간다.
배치가 바뀌어 사본을 다시 받으면 출처 커밋이 바뀌므로 새 키가 된다 — 이미 있는 코드는 409 LOCATION_CODE_EXISTS 로 멈춘다(바뀐 칸 정리는 따로).

  python -I deploy/lan/seed/cdc_locations.py                                        # 계획만(묶음 수·유형별 칸 수)
  PMART_CDC_LOCATION=http://<호스트>:8602/api/v1 python -I deploy/lan/seed/cdc_locations.py --apply --approval-ref <BO-06 승인>

- `/internal/**` 은 게이트웨이가 막으므로 서비스 주소로 바로 부른다(토큰 없음 — ADR-0026, X-Source-System 만). LAN 주소는 배치가 정해지면 넣는다(기본값 없음).
- approvalRef(센터 기준정보 승인 BO-06)는 지어내지 않는다 — 인자로 받는다.
- 모든 값은 사용자 제공 설계 가정·개념도(chilgok-dc.md §1)이며 실제 센터 검증이 아니다.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
TABLE = os.path.join(HERE, "..", "..", "..", "docs", "simulator", "cdc", "layout", "chilgok-locations.json")
BATCH = 2000


def load():
    with open(TABLE, encoding="utf-8") as f:
        d = json.load(f)
    cols = d["columns"]
    items = []
    for r in d["rows"]:
        row = dict(zip(cols, r))
        cap = {k: v for k, v in (("unit", row["capacityUnit"]), ("maxQty", row["maxQty"]), ("maxWeightG", row["maxWeightG"])) if v is not None}
        items.append({
            "locationCode": row["locationCode"],
            "centerId": d["centerId"],
            "temperatureZone": row["temperatureZone"],
            "locationType": row["locationType"],
            "capacity": cap,
        })
    m = re.search(r"@ ([0-9a-f]{7,40})", d["source"])
    if not m:
        sys.exit("사본 source 에 출처 커밋(@ <해시>)이 없습니다.")
    return d["centerId"], m.group(1), items


def post(base, key, body):
    req = urllib.request.Request(
        base + "/internal/location-batches",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Idempotency-Key": key,
            "X-Source-System": "pmart-seed",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main():
    for s in (sys.stdout, sys.stderr):
        s.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글·기호 출력
    center, commit, items = load()
    chunks = [items[i:i + BATCH] for i in range(0, len(items), BATCH)]
    print(f"센터 {center} · 사본 {commit} · {len(items)}칸 · {len(chunks)}묶음")
    for k, v in sorted(Counter(i["locationType"] for i in items).items()):
        print(f"  {k:10s} {v}")
    if "--apply" not in sys.argv:
        print("계획만 — 넣으려면 --apply --approval-ref <승인> 와 PMART_CDC_LOCATION")
        return
    if "--approval-ref" not in sys.argv:
        sys.exit("--approval-ref <센터 기준정보 승인> 가 필요합니다(지어내지 않음).")
    approval = sys.argv[sys.argv.index("--approval-ref") + 1]
    base = os.environ.get("PMART_CDC_LOCATION")
    if not base:
        sys.exit("PMART_CDC_LOCATION(cdc-location 주소, 예 http://localhost:8602/api/v1)이 필요합니다.")
    total = 0
    for n, chunk in enumerate(chunks):
        key = f"cdc-loc-{commit}-{n:03d}"
        status, body = post(base, key, {"centerId": center, "approvalRef": approval, "activate": True, "items": chunk})
        if status != 200:
            print(f"묶음 {n} 실패 {status}: {json.dumps(body, ensure_ascii=False)[:800]}")
            sys.exit(1)
        total += body.get("appliedQty", 0)
        print(f"묶음 {n} ({len(chunk)}칸) 반영 — 키 {key}")
    print(f"끝: {total}칸(재생된 묶음 포함)")


if __name__ == "__main__":
    main()
