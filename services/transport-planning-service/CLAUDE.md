# transport-planning-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `transport-planning-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/transport-planning-service.yaml` → `openApiGenerate` 가 `com.pmart.transport.planning.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.transport.planning.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:transport-planning-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:transport-planning-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8701.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- **0.2.0(2026-11-26, TMS 1단계 — 설계 `docs/cdc/design/2026-11-26-tms-first-stage.md`, cdc 세션)**, Flyway V2. 결정(노선·정규차·생성·이동·계획완료·결행·취소)은 `CDC_SUPERVISOR`, 조회는 그 센터 운영자.
  - `domain/Schedule`(정본 3.3 — 출차 HH:mm 이 12:00 앞이면 D+1, 상차 = 출차 − 30분, 정차 도착 = 앞 출발 + 구간, 하차 30분, 마지막 도착 ≤ D+1 07:30, 기한 구간 [D 12:00, D+1 12:00)), `domain/RequestRules`(V1·취소·V2·V6·D-06·V8 순서).
  - `RouteService`: 노선(정차 1~3, 같은 MFC 는 살아 있는 노선 하나 — 409 MFC_ALREADY_ROUTED, 시각 불가 400 ROUTE_TIME_INFEASIBLE)·노선 × 온도대 정규차(fleet 확인 — REGULAR·사용 중·온도대 일치, 최대값 사본).
  - `PlanningService.generate`: cdc-outbound `transport-feed`(트랜잭션 밖, 안 되면 503·아무것도 안 바꿈) → 출고일 잠금 안에서 정규차 있는 노선 × 온도대 지시서(TO-yyyyMMdd-nnn, 없으면 만듦) → 요청서 반영·검증·연결(손으로 옮긴 계획중 지시서는 유지, 계획완료 뒤 새 버전은 NEWER_VERSION·출차 뒤 VERSION_AFTER_DEPARTURE 사유만). 적재·결행 후보·용량 초과는 조회 때 계산(저장 안 함).
  - `move`: 같은 출고일·온도대 계획중끼리, 정차 3곳·07:30 검사, 더하는 정차의 구간 분은 원래 지시서의 그 정차 구간 [가정], 보내는 쪽은 노선 정차가 아니고 남은 요청서가 없으면 정차를 뺌.
  - `confirm`: PLANNING → CONFIRMING(차량·기사 바꾸기 가능 — 온도대·용량 검사) → fleet 점유(키 고정, 창 = 상차 시작 ~ 복귀) → execution 운행(tripId = orderId) → DISPATCHED·요청서 ASSIGNED. 불명이면 CONFIRMING 그대로(같은 요청·주기 재시도 `pmart.planning.retry-interval` 가 이어 감), 업무 거절이면 이번 점유만 풀고 PLANNING(사유·이력). 요청 키 재생 대신 상태로 멱등.
  - `cancel`(출차 전 — DISPATCHED 면 execution 취소 → 점유 해제, 요청서 ACCEPTED·잔여 물량)·`skip`(요청서 없을 때만)·`tripUpdate`(execution 이 알림 — 낮은 tripVersion 무시, DEPARTED·HANDED_OVER 를 요청서에).
  - 이벤트 없음(1단계). 시드 `deploy/lan/seed/tms_routes.py`(RT-04·07·09·10 — 시각 [가정], fleet 시드 뒤).
- 0.3.0(2026-11-26, 도크 DK-2 — 사용자 "계획완료 때 자동"): 계획완료가 fleet 점유 뒤 cdc-location 도크 예약(창 = 상차 시작 ~ 계획 출차, `pmart.planning.cdc-location-base-url`) — NO_DOCK 이면 도크 없이(이력 NO_DOCK 한 번), CAPACITY_EXCEEDED 면 거절(이번 점유·도크만 풂). 운행 본문에 dockCode·dockReservationId. 취소·운행 DEPARTED 사본 때 도크 해제(실패해도 경고만). Flyway V3 `transport_order.dock_code·dock_reservation_id`.
- 0.4.0(2026-11-27, DC 백오피스 DN-4): `api/TrfNumberLabels`(ResponseBodyAdvice) — TransportOrder·목록·생성 응답에 `trfNumbers`·`stops[].trfNumbers`(transport_request.trf_number 사본, 없으면 빈 문자열). 경로 그대로.
- 0.5.0(2026-11-27, 시뮬레이터 R-28 — 사용자 "정해"): READY TRF 의 출고일 = 도착 마감 D+1 07:30 ≤ dueAt 인 가장 늦은 D(`Schedule.readyShipDate`), ARRIVAL·HANDOVER 는 그대로 [D 12:00, D+1 12:00). 생성은 feed 를 [D 07:30, D+2 07:30)(`Schedule.feedWindow`)로 읽어 걸러, READY 의 D−1 몫도 본다(권고 잠금 D → D−1 순). 맞는 출고일 지시서가 닫혔거나(ORDER_CLOSED) 지난 날이라 없으면 HELD `READY_UNREACHABLE`(다음 날로 미루지 않음). cdc-outbound 0.32.0 feed 의 deadlineMeaning(없으면 ARRIVAL).
- 0.6.0(2026-11-27, 시뮬레이터 R-30 — 사용자 "이월 + 용차", 정본 7.3 3·4단계, Flyway V4 `transport_request.carried_over_from·carryover_reason`·`transport_order.spot`(칸 유일 색인은 NOT spot 만)): 이월 `POST /cdcs/{cdcId}/transport-requests/{trfId}/carryovers`(HELD ORDER_CLOSED·READY_UNREACHABLE → 다음 열린 출고일 7일 안 노선 × 온도대 계획중 지시서, 없으면 정규차로 만듦, 닫힌 날 건너뜀)·용차 `POST /cdcs/{cdcId}/transport-orders/spot-orders`(HELD ORDER_CLOSED·READY_UNREACHABLE·NO_REGULAR_VEHICLE·OVER_VEHICLE_CAPACITY 요청서만, 같은 온도대·MFC 3곳, 정차 = 각 노선 구간 분 짧은 순 [가정], 출차 = 든 노선 중 가장 이른 시각, fleet 사용 중·온도대 차량, 적재 검사 → PLANNING SPOT). 둘 다 상태로 멱등. 옮긴 요청서는 생성이 원래 출고일로 되돌리지 않음(`upsertRequest` 가 carried_over_from 이면 그 출고일).
