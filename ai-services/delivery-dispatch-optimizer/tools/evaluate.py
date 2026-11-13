"""RouteFinder 배정안 vs OR-Tools(CP-SAT) 배정안 비교 — ADR-0058 "성능 검증"(원문 §2.1 ③).

같은 무작위 문제를 두 방식으로 풀고 같은 비용식(dispatch BundleProblem: 라이더 → MFC + 출하 대기 대비 늦음×2/기다림 + Σ고객 도착)으로 잰다.
OR-Tools 쪽은 dispatch Java 계산을 그대로 옮긴 것이다(알뜰배달 1~3건 후보 + 방문 순서 순열 + 지연 한도, CP-SAT 라이더 ↔ 묶음).
도로 시간은 카카오 대신 직선거리 × 1.3 ÷ 5m/s(시속 18km)로 만든 **합성값**이다 — 실제 도로에서는 다시 재야 한다.

    uv run python tools/evaluate.py --instances 60 --seed 1
"""

from __future__ import annotations

import argparse
import itertools
import math
import random
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import planner  # noqa: E402
from app.schemas import PlanRequest  # noqa: E402

MFC = (37.4979, 127.0276)
SPEED = 5.0  # m/s
ORTOOLS_NO_SOLUTION: list[int] = []
ROAD = 1.3


def meters(a, b):
    dlat = math.radians(b[0] - a[0])
    dlng = math.radians(b[1] - a[1])
    h = math.sin(dlat / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlng / 2) ** 2
    return 2 * 6_371_000 * math.asin(min(1, math.sqrt(h)))


def secs(a, b):
    return int(meters(a, b) * ROAD / SPEED)


def instance(rnd: random.Random, n: int, m: int) -> PlanRequest:
    pts = [(MFC[0] + rnd.uniform(-0.025, 0.025), MFC[1] + rnd.uniform(-0.03, 0.03)) for _ in range(n)]
    deliveries = [
        {
            "deliveryId": f"D{i}",
            "at": {"lat": p[0], "lng": p[1]},
            "directSeconds": secs(MFC, p),
            "readyInSeconds": rnd.choice([0, 0, 120, 300, 600]),
            "bundlable": True,
        }
        for i, p in enumerate(pts)
    ]
    riders = [
        {
            "riderId": f"R{j}",
            "pickupSeconds": rnd.randint(60, 900),
            "capacity": 3,
            "allowedDeliveryIds": [d["deliveryId"] for d in deliveries],
        }
        for j in range(m)
    ]
    between = [[0 if i == j else secs(pts[i], pts[j]) for j in range(n)] for i in range(n)]
    return PlanRequest.model_validate(
        {
            "mfc": {"lat": MFC[0], "lng": MFC[1]},
            "deliveries": deliveries,
            "riders": riders,
            "betweenSeconds": between,
            "maxBundleSize": 3,
            "maxExtraSeconds": 1200,
            "lateWeight": 2,
        }
    )


def score(req: PlanRequest, assignments) -> tuple[int, int, int]:
    """(배정 배송 수, 총비용, 2건 이상 묶음 수) — dispatch 와 같은 비용식."""
    idx = {d.delivery_id: i for i, d in enumerate(req.deliveries)}
    ridx = {r.rider_id: k for k, r in enumerate(req.riders)}
    covered = total = bundled = 0
    for a in assignments:
        order = [idx[x] for x in a.delivery_ids]
        arr = planner.arrivals(req, order)
        b = planner.Bundle(tuple(order), tuple(arr))
        total += planner.cost(req, ridx[a.rider_id], b)
        covered += b.size
        bundled += 1 if b.size > 1 else 0
    return covered, total, bundled


def ortools_plan(req: PlanRequest, time_limit: float = 2.0):
    from ortools.sat.python import cp_model

    n = len(req.deliveries)
    direct = [d.direct_seconds for d in req.deliveries]
    cands: list[planner.Bundle] = [planner.Bundle((i,), (direct[i],)) for i in range(n)]
    for k in (2, 3):
        for sub in itertools.combinations(range(n), k):
            best = None
            for order in itertools.permutations(sub):
                arr = planner.arrivals(req, order)
                if arr is None or any(arr[i] - direct[order[i]] > req.max_extra_seconds for i in range(k)):
                    continue
                if best is None or sum(arr) < sum(best.arrivals):
                    best = planner.Bundle(tuple(order), tuple(arr))
            if best is not None:
                cands.append(best)
    costs = {
        (r, b): planner.cost(req, r, bundle)
        for r in range(len(req.riders))
        for b, bundle in enumerate(cands)
        if req.riders[r].capacity >= bundle.size
    }
    hint = greedy(cands, costs)
    model = cp_model.CpModel()
    y = {}
    for (r, b) in costs:
        y[r, b] = model.NewBoolVar(f"y{r}_{b}")
        model.AddHint(y[r, b], 1 if hint.get(b) == r else 0)  # dispatch 와 같이 규칙 기반 해를 힌트로
    for r in range(len(req.riders)):
        model.AddAtMostOne(v for (rr, _), v in y.items() if rr == r)
    for d in range(n):
        model.AddAtMostOne(v for (_, b), v in y.items() if d in cands[b].deliveries)
    bonus = 10_000_000
    model.Maximize(sum(v * (bonus * cands[b].size - min(bonus - 1, costs[r, b])) for (r, b), v in y.items()))
    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = time_limit
    solver.parameters.num_workers = 1
    status = solver.Solve(model)
    if status in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        picked = {b: r for (r, b), v in y.items() if solver.Value(v)}
    else:
        ORTOOLS_NO_SOLUTION.append(n)  # dispatch 와 같이 규칙 기반 해를 쓴다
        picked = hint
    return [
        planner.PlanAssignment(
            rider_id=req.riders[r].rider_id, delivery_ids=[req.deliveries[d].delivery_id for d in cands[b].deliveries]
        )
        for b, r in picked.items()
    ]


