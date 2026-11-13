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
