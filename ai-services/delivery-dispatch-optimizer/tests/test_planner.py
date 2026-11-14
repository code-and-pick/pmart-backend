from app import planner
from app.schemas import PlanRequest


def req(deliveries, riders, between, max_extra=1200, max_size=3):
    return PlanRequest.model_validate(
        {
            "mfc": {"lat": 37.4979, "lng": 127.0276},
            "deliveries": deliveries,
            "riders": riders,
            "betweenSeconds": between,
            "maxBundleSize": max_size,
            "maxExtraSeconds": max_extra,
            "lateWeight": 2,
        }
    )


def d(i, bundlable=True, direct=400, ready=0):
    return {
        "deliveryId": f"D{i}",
        "at": {"lat": 37.50 + i * 0.001, "lng": 127.03},
        "directSeconds": direct,
        "readyInSeconds": ready,
        "bundlable": bundlable,
    }


def r(i, pickup=300, capacity=3, allowed=None):
    return {"riderId": f"R{i}", "pickupSeconds": pickup, "capacity": capacity, "allowedDeliveryIds": allowed}


def one_route(mfc, pts, max_size, deadlines=None):
    return [list(range(len(pts)))]


def test_model_route_becomes_one_bundle_for_one_rider():
    q = req([d(0), d(1)], [r(0, allowed=["D0", "D1"])], [[0, 120], [120, 0]])
    out = planner.plan(q, one_route)
    assert [(a.rider_id, a.delivery_ids) for a in out] == [("R0", ["D0", "D1"])]


def test_single_deliveries_are_not_bundled():
    q = req(
        [d(0, bundlable=False), d(1, bundlable=False)],
        [r(0, allowed=["D0", "D1"]), r(1, pickup=500, allowed=["D0", "D1"])],
        [[0, 60], [60, 0]],
    )
    out = planner.plan(q, one_route)
    assert sorted(len(a.delivery_ids) for a in out) == [1, 1]


def test_detour_over_limit_or_missing_road_splits_bundle():
    far = req([d(0), d(1)], [r(0, allowed=["D0", "D1"]), r(1, allowed=["D0", "D1"])], [[0, 1800], [1800, 0]])
    out = planner.plan(far, one_route)
    assert sorted(len(a.delivery_ids) for a in out) == [1, 1]
    no_road = req([d(0), d(1)], [r(0, allowed=["D0", "D1"])], [[0, None], [None, 0]])
    assert [len(a.delivery_ids) for a in planner.plan(no_road, one_route)] == [1]


def test_rider_capacity_and_allowed_are_respected():
    # 하나씩 배달(용량 1) 라이더 — 묶음을 1건씩 나눠 맡긴다
    q = req([d(0), d(1)], [r(0, capacity=1, allowed=["D0", "D1"])], [[0, 120], [120, 0]])
    out = planner.plan(q, one_route)
    assert len(out) == 1 and len(out[0].delivery_ids) == 1
    # 반경 밖(allowed 아님) 배송은 맡기지 않는다
    q2 = req([d(0)], [r(0, allowed=[])], [[0]])
    assert planner.plan(q2, one_route) == []


def test_without_model_everything_is_single():
    q = req([d(0), d(1)], [r(0, allowed=["D0", "D1"]), r(1, allowed=["D0", "D1"])], [[0, 120], [120, 0]])
    out = planner.plan(q, None)
    assert sorted(len(a.delivery_ids) for a in out) == [1, 1]


def test_bad_matrix_is_rejected():
    q = req([d(0), d(1)], [r(0, allowed=["D0"])], [[0]])
    try:
        planner.plan(q, one_route)
        raise AssertionError("ValueError 가 나야 한다")
    except ValueError:
        pass


def test_road_matrix_goes_to_models_that_accept_it():
    """2026-11-14: 실제 도로 시간 행렬(평면 단위)을 받는 모델에만 넘긴다 — [0]=MFC, 길 없음은 큰 값, 고객 → MFC 는 0."""
    seen = {}

    class RoadModel:
        accepts_road = True

        def __call__(self, mfc, pts, max_size, deadlines=None, road=None):
            seen["road"] = road
            return [list(range(len(pts)))]

    q = req([d(0, direct=400), d(1, direct=800)], [r(0, allowed=["D0", "D1"])], [[0, 120], [None, 0]])
    planner.plan(q, RoadModel())
    m = seen["road"]
    assert len(m) == 3 and all(len(row) == 3 for row in m)
    assert m[0][2] == 2 * m[0][1]  # 도로 초에 비례
    assert m[1][0] == 0.0 and m[2][0] == 0.0
    assert m[2][1] == planner.NO_ROUTE_UNITS
    assert abs(m[1][2] / m[0][1] - 120 / 400) < 1e-9
    planner.plan(q, one_route)  # 받지 않는 모델에는 넘기지 않는다(오류 없음)


def test_road_route_cost_matches_manual_sum():
    import torch

    from app.routefinder_model import road_route_cost

    road = torch.tensor([[[0.0, 1.0, 2.0], [0.0, 0.0, 0.5], [0.0, 0.5, 0.0]]])
    # 한 경로 MFC → 1 → 2: 도착 1.0, 1.5 → 2.5 + 경로 1 × 2.0
    assert float(road_route_cost(road, torch.tensor([[1, 2, 0]]), 2.0)[0]) == 4.5
    # 두 경로 MFC → 1, MFC → 2: 1.0 + 2.0 + 2 × 2.0
    assert float(road_route_cost(road, torch.tensor([[1, 0, 2]]), 2.0)[0]) == 7.0


def test_deadline_never_below_direct_straight_distance():
    """2026-11-14: 도로가 직선보다 유난히 짧은 고객도 모델 시간창이 MFC 직선거리 밑으로 가지 않는다(디코딩이 끝나지 않던 것)."""
    import math

    from app.routefinder_model import _normalize

    far = {**d(0, direct=60), "at": {"lat": 37.52, "lng": 127.05}}  # 멀지만 도로 60초(비현실적으로 짧음)
    q = req([far, d(1, direct=900), d(2, direct=900)], [r(0, allowed=["D0", "D1", "D2"])], [[0, 100, 100], [100, 0, 100], [100, 100, 0]], max_extra=0)
    mfc = (q.mfc.lat, q.mfc.lng)
    pts = [(x.at.lat, x.at.lng) for x in q.deliveries]
    dl = planner._deadlines(q, [0, 1, 2], mfc, pts)
    locs = _normalize([mfc, *pts])
    assert all(dl[a] >= math.dist(locs[0], locs[a + 1]) for a in range(3))