def greedy(cands, costs) -> dict[int, int]:
    """dispatch BundleProblem.greedy 와 같은 규칙 — (배송당 비용, 큰 묶음 먼저, 묶음, 라이더) 순으로 겹치지 않게. {묶음: 라이더}."""
    cells = sorted(costs.items(), key=lambda kv: (kv[1] / cands[kv[0][1]].size, -cands[kv[0][1]].size, kv[0][1], kv[0][0]))
    out: dict[int, int] = {}
    used_r: set[int] = set()
    used_d: set[int] = set()
    for (r, b), _ in cells:
        if r in used_r or any(d in used_d for d in cands[b].deliveries):
            continue
        out[b] = r
        used_r.add(r)
        used_d.update(cands[b].deliveries)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--instances", type=int, default=60)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--min-n", type=int, default=4, help="문제당 배송 수 최소")
    ap.add_argument("--max-n", type=int, default=12, help="문제당 배송 수 최대(큰 MFC 비교는 20~40)")
    ap.add_argument("--multistart", action="store_true", help="RouteFinder 를 출발점 n개로 풀고 가장 싼 답(app.routefinder_model.route_cost)")
    ap.add_argument("--ortools-limit", type=float, default=2.0, help="CP-SAT 시간 한도(초) — dispatch solver-time-limit 와 같게")
    args = ap.parse_args()
    from app.routefinder_model import RouteFinderModel, checkpoint_path

    model = RouteFinderModel(checkpoint_path(), multistart=args.multistart)
    rnd = random.Random(args.seed)
    rows = []
    for _ in range(args.instances):
        n = rnd.randint(args.min_n, args.max_n)
        m = rnd.randint(max(2, n // 4), max(2, n // 2))
        req = instance(rnd, n, m)
        t0 = time.perf_counter()
        ai = planner.plan(req, model)
        t_ai = (time.perf_counter() - t0) * 1000
        t0 = time.perf_counter()
        ot = ortools_plan(req, args.ortools_limit)
        t_ot = (time.perf_counter() - t0) * 1000
        rows.append((n, m, score(req, ai), score(req, ot), t_ai, t_ot))
    cov_ai = sum(r[2][0] for r in rows)
    cov_ot = sum(r[3][0] for r in rows)
    same_cov = [r for r in rows if r[2][0] == r[3][0]]
    gap = [(r[2][1] - r[3][1]) / r[3][1] * 100 for r in same_cov if r[3][1] > 0]
    worse_cov = sum(1 for r in rows if r[2][0] < r[3][0])
    better_cov = sum(1 for r in rows if r[2][0] > r[3][0])
    print(f"문제 {len(rows)}개 (배송 {args.min_n}~{args.max_n}, multistart={args.multistart}, 라이더 배송/4~배송/2, 합성 도로 시간)")
    print(f"배정 배송 수 합계: RouteFinder {cov_ai} / OR-Tools {cov_ot}")
    print(f"배정 수가 OR-Tools 보다 적은 문제 {worse_cov}개, 많은 문제 {better_cov}개")
    if gap:
        print(
            f"배정 수가 같은 {len(gap)}개 문제의 총비용 차이(RouteFinder − OR-Tools)/OR-Tools: "
            f"평균 {statistics.mean(gap):+.1f}% · 중앙 {statistics.median(gap):+.1f}% · 최대 {max(gap):+.1f}%"
        )
    print(f"2건 이상 묶음 수: RouteFinder {sum(r[2][2] for r in rows)} / OR-Tools {sum(r[3][2] for r in rows)}")
    print(
        f"계산 시간(ms) 중앙: RouteFinder {statistics.median(r[4] for r in rows):.1f} / "
        f"OR-Tools {statistics.median(r[5] for r in rows):.1f}"
    )
    print(f"계산 시간(ms) 최대: RouteFinder {max(r[4] for r in rows):.0f} / OR-Tools {max(r[5] for r in rows):.0f}")
    if ORTOOLS_NO_SOLUTION:
        print(
            f"OR-Tools 가 시간 안에 해를 못 찾아 규칙 기반 해를 쓴 문제 {len(ORTOOLS_NO_SOLUTION)}개"
            f"(배송 수 {sorted(ORTOOLS_NO_SOLUTION)})"
        )


if __name__ == "__main__":
    main()
