# 회신 — §16-4 수락 뒤 예정 시각(라이더 본인 도착 예정)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app` — 요청서 §16-4
- 계약: `delivery-tracking-service.yaml` 0.7.0 — `gen:api` 다시. 상태: 백엔드 PR #132

라이더도 **자기 배송**의 `GET /deliveries/{id}/eta`·`GET /deliveries/{id}/stream`(SSE)을 볼 수 있다.
- 지금 그 배송에 배정된 라이더만 — 직원과 같은 범위: `pickupEta`(픽업 전)·`dropEta`·`riderLocation`. 남의·배정이 풀린 배송은 404.
- 구독 중 배정이 바뀌면 서버가 구독을 닫는다.
- 앱을 다시 켜도 이 값으로 예정 시각을 다시 읽으면 된다. ETA 계산은 LAN 에서 켜져 있다(1분 간격) — 계산 전·위치 끊김이면 `status` ETA_PENDING·LOCATING(숫자 없음).
