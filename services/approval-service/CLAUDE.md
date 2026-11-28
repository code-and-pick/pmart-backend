# approval-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `approval-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/approval-service.yaml` → `openApiGenerate` 가 `com.pmart.approval.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.approval.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:approval-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:approval-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8505.

## 상태
- 3단계 스캐폴딩(2026-10-23).
- 슬라이스 1 최소 범위(2026-10-28, ADR-0014): 변경 요청 생성·조회, 제출(승인 단위 확정·이중 발급 금지 `DUPLICATE_APPROVAL`), 승인함·상세, 결정(APPROVE/REJECT) → `ApprovalDecided`(`platform.approval.v1`). 저장은 JDBC(`ApprovalStore`, 본문 jsonb 스냅샷), 명령은 `RequestIdempotency` 안에서.
- 승인자 권한: `pmart.approval.approver-roles.<종류>=<역할>` 설정값 + 자기승인 금지. 미설정 종류는 `CONFIG_REQUIRED`. auth-service 행위 시점 검증(대행·센터 범위)은 auth 구현 후 `ActionAuthorizer` 로 교체.
- 반영 회신(2026-11-06, ADR-0039): `ApplicationReceiptRecorded`(`platform.approval-receipt.v1`, 바인딩 `receiptEvents-in-0`)를 `ApplicationReceiptRecorder` 가 inbox 안에서 `application_receipt`(V3, 제약 V4: `event_id` UNIQUE·`result` CHECK 다섯 값 — 벗어나면 저장 실패 → 재시도 뒤 DLT)에 저장. 같은 승인·수신자·순번은 한 번만. 실행 축 = 그 승인 정본(targetService)의 마지막 순번 회신 결과(`ApprovalRules.executionAfterReceipt`), 전달 축 `ACKNOWLEDGED`. 다른 수신자·반려 승인의 회신은 기록만, 모르는 승인은 경고 후 버림. 상세 `receipts`·`GET /approvals/{id}/receipts`. 1차 회신 정본은 pricing 뿐.
- 직원 권한 요청(2026-11-13, ADR-0056 5-4 — 요청 AP-2): 구독 `GrantRequested`(platform.employee.v1, 바인딩 `employeeEvents-in-0`, 다른 직원 사건은 버림) → `ApprovalService.intakeGrantRequest` 가 inbox 안에서 변경 요청(제출됨)+승인(type `GRANT`, 정본 `employee-auth-service`, 원객체 = 운영자 ID, 요청자 = 등록한 인사팀 직원)을 만든다. 행마다 자동 승인 규칙 표 `pmart.approval.auto-approval-rules`(`ApprovalRules.autoApprovable` — 규칙 역할 + 소속이 센터 + 권한 센터 = 그 센터 하나)를 보고, 맞는 행은 승인 하나로 묶어 바로 APPROVED(승인자 `RULE:<id>@v<버전>`, 행위자 SYSTEM, `ApprovalDecided`), 나머지는 승인 하나로 심사 중(`approver-roles.GRANT`). 부분 승인이 없어 승인을 나눈다. 규칙을 바꾸면 version 을 올린다(설정 저장소 이력이 변경 기록).
- **MFC 승인 번호 확인·사용(2026-11-17, 요청 AP-1, 계약 0.3.0, V5)**: 종류 `MFC_STOCK_ADJUSTMENT`(실사 조정·정정 → mfc-inventory, 폐기·격리 해제 → mfc-quality — Q-11 기준 이상 본사 재고 승인, `approver-roles` INVENTORY_APPROVER)·`MFC_OPERATION_RESTRICTION`(운영 제한 해제 → mfc-capacity, OPERATOR_ADMIN). targetRef = `<mfcId>:<kind>:<ref>`, kind 가 정본을 정한다 — 생성 때 `domain/MfcTargets` 가 형식·kind·정본·재고 행(SKU·수량 ≥ 1)을 검사(400 VALIDATION_FAILED). 기준 판정은 MFC 정본. 내부 API(토큰 없음): `POST /internal/approval-ref-checks`(확인만) · `POST /internal/approval-ref-uses`(확인 + 한 번 사용, `useRef` 업무 멱등 — 같은 useRef 는 같은 결과, 다른 useRef 는 ALREADY_USED). 결과는 늘 200 `usable`·`reason`(NOT_FOUND·TYPE_MISMATCH·TARGET_MISMATCH·NOT_APPROVED·SELF_APPROVAL(집행자 = 승인자)·ALREADY_USED·NOT_YET_EFFECTIVE·EXPIRED, `domain/ApprovalRefRules`) + 승인 단위 행(정본이 범위 비교). 사용 기록 `approval_ref_use`(PK = 승인, `INSERT … ON CONFLICT DO NOTHING`), 쓸 수 없는 판정은 기록하지 않아 RequestIdempotency 를 쓰지 않는다(`Idempotency-Key` 는 공통 헤더 검사 때문에 받기만 함). 사용은 실행 축을 바꾸지 않는다(회신은 ADR-0039 그대로). MFC 쪽 전환은 PC 3(AP-1 "PC 3 이 할 것").
- **보는 사람이 결정할 수 있는지(2026-11-18, 요청 AP-3, 계약 0.4.0)**: 상세·승인함 행에 `viewerCanDecide`·`viewerDecideBlockedReason`(NOT_PENDING·SELF_APPROVAL·CONFIG_REQUIRED·NO_ROLE·GRANT_TO_SELF). 판정은 `ApprovalRules.decideBlock` 하나 — 결정 API 도 `requireCanDecide` 로 같은 함수를 거쳐 예전과 같은 오류를 던진다(검사 순서 그대로). 승인함 `decidableByMe=true` 는 같은 조건을 SQL 로(`decidableTypes` = 설정 역할을 가진 종류, 원격 호출 없음). 같은 날 승인 번호 사용 시각을 µs 로 잘라 저장(같은 useRef 재시도 응답이 첫 응답과 같게).
- **검색 동의어(2026-11-20, ADR-0073 단계 B, 계약 0.5.0)**: 종류 `SEARCH_SYNONYM`(정본 search-service, targetRef `pmart-product-synonyms`, `targetExpectedVersion` 필수, 행 = 규칙 UPSERT·DELETE — `domain/SearchSynonymTargets` 가 생성 때 형식 검사, 400 VALIDATION_FAILED). 종류별 요청 역할 설정 `pmart.approval.requester-roles.<종류>`(새로 — 있으면 그 역할만 변경 요청 생성, 아니면 403, `ApprovalRules.requireRequesterRole`) — SEARCH_SYNONYM: [MD, OPERATOR_ADMIN], 승인 `approver-roles.SEARCH_SYNONYM` = OPERATOR_ADMIN(사용자 "MD 요청, 관리자 승인"). 대상이 하나라 심사 중인 동의어 승인은 한 번에 하나(DUPLICATE_APPROVAL).
- **운영자 RAG 문서(2026-11-28, ADR-0092, 계약 0.7.0)**: 종류 `RAG_DOCUMENT`(정본 ai-assistant-service, targetRef = 초안 ID `KDR-…`, `targetExpectedVersion` 필수 = 초안 기준 판, 행 정확히 하나 — lineKey = 문서 ID `docs/rag/rag-docs/<영역>/<이름>.md`, attributes op UPSERT·RETIRE·draftId = targetRef·UPSERT 는 contentSha256). `domain/RagDocumentTargets` 가 생성 때 형식 검사(400). 요청 역할 `requester-roles.RAG_DOCUMENT` [OPERATOR_ADMIN], 승인 `approver-roles.RAG_DOCUMENT` OPERATOR_ADMIN(자기 승인 금지 — 다른 관리자). 본문은 ai-assistant 초안 응답의 `approvalRequest` 그대로. 반영 회신은 ai-assistant 가. **0.8.0(ADR-0094)**: 정본에 customer-assistant-service(고객 RAG) 도 — `RagDocumentTargets.TARGET_SERVICES`, 문서 ID 에 `docs/business/customer-faq/<이름>.md`.
- 미구현(501): 철회, 부분 승인·증빙 요청, 재심사, 동기 회신(`/internal/application-receipts` — 이벤트로 대신), 일괄 변경, 정책 참조.
