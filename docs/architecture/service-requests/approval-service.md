# approval-service 에 대한 요청 (MFC 쪽에서)

## AP-1. MFC 승인 번호(approvalRef) 실제 검증 (2026-11-09 기록)

- **요청한 곳**: mfc-inventory(실사 조정·폐기 실행), mfc-quality(격리 해제·폐기), mfc-capacity(운영 제한 BO-08).
- **지금(임시)**: 세 서비스의 `ApprovalRefs` 가 설정 `pmart.approval-ref.mode: RECORD_ONLY` 일 때만 번호를 **확인 없이 기록**한다. 이 값은 LAN·local 설정에만 있다(`deploy/lan/.config-repo/application-lan.yml`, `application-local.yml`). 설정이 없으면 CONFIG_REQUIRED 로 막히므로 **승인 연동 전에는 운영에서 쓸 수 없다**.
- **approval-service 가 할 것**
  1. MFC 승인 유형 추가(BO-15 재고 조정·폐기, BO-08 운영 제한). 지금 `ApprovalRules.targetType` 은 PRODUCT·PRICE·DISPLAY·CENTER_MASTER·SERVICEABILITY·INITIAL_STOCK 만.
  2. 승인 번호 검증 수단 — 내부 API(계약 `approval-service.yaml` 에 없음, InternalController 501) 또는 사건 중 하나로 정한다.
- **요청한 쪽에서 바꿀 것**: `ApprovalRefs` 를 실제 검증으로 바꾸고 RECORD_ONLY 를 LAN 시험용으로만 남긴다.
- **사용자 결정**: Q-11 "위험 추가 승인(금액·수량 기준)" 은 기준이 정해지면(`docs/business/flows/pending-user-decisions.md`).
- 근거: `services/mfc-*-service/.../application/ApprovalRefs.java`, `docs/business/flows/mfc-defaults-status.md` #8.
- **결정(2026-11-17 사용자 "추천대로")**: Q-11 기준 = 한 건 손실 **10만 원 이상** 또는 한 SKU **20개 이상**이면 본사 재고 승인(`INVENTORY_APPROVER`), 그 아래는 센터 관리자. approval-service 는 MFC 승인 종류와 승인 번호 확인 내부 API 를 만든다(PC 1). 기준 판정·번호 확인 호출·`ApprovalRefs` 전환은 MFC 서비스(PC 3). LAN `RECORD_ONLY` 는 PC 3 전환 뒤 끈다.
- **구현(PC 1 쪽, 2026-11-17 — approval-service 계약 0.3.0, V5, LAN 배포 전)**:
  - 종류 `MFC_STOCK_ADJUSTMENT`(승인자 `approver-roles` = `INVENTORY_APPROVER`)·`MFC_OPERATION_RESTRICTION`(= `OPERATOR_ADMIN` — BO-08 "본사 승인 완료"를 본사 운영으로 읽음, 아래 확인 필요). CDC 용 `OPERATION_RESTRICTION`·`INVENTORY_ADJUSTMENT` 등은 그대로.
  - 변경 요청 형식(`POST /api/v1/change-requests` → `…/submissions`, 운영자 토큰 — 요청자 = 센터 관리자): `targetRef = <mfcId>:<kind>:<ref>`, kind 가 `targetService` 를 정한다 — `COUNT_ADJUSTMENT`(ref = 실사 세션 ID)·`CORRECTION`(ref = 정정 참조) → `mfc-inventory-service`, `DISPOSAL`(ref = 폐기 요청 ID)·`QUALITY_RELEASE`(ref = 품질 판정·보류 ID) → `mfc-quality-service`, `RESTRICTION_LIFT`(ref = 운영 제한 ID `OPR-…`) → `mfc-capacity-service`. `MFC_STOCK_ADJUSTMENT` 는 `lines` 1개 이상: `lineKey` = SKU, `requestedQty` = 움직이는 수량(절대값, ≥ 1), `requestedAmount` = 추정 손실(KRW, 단가 × 감소 수량), `attributes` 권장 `delta`(음수 = 줄어듦)·`lotId`·`locationCode`·`unitPrice`·`priceVersion`·`unpriced`·`reason`. 어긋나면 400 VALIDATION_FAILED. `centerScope: [<mfcId>]` 권장.
  - 확인·사용 내부 API(토큰 없음, `Idempotency-Key`·`X-Source-System` 필수 — 공통 헤더 검사 때문이고 멱등은 본문 `useRef`, 키 응답 재생 없음): `POST /internal/approval-ref-checks`(바꾸지 않음) · `POST /internal/approval-ref-uses`(확인 + 한 번 사용). 본문 `{approvalId, type, targetService, targetRef, actorId?, useRef}`(uses 는 useRef 필수). 응답 늘 200 `{usable, reason?, …승인 단위(lines·approverId·requesterId·effectiveFrom·expiresAt), use?}`. reason = `NOT_FOUND`·`TYPE_MISMATCH`·`TARGET_MISMATCH`·`NOT_APPROVED`·`SELF_APPROVAL`(actorId = 승인자)·`ALREADY_USED`·`NOT_YET_EFFECTIVE`·`EXPIRED`. 한 승인은 한 번만 — 같은 `useRef` 는 같은 결과(재시도 멱등), 다른 useRef 는 ALREADY_USED. 쓸 수 없는 판정은 기록하지 않는다(승인 뒤 같은 useRef 로 다시 오면 다시 판정).
