"""FastAPI 진입점 — POST /api/v1/internal/dispatch-plans, GET /health (계약 delivery-dispatch-optimizer.yaml).

모델을 올리지 못하면 계산은 503 MODEL_UNAVAILABLE(dispatch 는 OR-Tools 로 대신 — ADR-0058 결정 13). 좌표·배송 ID 만 받으며
요청 본문은 로그에 남기지 않는다(라이더·고객 위치).
"""

from __future__ import annotations

import logging
import os
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import planner
from .schemas import PlanRequest, PlanResponse

log = logging.getLogger("delivery-dispatch-optimizer")
logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"))


def load_model():
    """기본 적재기. 시험은 app.state.model 을 바꿔 끼운다."""
    if os.environ.get("OPTIMIZER_DISABLE_MODEL") == "1":
        return None, None
    try:
        from .routefinder_model import NAME, RouteFinderModel, checkpoint_path

        multistart = os.environ.get("OPTIMIZER_MULTISTART", "1") != "0"  # 출발점 n개 중 가장 싼 답(약 1.4배 시간, 2026-11-13 평가)
        return RouteFinderModel(checkpoint_path(), multistart=multistart), NAME + (" multistart" if multistart else "")
    except Exception as e:  # noqa: BLE001 — 모델 없이도 떠서 503 으로 알린다
        log.error("RouteFinder 를 올리지 못함 — 계산 요청은 503: %s", e)
        return None, None


@asynccontextmanager
async def lifespan(app: FastAPI):
    if not hasattr(app.state, "model"):
        app.state.model, app.state.model_name = load_model()
    yield


app = FastAPI(title="delivery-dispatch-optimizer", version="0.1.0", lifespan=lifespan)


def problem(status: int, code: str, detail: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        media_type="application/problem+json",
        content={"type": "about:blank", "title": code, "status": status, "code": code, "detail": detail},
    )


@app.exception_handler(RequestValidationError)
async def on_validation(_: Request, e: RequestValidationError):
    return problem(400, "VALIDATION_FAILED", "요청 형식이 계약과 다릅니다: " + str(len(e.errors())) + "건")


@app.get("/health")
async def health(request: Request):
    model = getattr(request.app.state, "model", None)
    return {"status": "UP" if model is not None else "DEGRADED", "model": getattr(request.app.state, "model_name", None)}


@app.post("/api/v1/internal/dispatch-plans", response_model=PlanResponse, response_model_by_alias=True)
def plan_dispatch(req: PlanRequest, request: Request):
    model = getattr(request.app.state, "model", None)
    if model is None:
        return problem(503, "MODEL_UNAVAILABLE", "경로 모델이 없습니다.")
    t0 = time.perf_counter()
    try:
        assignments = planner.plan(req, model)
    except ValueError as e:
        return problem(400, "VALIDATION_FAILED", str(e))
    ms = int((time.perf_counter() - t0) * 1000)
    log.info("배정안 %d건 / 배송 %d · 라이더 %d · %dms", len(assignments), len(req.deliveries), len(req.riders), ms)
    return PlanResponse(assignments=assignments, solver=request.app.state.model_name or "UNKNOWN", compute_millis=ms)
