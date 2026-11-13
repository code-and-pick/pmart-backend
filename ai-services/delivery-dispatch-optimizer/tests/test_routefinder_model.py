"""실제 RouteFinder 가중치로 — 가중치가 없으면(내려받지 못하면) 건너뛴다. ``uv run pytest -m model``."""

import random

import pytest

pytestmark = pytest.mark.model


@pytest.fixture(scope="module")
def model():
    try:
        from app.routefinder_model import RouteFinderModel, checkpoint_path

        return RouteFinderModel(checkpoint_path())
    except Exception as e:  # noqa: BLE001
        pytest.skip("RouteFinder 가중치를 쓸 수 없음: " + str(e))


def test_routes_cover_every_customer_once_and_respect_capacity(model):
    rnd = random.Random(7)
    mfc = (37.4979, 127.0276)
    pts = [(37.49 + rnd.random() * 0.03, 127.02 + rnd.random() * 0.03) for _ in range(8)]
    routes = model(mfc, pts, 3)
    flat = [i for r in routes for i in r]
    assert sorted(flat) == list(range(8))
    assert all(1 <= len(r) <= 3 for r in routes)


def test_two_nearby_customers_end_up_together(model):
    mfc = (37.4979, 127.0276)
    # 북동쪽에 붙어 있는 두 고객, 남서쪽 멀리 하나
    pts = [(37.5100, 127.0400), (37.5102, 127.0403), (37.4850, 127.0150)]
    routes = model(mfc, pts, 3)
    together = [r for r in routes if 0 in r]
    assert 1 in together[0]


def test_multistart_covers_every_customer_once_and_is_not_worse():
    """출발점 n개 중 가장 싼 답 — 이 고정 예에서 한 번 풀기보다 비용이 크지 않다(탐욕 첫 고객이 n개 출발점 안에 있으면 늘 성립)."""
    try:
        import torch

        from app.routefinder_model import RouteFinderModel, _normalize, checkpoint_path, route_cost

        single = RouteFinderModel(checkpoint_path())
        multi = RouteFinderModel(checkpoint_path(), multistart=True)
    except Exception as e:  # noqa: BLE001
        pytest.skip("RouteFinder 가중치를 쓸 수 없음: " + str(e))
    rnd = random.Random(11)
    mfc = (37.4979, 127.0276)
    pts = [(37.48 + rnd.random() * 0.04, 127.01 + rnd.random() * 0.04) for _ in range(10)]
    locs = torch.tensor([_normalize([mfc, *pts])])

    def cost(routes):
        actions = [a for r in routes for a in [*(i + 1 for i in r), 0]]
        return float(route_cost(locs, torch.tensor([actions]), 2.0)[0])

    m = multi(mfc, pts, 3)
    assert sorted(i for r in m for i in r) == list(range(10))
    assert all(1 <= len(r) <= 3 for r in m)
    assert cost(m) <= cost(single(mfc, pts, 3)) + 1e-6