- **PC 3 이 할 것**(mfc-inventory·mfc-quality·mfc-capacity — `ApprovalRefs` 는 PC 3 영역이라 PC 1 은 손대지 않음):
  1. **기준 판정**(Q-11): 한 건(실사 세션·폐기 요청·정정·격리 해제 한 건)의 손실 = Σ(감소 수량 × 단가) **≥ 100,000원** 또는 한 SKU 수량 **≥ 20개** → 본사 승인 필요. 단가는 pricing `GET /internal/prices?skus=`(최대 200건, `basePrice` = 판매 기준가 — 원가가 아님, 아래 확인 필요). `notSet`(단가 없음)이면 미평가 → **본사 승인 쪽으로**(BO-16 "평가액은 근거가 없으면 미평가, 임의 금액으로 승인선을 고르지 않는다"). 기준 값은 설정으로(코드 상수 금지, 미설정이면 CONFIG_REQUIRED).
  2. **기준 이상이면** 센터 관리자 승인 명령을 `422 APPROVAL_REQUIRED` 로 막고 details 에 만들 변경 요청 본문(위 형식 — pricing 의 `approvalRequest` 처럼)을 준다. MFC 백오피스가 그 본문으로 approval-service `POST /change-requests` + `/submissions`(센터 관리자 토큰), 본사 `INVENTORY_APPROVER` 가 승인함에서 승인 → 센터 관리자가 그 `approvalId` 를 approvalRef 로 다시 보낸다. 기준 아래면 지금처럼 센터 관리자 승인만(approvalRef 확인 없음 — RECORD_ONLY 관문도 거치지 않게).
  3. **집행 때 확인·사용**: 자기 변경을 확정하기 **전에** `POST /internal/approval-ref-uses` — `useRef` 는 집행마다 결정적으로(예 `mfc-inventory-service:COUNT_ADJUSTMENT:<sessionId>`), `actorId` = 집행 운영자. `usable=false` 면 422 APPROVAL_REQUIRED(reason 을 그대로 사유로). `lines` 와 자기 집행을 비교(SKU 별 수량이 승인 수량 이하 — 넘으면 거절, 재심사는 새 승인). 호출은 `Downstream.call`(시간 초과 = UNKNOWN_RESULT — 같은 useRef 로 다시 부르면 결과를 안다).
  4. **`ApprovalRefs` 전환**: `pmart.approval-ref.mode` 를 `RECORD_ONLY | VERIFY` 로 — VERIFY = 위 3, RECORD_ONLY 는 LAN 시험용으로만. 운영 제한 해제(`RestrictionService.lift`)는 기준 없이 본사 승인(`MFC_OPERATION_RESTRICTION`, 아래 확인 필요). mfc-inventory `QualityPostingService` 의 approvalRef 는 mfc-quality 내부 승인 ID 라 approval-service 확인 대상이 아니다(호출자 mfc-quality 가 이미 확인) — 그 `requireUsable()` 정리.
  5. **설정**: 전환이 끝나면 `application-lan.yml`·`application-local.yml` 의 `pmart.approval-ref.mode: RECORD_ONLY` 를 VERIFY 로(PC 1 에 요청 — 설정 저장소 공용).
  6. (선택) 집행 결과를 `ApplicationReceiptRecorded`(platform.approval-receipt.v1, ADR-0039)로 회신하면 승인함 실행 축이 APPLIED 로 바뀐다 — 사용만으로는 실행 축을 바꾸지 않는다.
