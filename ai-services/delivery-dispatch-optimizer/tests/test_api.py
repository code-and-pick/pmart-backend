import os

os.environ["OPTIMIZER_DISABLE_MODEL"] = "1"  # 시험은 가짜 모델을 끼운다

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

BODY = {
    "mfc": {"lat": 37.4979, "lng": 127.0276},
    "deliveries": [
        {"deliveryId": "D0", "at": {"lat": 37.5006, "lng": 127.036}, "directSeconds": 400, "readyInSeconds": 0, "bundlable": True},
        {"deliveryId": "D1", "at": {"lat": 37.5012, "lng": 127.0372}, "directSeconds": 450, "readyInSeconds": 0, "bundlable": True},
    ],
    "riders": [{"riderId": "R0", "pickupSeconds": 300, "capacity": 3, "allowedDeliveryIds": ["D0", "D1"]}],
    "betweenSeconds": [[0, 120], [120, 0]],
    "maxBundleSize": 3,
    "maxExtraSeconds": 1200,
    "lateWeight": 2,
}


def test_plan_with_model():
    with TestClient(app) as c:
        app.state.model = lambda mfc, pts, k, deadlines=None: [list(range(len(pts)))]
        app.state.model_name = "FAKE"
        res = c.post("/api/v1/internal/dispatch-plans", json=BODY)
        assert res.status_code == 200
        body = res.json()
        assert body["solver"] == "FAKE"
        assert body["assignments"] == [{"riderId": "R0", "deliveryIds": ["D0", "D1"]}]
        assert isinstance(body["computeMillis"], int)
        assert c.get("/health").json()["status"] == "UP"


def test_no_model_is_503_and_bad_body_is_400():
    with TestClient(app) as c:
        app.state.model = None
        app.state.model_name = None
        res = c.post("/api/v1/internal/dispatch-plans", json=BODY)
        assert res.status_code == 503
        assert res.json()["code"] == "MODEL_UNAVAILABLE"
        assert c.get("/health").json()["status"] == "DEGRADED"
        app.state.model = lambda mfc, pts, k, deadlines=None: []
        bad = dict(BODY, betweenSeconds=[[0]])
        assert c.post("/api/v1/internal/dispatch-plans", json=bad).status_code == 400
        assert c.post("/api/v1/internal/dispatch-plans", json={"mfc": {}}).status_code == 400


def test_oversized_matrix_is_400_before_planning():
    """betweenSeconds 행·열도 50 까지(PC 1 검토 L6) — 큰 본문은 계산 전에 거절."""
    with TestClient(app) as c:
        app.state.model = lambda mfc, pts, k, deadlines=None: []
        app.state.model_name = "FAKE"
        too_many_rows = dict(BODY, betweenSeconds=[[0, 0]] * 51)
        assert c.post("/api/v1/internal/dispatch-plans", json=too_many_rows).status_code == 400
        too_wide = dict(BODY, betweenSeconds=[[0] * 51, [0] * 51])
        assert c.post("/api/v1/internal/dispatch-plans", json=too_wide).status_code == 400
