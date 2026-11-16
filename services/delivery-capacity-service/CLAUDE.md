# delivery-capacity-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `delivery-capacity-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/delivery-capacity-service.yaml` → `openApiGenerate` 가 `com.pmart.delivery.capacity.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.delivery.capacity.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:delivery-capacity-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:delivery-capacity-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8402.

## 예약 슬롯 (2026-10-31)
- `GET /slots?mfcId&date`: 오늘·내일(Asia/Seoul)만. 운영 구간은 center-master `GET /internal/centers/{id}/calendar?kind=CUSTOMER_ORDER`(내부 호출), 그 안에 온전히 들어가는 1시간 시간대. [확정] 00:00~08:00 실제 배송 없음 → 08시 전 시간대는 만들지 않는다.
- 정원 `pmart.capacity.slot-capacity`(정책값, pmart-config `delivery-capacity-service-<프로필>.yml`). 비우면 CONFIG_REQUIRED(선택 불가). 센터 없음 404, 시간표 미승인 422.
- 선택 마감 선행시간은 정책이 없어 시간대 시작 시각(`cutoffAt`)에 마감한다 — 정책이 정해지면 바꾼다.
- 잔여 = 정원 − 점유(만료 전 HELD + CONFIRMED). 0이면 FULL. 비회원 토큰도 조회 가능(guest-paths).

## 상태
예약 슬롯 조회·슬롯 점유 구현(2026-10-31). S4 교체 점유(newSlotFor)는 미지원(422 UNSUPPORTED_ACTION). SlotHoldResult 이벤트는 아직 발행하지 않는다(동기 응답으로 대신).

## 슬롯 점유 (2026-10-31, `/internal/slot-holds/**`)
- 점유 판정은 조회와 같은 규칙(`SlotRules.selectable`) → 아니면 422 SLOT_NOT_SELECTABLE. 정원 미설정·캘린더 조회 실패(센터 없음·미승인·장애 모두) → 422 CONFIG_REQUIRED(계약 문구대로, 503 아님). 정원 소진 → 422 CAPACITY_EXCEEDED.
- 같은 슬롯 점유는 `slot_lock` 행 FOR UPDATE 로 직렬화한 뒤 `slot_hold` 를 센다(별도 집계 열 없음). 주문당 활성 1건은 부분 유일 색인 `ux_slot_hold_active_order` 가 최종 방어선(409 HOLD_ALREADY_ACTIVE).
- TTL(기본 600초) 지난 HELD 는 정리 전에도 정원에서 빠지고 EXPIRED 로 보인다(확정 불가). `HoldExpirySweeper` 가 `pmart.capacity.hold-sweep-interval`(기본 PT30S)마다 SKIP LOCKED 로 EXPIRED 저장.
- 확정·해제 결과는 `slot_hold_command` 에 남겨 `.../{requestId}` 로 조회한다. 모든 변경은 RequestIdempotency 안(같은 키 재전송은 마감 뒤에도 기존 점유 반환).

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.
- **배송 지연 가능 안내(CP-1 1부, 2026-11-16)**: 구독 `capacityEvents-in-0` ← mfc.capacity.v1 `CapacityReduced` → `CapacityReductionHandler` — 영향 주문의 CONFIRMED 점유를 도착 시간대별로 묶어 `SlotCapacityReduced`(delivery.slot.v1, aggregate Slot `mfcId:날짜:시작분`, window ARRIVAL), 점유 없는 주문은 MFC 작업 창(WORK)으로 한 건. 점유·정원은 바꾸지 않는다(결정 2026-11-15 — 유지 + 안내). 이 서비스의 첫 구독·발행.