- **회신(PC 3, 2026-11-17 — 브랜치 `mfc/approval-ref-check`, mfc-inventory 0.22.0·mfc-quality 0.3.0·mfc-capacity 0.16.0)**: 1~5 구현, 6(회신 사건)은 안 함.
  - **사용자 확인(2026-11-17)**: ① 운영 제한 해제 = **기준 없이 늘 본사**(OPERATOR_ADMIN) ② 격리 해제도 **같은 기준**(해제 수량 × 단가 — 손실은 아니지만 같은 판정) ③ 단가 = 판매 기준가(원가 없음) — 그대로. ④ 유효기간은 approval-service 몫이라 손대지 않음.
  - **판정**(`domain/HqApprovalRules` — inventory·quality 같은 코드): SKU 별 움직이는 수량(|증감| 합) ≥ `pmart.approval-ref.hq-sku-qty` 또는 Σ(감소 수량 × 판매 기준가) ≥ `pmart.approval-ref.hq-loss-krw` → 본사. 감소가 있는데 단가가 없으면(pricing `notSet`) 미평가 → 본사. 실사 조정 = 차이 |증감| 합·순감소, 정정 = 총량 불변이라 수량 기준만(단가 조회 안 함), 폐기 = 승인 수량, 해제 = 해제 수량. 기준 값 미설정이면 CONFIG_REQUIRED.
  - **기준 이상·번호 없음** → 422 APPROVAL_REQUIRED. 공통 오류 형식(`libraries/common-web` — PC 1 영역이라 안 바꿈)이 details 를 (field, reason) 문자열 쌍으로만 담아서, **`details[field=approvalRequest].reason` = 변경 요청 본문 JSON 문자열**(type·targetService·targetRef `<mfcId>:<kind>:<ref>`·centerScope·allowedActions `POST_<kind>`/`LIFT_OPERATION_RESTRICTION`·summary·lines(lineKey = SKU, requestedQty, requestedAmount(단가 있을 때), attributes delta·unitPrice·priceVersion·unpriced·lotId·locationCode)), `field=threshold` = 걸린 기준(사람용). 백오피스가 reason 을 JSON 으로 읽어 `POST /change-requests` 에 그대로.
  - **ref**: COUNT_ADJUSTMENT = 실사 세션 ID, CORRECTION = **정정 요청의 Idempotency-Key**(정정은 실행 전 ID 가 없다 — 승인 뒤 같은 키로 다시 보낸다, 422 는 기록되지 않아 같은 키 재사용 가능), DISPOSAL = 폐기 요청 ID, QUALITY_RELEASE = 품질 판정 ID, RESTRICTION_LIFT = 운영 제한 ID.
  - **번호 있음** → 집행 전 `POST /internal/approval-ref-uses`(useRef `<서비스>:<kind>:<ref>`, actorId = 집행 운영자, `Downstream.call`) → usable=false 면 422 APPROVAL_REQUIRED(details `field=approvalRef` reason = NOT_APPROVED 등), SKU 수량이 승인(approvedQty, 없으면 requestedQty)보다 많거나 승인에 없는 SKU 면 422(reason OUT_OF_SCOPE). 기준 아래면 번호 확인 없이 기록만(없으면 `CENTER:<ref>` — 사건 approvalRef.approvalId 가 필수라). 정정 `approvalRef`·격리 해제 approvalRef(새 칸)·운영 제한 해제 query approvalRef 는 선택으로 바꿈.
  - **모드** `pmart.approval-ref.mode`: `VERIFY` = 위, `RECORD_ONLY` = 예전(번호 필수·확인 없음, 격리 해제는 예전처럼 번호 없이), 그 밖 CONFIG_REQUIRED. mfc-inventory `QualityPostingService` 의 관문은 없앰(mfc-quality 내부 승인 — mfc-quality 가 확인).
  - 외부 호출 위치: 실사 승인·폐기 승인·운영 제한 해제는 그 행을 잠근 트랜잭션 안(같은 대상만 기다림, 응답 5초 상한), 정정·격리 해제는 잠금 전.
  - **PC 1 에 요청(설정 저장소 공용)**: ① `pmart.approval-ref.hq-loss-krw: 100000`·`hq-sku-qty: 20`(inventory·quality — 공통이면 `application.yml`) ② 주소 `pmart.approval-ref.approval-base-url`(inventory·quality·capacity)·`pricing-base-url`(inventory·quality) — LAN·local ③ 그 뒤 `pmart.approval-ref.mode: RECORD_ONLY` → `VERIFY`(`application-lan.yml`·`application-local.yml`). 순서: 이 PR 배포 → ①② → ③. ③ 전에는 예전 동작 그대로.
  - 시험(PC 3): inventory 단위 61(판정 6·관문 5)·통합 31, quality 단위 14·통합 4, capacity 단위 49(Docker — OR-Tools DLL 이 이 PC 에서 막힘), 통합 31 중 28 통과·`AssignmentPlanIT` 3 은 같은 DLL 차단(배정 추천 500 — 이 변경과 무관, PC 1/Linux 에서 확인 부탁).
