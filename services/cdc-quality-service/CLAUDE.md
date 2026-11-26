# cdc-quality-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cdc-quality-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cdc-quality-service.yaml` → `openApiGenerate` 가 `com.pmart.cdc.quality.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.cdc.quality.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:cdc-quality-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:cdc-quality-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8604.

## 상태
- 2026-11-26 첫 구현(계약 0.2.0, Flyway V2, 로컬 포트 8604 — WMS CDC-F-028, 사용자 "보류·해제·폐기만"·"MFC 품질과 같게"). 설정은 부트스트랩만(ADR-0029) — 시험 사본 `src/integrationTest/resources/pmart-config/`, 정본은 PC1 이 pmart-config 에.
- 판단·승인 기록만 여기, 상태·수량은 cdc-inventory `POST /internal/quality-postings`(0.15.0, `infrastructure/InventoryClient` — 주소 `pmart.quality.cdc-inventory-base-url`). 재고 호출은 트랜잭션 밖, 키 고정(`cdcq-hold-<id>-<줄>`·`cdcq-rel-<id>`·`cdcq-dex-<실행 ID>`), 불명이면 UNKNOWN_RESULT → 주기 재시도(`pmart.quality.retry-interval`, 기본 30초).
- 보류 `/cdcs/{cdcId}/quality-holds`(`HoldService`): 줄마다 재고 HOLD → 줄별 heldQty(실제 보류 수량). 모든 줄 거절 REJECTED, 남은 보류 0 이면 CLOSED, 해제·폐기가 있으면 PARTIALLY_RELEASED.
- 해제 요청 → 결정(`…/release-requests/{id}/decisions`): 결정은 그 센터 CDC_SUPERVISOR(아니면 403), 요청자 본인 422 SELF_APPROVAL. APPROVE → 승인 관문(`ApprovalGate` — cdc-inventory 실사 관문을 옮김, kind QUALITY_RELEASE, `pmart.quality.approval-ref.*` 10만 원·20개) → 재고 RELEASE(approvalRef 없으면 `CENTER:<releaseId>`).
- 폐기 `/cdcs/{cdcId}/disposal-approvals`(`DisposalService`): 요청은 holdId 필수·남은 보류 이하(요청 중 해제·승인 잔량을 뺀 값, 422 QTY_EXCEEDS_HELD) → 결정(같은 규칙, kind DISPOSAL — 승인만으로 장부 안 바뀜) → 실행(증빙 필수·승인 잔량 − 진행 중 실행 이하, 재고 DISPOSE). 다 실행하면 COMPLETED.
- 리콜(0.3.0, Flyway V3 `recall`·`recall_lot`·`recall_center` — WMS CDC-F-028·정책 16.13, 사용자 "본사 승인 번호 + 모든 CDC"·"리콜은 출고대기분도 막음", `RecallService`): `POST·GET /cdc-recalls`·`GET /cdc-recalls/{id}`(여러 센터라 `/cdcs/{cdcId}` 밖 — MFC `/recalls` 와 겹치지 않게). 운영자 + approvalRef 필수(빈 값 422, 기록만). 발령 트랜잭션에 `RecallIssued`(토픽 cdc.quality.v1 — 이 서비스 첫 발행) → (밖) cdc-inventory `GET /internal/lot-traces` 로 LOT 마다 정상 현물이 있는 CDC 에 보류(`HoldService.insertSystemHold`, reason RECALL, 요청자 `RECALL:<id>` — 재고 HOLD `recall: true` 로 출고대기 91 까지). 추적 불명은 TRACING → 주기 재시도, 다 되면 ISSUED. 조회는 센터별 보류와 지금 출발 추적(안 되면 traceFreshness UNAVAILABLE). 발령 뒤 들어온 같은 LOT 은 아직 막지 않는다.
- 운영 제한(0.4.0, Flyway V4 `operation_restriction` — WMS CDC-F-031, 사용자 "새 할당과 출발"·"문서 그대로", `RestrictionService`): `/cdcs/{cdcId}/operation-restrictions`(걸기·목록·조회·`…/lifts`). 활동 ALLOCATION·DEPARTURE, 범위 온도대·SKU(비면 전체). 걸기: CONGESTION 관리자, SAFETY 그 센터 운영자, HQ·QUALITY 승인 번호. 풀기: 관리자, CONGESTION 밖은 승인 번호도. 자동 해제 없음. 내부 확인 `POST /internal/operation-restriction-checks`(맞는 ACTIVE 하나라도 있으면 restricted — cdc-inventory 새 할당·cdc-outbound 출발 검증이 부르고, 안 되면 그쪽이 보류). 사건 OperationRestrictionApplied·Lifted(cdc.quality.v1, restrictionType WORK_STOP/QUALITY).
- 그 밖 사건 발행 없음(재고의 LotBlocked·LotReleased·InventoryAdjustmentPosted 가 사실을 알린다). FEFO 예외·LOT 계보 화면은 501(구현할 때 `/cdcs/{cdcId}/…` 로 옮김). 게이트웨이 라우트·LAN 배치·pmart-config 는 PC1(요청 `docs/operations/pc1-todo.md`).
- 결정 역할 고침(2026-11-26): `CDC_SUPERVISOR`(직원 권한 정본) — 전에는 정본에 없는 `CDC_MANAGER`.
