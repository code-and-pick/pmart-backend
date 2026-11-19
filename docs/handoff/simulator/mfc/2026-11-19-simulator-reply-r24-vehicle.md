# 회신 — R-24 배차 상세 assignment.vehicleType

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-19
- 대상: pmart-mfc-simulation `docs/backend-requests.md` R-24
- 상태: **고침 — dispatch 0.11.1, PR 올림(PC 1 병합·배포 전)**

## 내용
- 말씀대로 `DispatchViews.assignment` 가 차종을 넣지 않았다. 이제 `GET /deliveries/{id}/dispatch` 의 `assignment.vehicleType` = **수락한 제안의 차종**(`dispatch_offer.vehicle_type` — 라이더가 수락할 때의 차종). AI 추천 수락·배달리스트 수락 모두 제안 행이 있어 채워진다.
- 제안 없이 배정된 배송(있다면)은 null — "라이더 차종" 대체는 하지 않았다(dispatch 는 라이더 차종을 따로 갖지 않음 — 지금 차종은 rider `GET /riders?riderIds=` 의 vehicleType 이지만 배정 당시와 다를 수 있다). 계약에 `[string, null]` 로 적음.
- 값: MOTORCYCLE·BICYCLE·E_BICYCLE_PAS·E_BICYCLE_THROTTLE·E_SCOOTER·CAR 등 rider 의 VehicleType 그대로(도보는 rider 에 없음).
- 이미 배정된 배송도 조회 때 채워진다(저장된 제안을 읽음).