- **확인 필요(사용자)** → 위 회신에서 ①②③ 정함(2026-11-17): ① 운영 제한 해제 승인자 = `OPERATOR_ADMIN`(본사 운영)으로 둠 — BO-08 은 "필요한 승인 후 재개·본사 승인 완료"만 적고 역할을 정하지 않음. 기준 없이 늘 본사인지도. ② 격리 해제(QUALITY_RELEASE)도 같은 기준·`INVENTORY_APPROVER` 로 둠 — BO 역할표는 품질 승인자와 재고/손실 승인자를 나눈다. ③ 손실 금액의 단가 = 판매 기준가(pricing `basePrice`) — 원가가 없다. ④ 본사 승인의 기본 유효기간(지금은 승인자가 `expiresAt` 을 주지 않으면 만료 없음).

## AP-2. 직원 권한 요청 유형(BO-01)·hr-service 자동 제출·자동 승인 규칙 (2026-11-12 기록, ADR-0056)

> **2026-11-13 구현(ADR-0056 5-4, LAN 배포 전)**: 유형은 계약에 이미 있던 `GRANT`(정본 `employee-auth-service`, 원객체 = 운영자 ID). 제출 경로는 내부 API 대신 **사건** `GrantRequested`(hr-service outbox → platform.employee.v1 → approval-service `employeeEvents`) — hr-service 재시도 장치 없이 등록과 같은 트랜잭션. 자동 승인 규칙 표 `pmart.approval.auto-approval-rules`(설정 저장소, 승인자 = `RULE:<id>@v<버전>`, 행위자 SYSTEM), 맞는 행과 나머지 행은 승인을 나눈다(부분 승인 없음). 수동 심사 역할 `approver-roles.GRANT = OPERATOR_ADMIN`. 반영은 employee-auth-service(`employee_grant`, `GrantApplied`, 회신 `ApplicationReceiptRecorded`), 결과 표시는 hr-service. 남은 것: 같은 직원에 심사 중인 권한 승인이 있으면 새 요청이 DUPLICATE_APPROVAL(지금은 등록 때 한 번만 와서 생기지 않음) — 권한 변경 요청 화면(BO-01 변경·회수)을 만들 때 정한다.

- **요청한 곳**: hr-service(새, 구현 전), employee-auth-service(새, 구현 전).
- **필요한 것**
  1. 권한 요청 승인 유형(BO-01: 역할·권한 센터·기간·근거 행 단위) — 지금 `ApprovalRules.targetType` 에 없다.
  2. hr-service 가 직원 등록 때 요청 권한을 **권한 요청으로 자동 제출**하는 내부 경로(요청자 = 등록한 인사팀 직원, 서비스 토큰).
  3. **자동 승인 규칙**: 요청 역할이 `MFC_WORKER` 이고 권한 센터 = 등록한 소속 센터일 때만 심사 없이 승인, 승인자 = 규칙(ID·버전)으로 기록. 그 밖은 승인자 심사. 규칙 표는 approval-service 설정(바꿀 때 기록).
  4. 승인 결과(`ApprovalDecided`)의 반영 대상을 auth-service → **employee-auth-service**(직원 권한 정본)로. 반영 확인 `GrantApplied` 도 employee-auth-service 가 발행.
- **지금(임시)**: 없음 — 직원 역할은 Keycloak Realm 역할을 관리 화면에서 직접 넣는다(ADR-0054 결정 L, 전환 때까지).
- **요청한 쪽에서 바꿀 것**: hr-service 등록 화면이 요청 권한을 받아 제출, employee-auth-service 가 `ApprovalDecided` 구독·grant 저장.
- **사용자 결정**: 정함(2026-11-12 — 등록 화면에서 함께 받기 "1번을 하자", 자동 승인 "소속 센터 현장 역할만").
- 근거: `docs/adr/0056-staff-login-employee-auth-service-and-hr-service.md` 결정 5·6, `docs/architecture/auth/employee-auth-structure.md` §8-1, 업무 원문 BO-01.

## AP-3. 승인 상세·목록에 "지금 보는 사람이 결정할 수 있는지" (2026-11-18 기록, 보낸 영역 backoffice — PC 2)

