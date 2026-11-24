# hr-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `hr-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.
결정: ADR-0056(결정 5·7·8·10·12·16). 흐름: `docs/business/flows/employee-account-provisioning.md`.

## 계약
- REST: `contracts/openapi/hr-service.yaml` → `com.pmart.hr.api.generated`. 사건: `contracts/events/EmployeeRegistered·Suspended·Reinstated·Left.v1.json`·`GrantRequested.v1.json`(토픽 `platform.employee.v1`, 키 = 운영자 ID). 구독: `ApprovalDecided`(platform.approval.v1, type GRANT만 — 바인딩 `approvalEvents-in-0`). 부르는 쪽 계약: `contracts/openapi/mail-provisioner.yaml`, `employee-auth-service.yaml` 내부 조회(0.11.0 — 권한 요청 검사).
- 계약을 먼저 고치고 코드를 맞춘다. 사건에 이름·역할을 싣지 않는다(이름은 구독자가 쓰지 않는 개인정보, 권한 정본은 employee-auth-service).

## 패키지
`com.pmart.hr.{api, application, infrastructure}`. Controller 에 업무 규칙 금지.

## 데이터·규칙
- 스키마는 `db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 비밀번호(Keycloak·사서함)를 받지도 저장하지도 로그하지도 않는다. 사서함 초기 비밀번호는 `mailbox-password-resets` 응답에만(멱등 기록에도 남기지 않으려고 `RequestIdempotency` 를 쓰지 않는다).
- 개인정보는 이름만. 행·메일 ID·사번을 지우지 않는다(퇴사자 메일 ID 재사용 금지). 사번 `P<연도>-<5자리>`(Asia/Seoul 연도, `employee_no_counter` 원자 증가).
- 모든 명령·목록은 `HR_ADMIN`(D3), 본인 조회(`/hr/employees/me`)와 표시 조회(`/hr/employee-labels`)만 모든 직원.
- 상태 변경 명령은 `RequestIdempotency` + `X-Expected-Version`(row_version).

## 본사 부서 코드 목록(2026-11-15)
- 표 `hq_department`(code PK·name·updated_by, V3 — 배포 때 기존 직원의 HQ_DEPARTMENT 코드로 채움, 이름 NULL). 소속 코드는 거절하지 않고, 등록·소속 변경이 HQ_DEPARTMENT 의 처음 보는 코드를 같은 트랜잭션에 더한다(`HrStore.ensureDepartment`, ON CONFLICT DO NOTHING).
- `GET /hr/departments`(코드 순, employeeCount = 퇴사 제외), `PUT /hr/departments/{code}`(표시 이름, 없으면 만듦 — `RequestIdempotency`, 버전 검사 없음). 모두 HR_ADMIN. 부서를 지우는 API 는 없다.

## 내부 이름 조회(0.5.0, 2026-11-17)
- `GET /internal/employee-names?operatorIds=…`(`InternalController` → `EmployeeService.names` → `HrStore.names`) — employee-auth-service 가 직원 조회 응답의 승인자·회수자 이름을 붙인다(사용자 결정 2026-11-17). 토큰 없는 내부 호출(게이트웨이가 `/internal/**` 를 막음), 1~200개, 형식 틀림 400, 퇴사자 포함, 모르는 ID 는 빠짐, 이름 말고는 싣지 않는다. 이름은 로그 금지. 시험 `HrProvisioningIT.internalEmployeeNames`.

## 표시 조회(0.8.0, 2026-11-19 — 클라이언트 operator-display-names R1, 사용자 "너가 말한대로")
- `GET /hr/employee-labels?operatorIds=…`(`LabelsController` → `EmployeeService.labels` → `HrStore.labels`) — 백오피스·인사팀 백오피스가 처리자·요청자 칸의 OP-… 를 "이름 · 사번"으로. 직원 토큰이면 누구나(역할 제한 없음), 이름은 가리지 않는다. 1~50개, 형식 틀림·51개 400, 퇴사자 포함, 모르는 ID 는 빠짐, 이름·사번 말고는 싣지 않는다. 이름은 로그 금지. 게이트웨이 허용 목록 GET 한 줄(라우트는 `/api/v1/hr/**` 그대로). 시험 `HrProvisioningIT.employeeLabels`.

## 계정 발급·상태 맞추기
- `ProvisioningWorker`: 행 잠금(SKIP LOCKED) 한 명씩, 단계 REQUESTED → MAILBOX_CREATED → IAM_CREATED → INVITE_SENT → DONE. 외부 호출 실패는 예외를 트랜잭션 밖으로 내지 않고 기록(같은 단계, 지수 대기). 영구 거절·횟수 소진이면 보상. 등록·재시도 직후 `kick()` 으로 바로 한 번.
- `IamSyncWorker`: 재직 상태 → Keycloak enabled·세션 로그아웃·사서함 활성/비활성. `ContractEndJob`: 한 시간마다 계약 종료 자동 정지.
- Keycloak 은 `KeycloakAdminClient`(hr-provisioner, 사용자 경로만 — **역할·그룹 매핑 API 를 부르지 않는다**, U-11). 사용자 표현을 바꿀 때는 전체를 읽어 enabled 만 바꿔 쓴다(속성이 지워지지 않게).
- 설정이 없으면 클라이언트 빈이 없고(로컬) 발급은 CONFIG_REQUIRED 로 남는다.

## 요청 권한(2026-11-13, ADR-0056 5-4)
- 등록 때 받은 요청 권한(`requested_grant`, PENDING)은 발급이 DONE 이 되는 트랜잭션에서 `GrantRequested`(요청 ID `GR-…`, 요청자 = `registered_by`, 행 키 = `grant_id`, 비운 기간 = 근무 기간)로 올리고 SUBMITTED. 재직 중일 때만.
- `GrantDecisions`: `ApprovalDecided`(GRANT) 행 → APPROVED·REJECTED(+ `approval_ref`). 화면 표시용 — 효력은 employee-auth-service.
- **재직 중 권한 추가·변경 요청(0.3.0, 2026-11-15 BO-01)**: `POST /hr/employees/{id}/grant-requests`(`EmployeeService.requestGrants`) — 재직 중·발급 DONE 직원만, 자기 권한 403, PENDING·SUBMITTED 행이 있으면 409 `GRANT_REQUEST_PENDING`(approval 이 대상마다 열린 승인 하나 — DUPLICATE_APPROVAL 을 미리 막음). 행 넣고 같은 트랜잭션에서 `GrantRequested`(요청자 = 요청한 인사팀 직원), 변경 기록 `GRANT_REQUESTED`. 회수는 employee-auth-service(즉시).
- **권한 요청 검사(0.6.0, 2026-11-17 HR-2 — `service-requests/hr-service.md`)**: `application/GrantRequestRules` — 등록 `requestedGrants`·권한 요청 `grants` 모두. 같은 역할 두 줄 400(멱등 기록 전), 모르는 역할·센터 규칙(센터 지정 역할은 1개 이상, 아닌 역할은 센터 없음) 400(`…[i].role`·`…[i].centers`), 권한 요청만 이미 유효·예정인 같은 권한(역할 + 센터 겹침 — 센터 지정이 아닌 역할은 센터와 상관없이 + 기간이 지금 이후로 겹침, 비운 기간 = 근무 기간) 409 `GRANT_ALREADY_ACTIVE`(details `grants[i]` = `existingGrantId=…;…;extension=…` — 기간 연장도 409, 연장은 끝나는 날부터 새 요청 — HR-2 ① 2026-11-18). 역할 목록·지금 권한은 employee-auth-service 가 정본 — `infrastructure/EmployeeAuthClient` 가 `GET /internal/roles`·`/internal/employees/{id}/grants?activeOrFuture=true`(토큰 없음, 앱 공용 `RestClient.Builder` — 연결 1초·응답 5초)를 **멱등 기록 뒤·직원 행 잠금 전**에 부른다(재생은 부르지 않음). 못 읽으면 503 `DEPENDENCY_UNAVAILABLE` — 검사 없이 받지 않는다(요청 권한 없는 등록은 부르지 않음). 주소 `pmart.hr.employee-auth-base-url`(기본 `http://employee-auth-service:8511`, local 은 설정 저장소에 `http://localhost:8511`). employee-auth-service 도 반영 때 같은 규칙으로 막는다(안전망). 시험 `HrProvisioningIT.grantRequestValidation`(employee-auth WireMock)·`GrantRequestRulesTest`.
- **역할·센터 섞임(0.7.0, 2026-11-18 — permissions.md §9 큰 것 2)**: `GrantRequestRules.checkCenterMix` — 요청 행마다 기간이 지금 이후로 겹치는 지금 grant·같은 요청 다른 행과 묶어, 센터 지정 역할 하나하나의 센터가 센터 전체와 다르면(A 관리자 + B 작업자 등) 지금 grant 와 섞이면 409 `CENTER_ROLE_MIX`(details `grants[i]` = `existingGrantId=…;role=…;centers=…`), 요청 행끼리만이면 400(등록 `requestedGrants[i]`도). 시험 같은 두 곳.
- **계정에 직접 둔 역할도 중복(2026-11-18 HR-2 ③)**: employee-auth 0.12.0 내부 grant 조회의 `account`(역할·센터)를 `EmployeeAuthClient.activeOrFutureGrants` 가 grantId `account`·기한 없음 줄로 바꿔 넣는다(역할 없이 센터만이면 역할 "" 한 줄) — 중복 409(`existingGrantId=account;…;validFrom=none`)·섞임 409 모두 계정 값을 본다. 시험 `GrantRequestRulesTest.accountRoleIsDuplicate·accountCentersCountForMix`, IT `grantRequestValidation`(account 칸 스텁).

## 시험
```bash
./gradlew :services:hr-service:check   # 단위 + 통합(PostgreSQL·Kafka·Keycloak 26.8.1·Mailpit 컨테이너, mail-provisioner WireMock)
```
- `HrProvisioningIT`: 등록 → 사서함 → Keycloak 사용자 → 실제 메일 도착(Mailpit) → EmployeeRegistered, 사서함 대기, 영구 거절 보상, 요청 권한 제출(GrantRequested)·승인 결과 반영, 정지·퇴사·복귀 반영, 계약 종료, 비밀번호 미저장, 본사 부서 코드 목록(`hqDepartmentList`), **U-11(manage-users 로 역할 매핑 가능 = 204)**.
- 시험 Realm 에는 `hr-provisioner` 의 `realm-management` 역할을 `clientScopeMappings` 로도 준다(fullScopeAllowed false — 없으면 403). LAN Realm 도 같다.
- 로컬 포트 8512. LAN PC 5.
- **이름 문자 검사(0.5.1, 2026-11-17)**: 등록 이름에 Keycloak firstName 규칙(person-name-prohibited-characters)이 막는 문자 `< > & " $ % ! # ? § ; * ~ / \ | ^ = [ ] { } ( )`·제어 문자가 있으면 400(field name, `EmployeeService.NAME_PROHIBITED`). 전에는 등록은 되고 발급 2단계가 `KEYCLOAK_400` 으로 실패·사서함 되돌림(2026-11-16 PC 2 "[시험] …"). Keycloak 400 응답 본문은 저장하지 않으므로 다른 400 은 Keycloak 사용자 프로필 규칙부터 본다.
- **인사 대시보드(2026-11-19, 계약 0.9.0, 마이그레이션 없음 — 인사 백오피스 H-D1·H-D2, 사용자 "응 해줘")**: `GET /hr/employees` 거르기 `provisioningStep`(쉼표)·`grantSubmission`·`endDateFrom/To`·`createdFrom/To`·`sort`(CREATED_DESC 기본 / END_DATE_ASC — 종료일 없는 직원 제외, 커서 `종료일|사번`) — `HrStore.ListFilter`, 검사는 `EmployeeService.list(ListFilter, …)`. `GET /hr/employees/counts?endingWithinDays=`(1~90, 기본 7): 상태별·발급 실패(FAILED·COMPENSATED)·승인 대기(SUBMITTED, 퇴사자 제외)·종료 임박(ACTIVE, 오늘~오늘+N−1 Asia/Seoul) — 값마다 `{value, complete}`(DB 집계라 늘 true). HR_ADMIN 만. 시험 `HrProvisioningIT.dashboardFiltersAndCounts`.
- **사번으로 찾기(2026-11-24 HR-5, 계약 0.10.0)**: `GET /hr/employee-labels?employeeNos=`(반복·쉼표, 1~50, `EmployeeService.labels(operatorIds, employeeNos)` — 둘 중 하나만, `HrStore.labelsByEmployeeNo`). 권한·응답은 0.8.0 그대로, 퇴사자 포함. **0.11.0 `activeOnly=true`** = 퇴사자(LEFT) 뺌(정지는 남김), 두 조회 모두.
- **이름표 상태(2026-11-24 HR-6, 계약 0.12.0 — 통합백오피스 프론트 요청, 사용자 "확인해봐 없으면 바로 시작해", PC 2 구현)**: `employeeNos` 로 부를 때만 줄마다 `status`(ACTIVE·SUSPENDED·LEFT — `HrStore.labelsByEmployeeNo`), `operatorIds` 로는 null(화면 표시용 이름표로 정지 여부가 퍼지지 않게 — 공개 범위를 사람 고르기로 좁힘, PC 1 검토). 시험 `HrProvisioningIT.employeeLabels`.
