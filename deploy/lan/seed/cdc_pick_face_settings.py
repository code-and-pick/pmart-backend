"""칠곡 DC `CDC01` 피킹면 최소·목표(cdc-location `POST /internal/pick-face-settings`) — 시뮬레이터 R-26(2026-11-26).

값: 시뮬레이터 `docs/layout/chilgok-pick-face-settings.json`(pmart-cdc-simulation 8eb7133)의 사본
`docs/simulator/cdc/layout/chilgok-pick-face-settings.json` — 최소 = 칸 용량 × 0.3, 목표 = 칸 용량 × 1.0(낱개, 내림, 시뮬 [가정] PICK_FACE_RULE).
사용자 결정(cdc HANDOVER 4절): "최소·목표는 시뮬레이터 값으로 시드한다". 대상 PICK_FACE 21,846칸(선반·랙 1단 박스 피킹면·야채 레인 G-5-93),
오토스토어 통(C-6·F-7 61~62, 7,200칸)은 입고 포트로 따로 채우므로 뺀다.

  python -I deploy/lan/seed/cdc_pick_face_settings.py            # 계획만 — 위치 사본과 대조(PICK_FACE·목표 ≤ 칸 용량·최소 ≤ 목표)
  PMART_CDC_LOCATION=http://<호스트>:8602/api/v1 python -I deploy/lan/seed/cdc_pick_face_settings.py --apply --approval-ref <승인>

- `/internal/**` 은 게이트웨이가 막으므로 서비스 주소로 바로 부른다(토큰 없음 — ADR-0026, X-Source-System 만). 기본 주소 없음.
- 계약에 승인 번호 칸이 없다 — `--approval-ref` 는 지어내지 않고 받아서 멱등 키와 출력에만 남긴다(PR 세션 일지에 기록).
- 2,000칸씩(11묶음). 묶음 키 `cdc-pick-face-<지문>-<번호>` 라 다시 돌려도 안전하고, 값이 바뀌면 지문이 바뀌어 덮어쓴다(설정 = 마지막 값).
- 위치가 LAN 에 먼저 있어야 한다(없는 칸·PICK_FACE 아닌 칸은 그 묶음 전체 400).
- 넣은 뒤 cdc-inbound 자동 보충(1분 주기)이 `GET /internal/pick-faces`(0.9.0 쪽 넘기기)로 모든 칸을 본다 — 박스 입수(units_per_box)가 없는 SKU 는 UNIT_MISSING.
"""
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
LAYOUT = os.path.join(HERE, "..", "..", "..", "docs", "simulator", "cdc", "layout")
SETTINGS = os.path.join(LAYOUT, "chilgok-pick-face-settings.json")
LOCATIONS = os.path.join(LAYOUT, "chilgok-locations.json")
CENTER = "CDC01"
BATCH = 2000


def load():
    with open(SETTINGS, encoding="utf-8") as f:
        p = json.load(f)
    if p.get("centerId") != CENTER:
        sys.exit(f"센터가 다릅니다: {p.get('centerId')}")
    with open(LOCATIONS, encoding="utf-8") as f:
        d = json.load(f)
    cols = d["columns"]
    rows = {r[cols.index("locationCode")]: dict(zip(cols, r)) for r in d["rows"]}
    items, seen, errors = [], set(), []
    for it in p["items"]:
        code = it["locationCode"]
        mn, tg = it.get("pickFaceMinQty"), it.get("pickFaceTargetQty")
        r = rows.get(code)
        if code in seen:
            errors.append(f"{code} 두 번")
        elif r is None:
            errors.append(f"{code} 위치 사본에 없음")
        elif r["locationType"] != "PICK_FACE":
            errors.append(f"{code} PICK_FACE 아님({r['locationType']})")
        elif not isinstance(mn, int) or not isinstance(tg, int) or mn < 0 or tg < mn:
            errors.append(f"{code} 최소·목표 이상({mn}·{tg})")
        elif r.get("maxQty") is not None and tg > r["maxQty"]:
            errors.append(f"{code} 목표 {tg} > 칸 용량 {r['maxQty']}")
        seen.add(code)
        items.append({"locationCode": code, "pickFaceMinQty": mn, "pickFaceTargetQty": tg})
    if p.get("count") is not None and p["count"] != len(items):
        errors.append(f"count {p['count']} ≠ 줄 {len(items)}")
    return p.get("rule"), items, errors


def post(base, key, body):
    req = urllib.request.Request(
        base.rstrip("/") + "/internal/pick-face-settings",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "Idempotency-Key": key, "X-Source-System": "pmart-seed"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"raw": raw[:200].decode("utf-8", "replace")}


def main():
    for s in (sys.stdout, sys.stderr):
        s.reconfigure(encoding="utf-8")  # Windows 콘솔(cp949)에서도 한글 출력
    rule, items, errors = load()
    chunks = [items[i:i + BATCH] for i in range(0, len(items), BATCH)]
    print(f"규칙 {rule} · 칸 {len(items)} · 묶음 {len(chunks)}(최대 {BATCH})")
    if errors:
        for e in errors[:20]:
            print("  ✗", e)
        sys.exit(f"사본 대조 실패 {len(errors)}건 — 넣지 않습니다.")
    print("사본 대조 통과(모두 PICK_FACE·최소 ≤ 목표 ≤ 칸 용량·중복 없음)")
    if "--apply" not in sys.argv:
        print("계획만 — 넣으려면 --apply --approval-ref <승인> 와 PMART_CDC_LOCATION")
        return
    if "--approval-ref" not in sys.argv:
        sys.exit("--approval-ref <승인> 가 필요합니다(지어내지 않음).")
    approval = sys.argv[sys.argv.index("--approval-ref") + 1]
    base = os.environ.get("PMART_CDC_LOCATION")
    if not base:
        sys.exit("PMART_CDC_LOCATION(cdc-location 주소, 예 http://localhost:8602/api/v1)이 필요합니다.")
    digest = hashlib.sha256(json.dumps(items, sort_keys=True).encode("utf-8")).hexdigest()[:12]
    total = 0
    for n, chunk in enumerate(chunks, 1):
        key = f"cdc-pick-face-{approval}-{digest}-{n}"
        status, body = post(base, key, {"centerId": CENTER, "items": chunk})
        if status != 200:
            sys.exit(f"묶음 {n} HTTP {status} {body} — 앞 묶음은 들어갔습니다(같은 명령으로 다시 돌리면 이어서).")
        total += body.get("setCount", 0)
        print(f"  묶음 {n}/{len(chunks)} 설정 {body.get('setCount')}")
    print(f"끝 — 설정 {total}칸(승인 {approval})")


if __name__ == "__main__":
    main()