- **요청한 곳**: 백오피스 웹(pmart-clients `docs/handoff/backoffice/2026-11-18-backend-approval-can-decide.md`) — PC 2 가 옮겨 적음.
- **왜**: 승인함에 직원 권한(GRANT) 요청도 보이고 승인·반려 버튼이 나오는데, GRANT 심사 역할은 설정 `approver-roles.GRANT = OPERATOR_ADMIN` 이라 다른 승인자(PRODUCT_APPROVER 등)가 누르면 403. 화면은 설정을 모르고 판정을 만들면 안 되므로 눌러 본 뒤에야 안다. `Approval.allowedActions` 는 요청에 정해진 결정 종류라 "보는 사람"의 권한이 아니다.
- **필요한 것**
  1. **(R1)** `GET /approvals/{approvalId}` 응답에 지금 토큰의 사람이 지금 결정할 수 있는지 — 예 `viewerCanDecide: boolean` + 못 하면 `viewerDecideBlockedReason`(NO_ROLE·SELF_APPROVAL·GRANT_TO_SELF·NOT_PENDING·CONFIG_REQUIRED 등). 판정은 결정 API 와 같은 검사를 그대로 — `ApprovalService` 결정 경로의 `ApprovalRules.requireUnderReview`·`requireApprover`(자기 요청·역할·설정 없음)·`requireNotOwnGrant` 를 예외 대신 이유로 돌려주는 형태.
  2. **(R2, 선택)** `GET /approvals` 목록에도 같은 값, 또는 `decidableByMe=true` 거르기 — 승인자 첫 화면 "심사 대기 N건"이 자기가 못 하는 건까지 센다(백오피스 대시보드 요청 D4 의 승인 건수도 이 값을 쓰면 좋음).
- **지금(임시)**: 화면은 버튼을 두고 서버 403 을 거절 문구로 보인다(판정을 지어내지 않음).
- **받으면 화면은**: `viewerCanDecide=false` 면 "○○ 역할이 심사해요" 안내·버튼 끔, 심사 대기 수를 그 값으로.
- **사용자 결정**: 필요 없음(판정 규칙은 지금 결정 API 그대로).
- **구현(2026-11-18, PC 1 — approval 0.4.0)**: `GET /approvals/{id}`·`GET /approvals` 항목에 `viewerCanDecide`·`viewerDecideBlockedReason`(NOT_PENDING·SELF_APPROVAL·CONFIG_REQUIRED·NO_ROLE·GRANT_TO_SELF — 결정 API 와 같은 검사 `ApprovalRules`), 목록 거르기 `decidableByMe=true`. 시험 `ApprovalRulesTest`·`GrantRequestIT`.

## AP-4. CDC 재고 조정 승인 종류 `CDC_STOCK_ADJUSTMENT` (2026-11-25 기록, 보낸 영역 cdc — D 클론 cdc 세션)

- **요청한 곳**: cdc-inventory 재계수·실사 조정(설계 `docs/cdc/design/2026-11-25-count-adjustment.md`, WMS CDC-F-029·030).
- **왜**: 사용자 결정(2026-11-25) "MFC 와 같은 본사 승인 기준" — 한 건 손실 10만 원 이상 또는 한 SKU 20개 이상이면 본사 승인 번호를 approval-service 로 한 번 사용·범위 확인한다. 지금 종류는 `MFC_STOCK_ADJUSTMENT`(대상 mfc-inventory·mfc-quality)뿐이다.
- **필요한 것**
  1. 종류 `CDC_STOCK_ADJUSTMENT` — 대상 서비스 `cdc-inventory-service`, targetRef `<cdcId>:COUNT_ADJUSTMENT:<countSessionId>`(kind 는 처음엔 COUNT_ADJUSTMENT 하나), 재고 행(SKU·수량 ≥ 1) 검사는 MFC 와 같게(`domain/MfcTargets` 같은 CDC 판).
  2. `approver-roles.CDC_STOCK_ADJUSTMENT: INVENTORY_APPROVER`(MFC 와 같은 역할 — 다르게 할지는 사용자).
  3. `/internal/approval-ref-checks`·`/internal/approval-ref-uses` 가 이 종류도 받기(useRef = `cdc-inventory-service:COUNT_ADJUSTMENT:<id>`).
- **지금(임시)**: cdc-inventory 는 `pmart.inventory.approval-ref.mode: RECORD_ONLY`(번호 필수, 확인 없음)로만 돈다. VERIFY 는 이 요청이 끝난 뒤.
- **함께 PC 1 몫**: pmart-config `cdc-inventory-service.yml` 에 `pmart.inventory.approval-ref.hq-loss-krw: 100000`·`hq-sku-qty: 20`(사용자 "MFC 와 같게"), lan 은 처음엔 `mode: RECORD_ONLY`.
- **사용자 결정**: 기준값 — 받음(MFC 와 같게). 승인 역할 — MFC 와 같다고 가정(다르면 알려 주기).
