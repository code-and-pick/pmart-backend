"""계약 contracts/openapi/delivery-dispatch-optimizer.yaml 의 요청·응답(JSON 은 camelCase)."""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class _Camel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


class Point(_Camel):
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)


class PlanDelivery(_Camel):
    delivery_id: str
    at: Point
    direct_seconds: int = Field(ge=0)
    ready_in_seconds: int = Field(ge=0)
    bundlable: bool


class PlanRider(_Camel):
    rider_id: str
    pickup_seconds: int = Field(ge=0)
    capacity: int = Field(ge=1, le=3)
    allowed_delivery_ids: list[str]


class PlanRequest(_Camel):
    mfc: Point
    deliveries: list[PlanDelivery] = Field(max_length=50)
    riders: list[PlanRider] = Field(max_length=30)
    between_seconds: list[list[int | None]]
    max_bundle_size: int = Field(ge=1, le=3)
    max_extra_seconds: int = Field(ge=0)
    late_weight: int = Field(ge=1)


class PlanAssignment(_Camel):
    rider_id: str
    delivery_ids: list[str] = Field(min_length=1, max_length=3)


class PlanResponse(_Camel):
    assignments: list[PlanAssignment]
    solver: str
    compute_millis: int | None = None
