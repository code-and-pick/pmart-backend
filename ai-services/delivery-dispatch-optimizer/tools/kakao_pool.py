"""카카오 실제 도로 시간 점 묶음 만들기(2026-11-14) — 평가·추가 학습이 같이 쓴다.

MFC 둘레 무작위 점 N 개(합성 좌표 — 고객 위치 아님)에 대해 MFC → 점, 점 → 점 도로 시간(초)을 받아 JSON 으로 저장한다.
줄(출발 점) 단위로 받다가 사용량이 떨어지면(400 code -10·429·--max-calls) **다 받은 줄까지만** 묶음으로 남긴다 — 앞 k 줄이 끝났으면
앞 k 개 점끼리는 모두 알기 때문. 같은 구간은 `.cache/kakao-legs.json` 에서 다시 쓴다.

    uv run python tools/kakao_pool.py --size 150 --max-calls 900 --out .cache/kakao-pool.json
    uv run python tools/kakao_pool.py --size 40 --mfc 37.5563,126.9220 --seed 2 --out .cache/kakao-pool-mapo.json
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from kakao_road import KakaoRoad  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=150)
    ap.add_argument("--mfc", default="37.4979,127.0276", help="위도,경도 — 기본은 평가 도구 MFC(강남)")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--max-calls", type=int, default=900)
    ap.add_argument("--kakao-key-file", default=None)
    ap.add_argument("--out", default=str(Path(__file__).resolve().parents[1] / ".cache" / "kakao-pool.json"))
    args = ap.parse_args()
    mfc = tuple(float(x) for x in args.mfc.split(","))
    rnd = random.Random(args.seed)
    road = KakaoRoad.from_file(args.kakao_key_file, max_calls=args.max_calls)

    def point():
        return (mfc[0] + rnd.uniform(-0.025, 0.025), mfc[1] + rnd.uniform(-0.03, 0.03))

    pts = [point() for _ in range(args.size)]
    direct = road.seconds(mfc, pts)
    for _ in range(5):  # 길 없는 점(강·공원 안 등)은 다시 뽑는다
        bad = [i for i, s in enumerate(direct) if s is None]
        if not bad:
            break
        for i in bad:
            pts[i] = point()
        for i, s in zip(bad, road.seconds(mfc, [pts[i] for i in bad])):
            direct[i] = s
    keep = [i for i, s in enumerate(direct) if s is not None]
    pts = [pts[i] for i in keep]
    direct = [direct[i] for i in keep]
    rows: list[list[int | None]] = []
    stopped = None
    for i in range(len(pts)):
        others = [pts[j] for j in range(len(pts)) if j != i]
        try:
            got = iter(road.seconds(pts[i], others))
        except SystemExit as e:  # 사용량 소진·상한 — 다 받은 줄까지만
            stopped = str(e)
            break
        rows.append([0 if i == j else next(got) for j in range(len(pts))])
    k = len(rows)
    out = {
        "mfc": list(mfc),
        "points": [list(p) for p in pts[:k]],
        "direct": direct[:k],
        "between": [r[:k] for r in rows],
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(out), "utf-8")
    road.save()
    print(f"점 {k}개 묶음 저장 {args.out} — 카카오 호출 {road.calls}번(저장분 제외)" + (f" · 멈춤: {stopped}" if stopped else ""))


if __name__ == "__main__":
    main()
