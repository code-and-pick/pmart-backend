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
