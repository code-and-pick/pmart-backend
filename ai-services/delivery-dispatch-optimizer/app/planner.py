"""배정안 계산(모델과 무관한 부분) — 묶음 검증·나누기, 라이더 ↔ 묶음 짝짓기.

규칙(ADR-0058 결정 11·13, dispatch ``BundleProblem`` 과 같은 비용식):

* 알뜰배달(bundlable)만 2~max_bundle_size 건으로 묶는다. 한집·예약은 1건.
* 묶음 방문 순서는 경로 모델(RouteFinder)이 정한다. 도로 시간으로 다시 보아 어느 배송이든 바로 갈 때보다 max_extra_seconds 넘게 늦거나
  고객 사이 길이 없으면(None) 앞에서부터 되는 데까지 자르고 나머지는 다시 묶음으로 본다.
* 비용(라이더 r, 묶음 b) = 라이더 → MFC + (출하 대기보다 늦게 도착한 초 × late_weight 또는 일찍 와서 기다린 초) + Σ고객 도착 초.
* 라이더는 묶음 하나, 남은 용량·갈 수 있는 배송 안에서만. 배송당 평균 비용이 작은 짝부터 겹치지 않게 고른다. 끝내 못 맡긴 묶음은 1건씩 나눠 다시 짝짓는다.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass

from .schemas import PlanAssignment, PlanRequest

# 경로 모델: (MFC, 고객 좌표 목록, 최대 묶음 크기) → 고객 번호 묶음(방문 순서)
RouteModel = Callable[[tuple[float, float], Sequence[tuple[float, float]], int], list[list[int]]]


@dataclass(frozen=True)
class Bundle:
    deliveries: tuple[int, ...]  # 방문 순서
    arrivals: tuple[int, ...]  # MFC 출발 뒤 각 고객 도착(초)

    @property
    def size(self) -> int:
        return len(self.deliveries)


def validate(req: PlanRequest) -> None:
    n = len(req.deliveries)
    if len(req.between_seconds) != n or any(len(row) != n for row in req.between_seconds):
        raise ValueError("betweenSeconds 는 deliveries 수와 같은 정사각 행렬이어야 합니다.")
    ids = [d.delivery_id for d in req.deliveries]
    if len(set(ids)) != n:
        raise ValueError("deliveryId 가 겹칩니다.")
    known = set(ids)
    for r in req.riders:
        if not set(r.allowed_delivery_ids) <= known:
            raise ValueError("allowedDeliveryIds 에 없는 배송이 있습니다: " + r.rider_id)


def arrivals(req: PlanRequest, order: Sequence[int]) -> list[int] | None:
    """방문 순서대로 도착 초. 고객 사이 길이 없으면 None."""
    t = req.deliveries[order[0]].direct_seconds
    out = [t]
    for a, b in zip(order, order[1:]):
        leg = req.between_seconds[a][b]
        if leg is None or leg < 0:
            return None
        t += leg
        out.append(t)
    return out


def split_valid(req: PlanRequest, order: Sequence[int]) -> list[Bundle]:
    """지연 한도·길 없음을 넘지 않게 앞에서부터 잘라 묶음 목록으로."""
    out: list[Bundle] = []
    rest = list(order)
    while rest:
        take = 1
        for k in range(min(len(rest), req.max_bundle_size), 1, -1):
            arr = arrivals(req, rest[:k])
            if arr is not None and all(
                arr[i] - req.deliveries[rest[i]].direct_seconds <= req.max_extra_seconds
                for i in range(k)
            ):
                take = k
                break
        part = rest[:take]
        arr = arrivals(req, part) or [req.deliveries[part[0]].direct_seconds]
        out.append(Bundle(tuple(part), tuple(arr)))
        rest = rest[take:]
    return out


def bundles(req: PlanRequest, model: RouteModel | None) -> list[Bundle]:
    """모든 배송을 묶음으로 나눈다(배송은 정확히 한 묶음에)."""
    singles = [i for i, d in enumerate(req.deliveries) if not d.bundlable]
    pooled = [i for i, d in enumerate(req.deliveries) if d.bundlable]
    out = [Bundle((i,), (req.deliveries[i].direct_seconds,)) for i in singles]
    if len(pooled) >= 2 and req.max_bundle_size >= 2 and model is not None:
        mfc = (req.mfc.lat, req.mfc.lng)
        pts = [(req.deliveries[i].at.lat, req.deliveries[i].at.lng) for i in pooled]
        routes = model(mfc, pts, req.max_bundle_size)
        seen: set[int] = set()
        for route in routes:
            order = [pooled[j] for j in route if 0 <= j < len(pooled) and pooled[j] not in seen]
            seen.update(order)
            if order:
                out.extend(split_valid(req, order))
        for i in pooled:  # 모델이 빠뜨린 고객은 1건으로
            if i not in seen:
                out.append(Bundle((i,), (req.deliveries[i].direct_seconds,)))
    else:
        out.extend(Bundle((i,), (req.deliveries[i].direct_seconds,)) for i in pooled)
    return out


def cost(req: PlanRequest, r: int, b: Bundle) -> int:
    rider = req.riders[r]
    ready = max(req.deliveries[d].ready_in_seconds for d in b.deliveries)
    diff = rider.pickup_seconds - ready
    wait = diff * req.late_weight if diff > 0 else -diff
    return rider.pickup_seconds + wait + sum(b.arrivals)


def assign(req: PlanRequest, bs: list[Bundle]) -> list[PlanAssignment]:
    allowed = [set(r.allowed_delivery_ids) for r in req.riders]
    ids = [d.delivery_id for d in req.deliveries]

    def feasible(r: int, b: Bundle) -> bool:
        return req.riders[r].capacity >= b.size and all(ids[d] in allowed[r] for d in b.deliveries)

    def match(candidates: list[Bundle], free: set[int]) -> tuple[list[tuple[int, Bundle]], list[Bundle]]:
        cells = sorted(
            ((cost(req, r, b) / b.size, -b.size, k, r) for k, b in enumerate(candidates) for r in free if feasible(r, b)),
        )
        used_r: set[int] = set()
        used_b: set[int] = set()
        picked: list[tuple[int, Bundle]] = []
        for _, _, k, r in cells:
            if r in used_r or k in used_b:
                continue
            used_r.add(r)
            used_b.add(k)
            picked.append((r, candidates[k]))
        left = [b for k, b in enumerate(candidates) if k not in used_b]
        return picked, left

    free = set(range(len(req.riders)))
    picked, left = match(bs, free)
    free -= {r for r, _ in picked}
    # 못 맡긴 묶음은 1건씩 나눠 남은 라이더에게
    pieces = [Bundle((d,), (req.deliveries[d].direct_seconds,)) for b in left if b.size > 1 for d in b.deliveries]
    if pieces and free:
        more, _ = match(pieces, free)
        picked.extend(more)
    return [
        PlanAssignment(rider_id=req.riders[r].rider_id, delivery_ids=[ids[d] for d in b.deliveries])
        for r, b in picked
    ]


def plan(req: PlanRequest, model: RouteModel | None) -> list[PlanAssignment]:
    validate(req)
    if not req.deliveries or not req.riders:
        return []
    return assign(req, bundles(req, model))
