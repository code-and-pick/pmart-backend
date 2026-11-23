# hr-service 에 요청

## HR-1 직원 이름 내부 조회 (2026-11-17 구현)

- **요청한 곳**: employee-auth-service(platform, PC 1) — 인사팀 앱 권한 상세의 승인 칸이 이름 대신 `OP-…`(PC 2 확인 2026-11-16, `docs/worklog/backoffice/2026-11-16.md`). 사용자 2026-11-17 "백엔드에서 이름을 같이 준다".
- **필요한 것**: 운영자 ID 여러 개 → 이름. employee-auth-service 는 이름을 저장하지 않으므로(그 서비스 CLAUDE.md 데이터 규칙) 정본 hr-service 에서 읽는다.
- **만든 것**: `GET /api/v1/internal/employee-names?operatorIds=…`(계약 hr-service 0.5.0, 토큰 없는 내부 호출, 1~200개, 퇴사자 포함, 모르는 ID 는 빠짐) → `{items: [{operatorId, name}]}`. employee-auth-service 0.10.0 이 `grants[].approverName`·`revokedByName` 에 쓴다(실패면 null).
- **요청한 쪽에서 바꿀 것**: 같은 커밋에서 했다(`EmployeeNames`).
- **사용자 결정 필요**: 없음.

## HR-2 권한 요청 검사 — 중복 부여·센터 규칙 (2026-11-17 기록·백엔드 구현, platform PC 1 — 사용자 수락)
- **문제(2026-11-17 확인)**: `POST /hr/employees/{id}/grant-requests`(그리고 등록 때 `requestedGrants`)는 "결정 안 된 요청이 있으면 409 `GRANT_REQUEST_PENDING`"만 본다.
  - 이미 유효한 **같은 역할·센터가 겹치는·기간이 겹치는** 권한을 또 요청할 수 있다 → 승인되면 employee-auth `employee_grant` 에 줄이 하나 더 생김(회수 때 한 줄만 회수하면 권한이 남는 위험, 목록 중복, `GrantEnded` 가 줄마다).
  - 한 요청 안에 같은 역할을 두 줄 넣을 수 있다.
  - 센터 지정 역할(`role-info.centerScoped` — `MFC_SUPERVISOR`·`MFC_WORKER`)에 센터가 없거나, 센터가 필요 없는 역할에 센터가 있어도 받는 것으로 보인다(구현 때 확인).
  - 인사팀 앱 화면(`web/hr-backoffice` 권한 관리)도 막지 않는다 — 지금 권한을 보여 주기만.
- **사용자 결정(2026-11-17)**: 제안 수락 — "백엔드부터 일단 내쪽(PC 1)에서 작업하고 클라이언트한텐 그 요청서로 보내자".
- **할 것(PC 1)**:
  1. hr-service 권한 요청 검사: 같은 요청 안 중복 역할 400, 센터 규칙(`centerScoped` 역할은 센터 1개 이상·아닌 역할은 센터 없음) 400, 이미 유효한 같은 권한(역할 같고 센터·기간 겹침) 409 `GRANT_ALREADY_ACTIVE` — 지금 권한은 employee-auth 에 묻는다(내부 조회). 역할 목록·`centerScoped` 도 employee-auth 설정을 정본으로.
  2. employee-auth 반영 안전망(`GrantApprovalHandler`): 승인된 줄이 이미 유효한 같은 권한과 겹치면 새 줄을 만들지 않고 기록만.
  3. 그 뒤 인사팀 앱 요청서(PC 2, `docs/handoff/clients/hr-backoffice/`): 센터가 필요 없는 역할이면 센터 칸 숨김·끔, 센터 지정 역할이면 센터 필수, 이미 가진 권한 표시.
- **정할 것**: 이미 있는 권한의 **기간 연장**(종료일 늘리기)을 새 요청으로 받을지, "연장" 요청으로 구분할지. (그 전까지는 겹치면서 더 늦게 끝나는 요청도 409 `GRANT_ALREADY_ACTIVE` — `extension=true`, 메시지 "기간 연장은 아직 별도 기능이 없습니다(결정 대기)".)
- **구현(2026-11-17, PC 1 — 백엔드 끝, LAN 배포 전)**:
  - hr-service **0.6.0**(`application/GrantRequestRules`, `infrastructure/EmployeeAuthClient`, `EmployeeService.register·requestGrants`): 같은 역할 두 줄 400(field `grants`·등록은 `requestedGrants`), 모르는 역할 400(`grants[i].role`), 센터 규칙 400(`grants[i].centers` — 센터 지정 역할은 1개 이상, 아닌 역할은 센터 없음), 이미 유효·예정인 같은 권한 409 `GRANT_ALREADY_ACTIVE`(역할 같음 + 센터 겹침 — 센터 지정이 아닌 역할은 센터와 상관없이 + 기간이 지금 이후로 겹침, details `grants[i]` = `existingGrantId=…;role=…;centers=…;validFrom=…;validUntil=…|none;extension=…`). 기존 권한이 끝난 날부터 시작하는 요청·다른 센터는 받는다. 역할 목록·지금 권한은 employee-auth-service 내부 조회 — 못 읽으면 503 `DEPENDENCY_UNAVAILABLE`(검사 없이 받지 않음, 요청 권한이 있는 등록도). 외부 조회는 멱등 기록 뒤·직원 행 잠금 전(재생은 부르지 않음). 설정 `pmart.hr.employee-auth-base-url`(기본 `http://employee-auth-service:8511`, local 은 설정 저장소 `hr-service-local.yml` 에 `http://localhost:8511`).
  - employee-auth-service **0.11.0**: 내부 조회 `GET /internal/roles`·`GET /internal/employees/{id}/grants?activeOrFuture=true`(`InternalController`, 토큰 없음). 안전망 — `GrantApprovalHandler` 가 같은 규칙(`application/GrantOverlap`)으로 겹치는 승인 행은 grant 를 만들지 않고 반영 회신 `REJECTED`(reasonCode `GRANT_ALREADY_ACTIVE`, domainRef = 기존 grantId)·경고 로그(운영자 ID·역할·grant ID 만), `GrantApplied` 없음. 같은 직원 반영은 advisory lock 으로 한 줄로. 사건 계약은 그대로.
  - 시험: hr `HrProvisioningIT.grantRequestValidation`(employee-auth WireMock)·`GrantRequestRulesTest`, employee-auth `GrantApprovalIT.duplicateApprovedLineDoesNotCreateSecondGrant`·`GrantOverlapTest`.
  - 클라이언트 요청서(PC 2): `docs/handoff/clients/hr-backoffice/2026-11-17-request-grant-request-validation.md`.
- **상태**: 백엔드 구현 끝(배포 전), 인사팀 앱 화면(PC 2) 대기, 기간 연장 결정 대기.
- **추가 결정(2026-11-18 사용자 "그 내용으로 고치고" — 고침: 11-17 줄에 "추천대로"로 적혀 있던 ①③④는 사실 정해지지 않았었다, ②만 11-17 그대로)**: ① 기간 연장은 **인사팀 앱 "연장" 버튼이 끝나는 날부터 시작하는 새 요청을 만든다**(같은 역할·센터, validFrom = 기존 권한이 끝나는 날 — 서버는 이미 받는다, 백엔드 변경 없음, PC 2 화면만). 따로 "연장 요청" 종류는 만들지 않는다. 남는 것: 같은 역할이 앞뒤 두 줄 — 회수 때 둘 다 회수해야 함(나중에 "이어지는 같은 권한도 같이 끝내기"로 보완 가능). ② 센터가 필요 없는 역할은 같은 역할이면 센터와 상관없이 중복(11-17 그대로). ③ **계정에 직접 들어 있는 역할**(`employee_account.roles`, 이전한 13명·관리 경로)도 중복으로 본다 — 같은 역할을 승인 권한으로 또 주면 회수해도 계정 값이 남아 권한이 안 빠지므로. employee-auth 0.12.0 내부 grant 조회에 `account`(역할·센터), hr 0.7.0 이 그 값까지 보고 409 `GRANT_ALREADY_ACTIVE`(`existingGrantId=account`)·`CENTER_ROLE_MIX`, 반영 안전망도 계정에 같은 역할이면 REJECTED(domainRef `account`). 근본 해결(13명 직접 역할을 승인 권한으로 옮기기)은 나중. ④ 안전망이 반영을 거절했을 때 인사팀 앱 "반영 거절" 표시는 **나중**(③까지 하면 요청 단계에서 거의 다 걸러져 동시 승인 경합 정도만 남음 — 생기면 그때 hr 가 반영 회신 구독 + PC 2 화면).

## ~~HR-3 직원 본인 연락처 등록·변경 (2026-11-18, 보낸 영역 mfc — PC 3, 받는 쪽 platform PC 1)~~ — 철회
- **왜**: pmart-clients `docs/handoff/mfc-backoffice/2026-11-18-backend-my-account.md`(R2). 사용자 결정(2026-11-18): **회사 메일(로그인 ID·사서함)은 인사팀만** 바꾸고, 본인은 **연락처(휴대폰·개인 메일)만** 등록·변경.
- **요청**
  1. `EmployeeSelf`(`GET /hr/employees/me`)에 `contactPhone`·`personalEmail`(빈 값 가능)과 `version`.
  2. 본인만: 예 `PUT /hr/employees/me/contact` `{contactPhone, personalEmail}` + `X-Expected-Version`, 빈 값이면 지움. 형식 검사는 서버(400 VALIDATION_FAILED + details).
  3. 쓰임 결정해 회신 — 클라이언트 제안: 인사팀 상세(`GET /hr/employees/{id}`)에 연락용 표시만, 로그인·알림 발송에는 안 씀. 개인정보라 로그·사건에 넣지 않음.
  4. `mailAddress` 는 본인 변경 불가(지금처럼 인사팀 경로).
- **확인함(R3, PC 3 2026-11-18 LAN)**: `claude-mfc-sup`(MFC_SUPERVISOR)·`claude-mfc-wrk`(MFC_WORKER) 토큰으로 게이트웨이 `GET /api/v1/hr/employees/me` → 200(affiliation·employeeNo·employmentType·mailAddress·name·operatorId·status).
- **받는 쪽(mfc-backoffice, PC 3)**: 나오면 "내 정보" 연락처 폼. 웹 생성 목록에 `hr` 추가는 PC 2(`tools/web-tools/gen-api.mjs`)에 클라이언트가 요청.

- **철회(2026-11-18 PC 3, 사용자 확인)**: 잘못 짚은 요청 — "내 정보"의 비밀번호는 로그인 비밀번호가 아니라 MFC 센터 휴게 번호(mfc-capacity 0.20.0 `PUT /mfcs/{mfcId}/break-pin`, 이미 배포)이고, 연락처는 시스템이 쓰지 않는 연락 수단 표시용이라 mfc-capacity 0.21.0 `/mfcs/{mfcId}/staff-contacts`(PC 3)로 만든다. **PC 1 할 일 없음.**

## HR-4 직원 표시(이름 · 사번) 여럿 조회 — 직원 토큰 (2026-11-18, 보낸 영역 rider-backoffice — PC 5, PC 2 가 옮겨 적음, 받는 쪽 platform PC 1)

- **요청한 곳**: 라이더 백오피스 "조치 이력"(pmart-clients PR #155 — rider `/rider-staff-actions`·execution `/delivery-staff-actions`·dispatch `/dispatch-staff-actions` 를 한 표로)과 공지 "보낸 사람" 칸. 각 줄에 운영자 ID(`actorId`)만 온다. 기본 백오피스(bff)의 승인함·처리 대기·접수 건 처리자 칸도 같은 처지.
- **왜**: ADR-0070 직원 표시 = **이름 · 사번**(`홍길동 · P2026-00001`), 운영자 ID(OP-…)는 처리자·결정자 칸에도 내지 않는다(ADR-0070 결과 "HR 조회가 없으면 PC 1(HR)에"). 지금 있는 HR-1 `GET /internal/employee-names` 는 **토큰 없는 서비스 간 내부 경로**라 웹이 못 부르고, 사번도 싣지 않는다.
- **필요한 것**: 직원 토큰 조회 — 예 `GET /employees/display?operatorIds=OP-1&operatorIds=OP-2`(1~50개, 같은 값 한 번) → `{items: [{operatorId, name, employeeNo}]}`. 퇴사자 포함(지난 기록 표시), 모르는 ID 는 빠짐(오류 아님), 메일·전화·소속 같은 다른 인사 정보는 싣지 않음. 권한: OPERATOR_ADMIN·RIDER_OPS(라이더 백오피스), 가능하면 CS_AGENT(접수 건 처리자 칸) — 정하는 것은 PC 1(직원 이름 노출 범위가 걸리면 사용자 결정).
- **지금(임시)**: 화면은 actorId 그대로(또는 끝 8자리 회색).
- **받으면**: 라이더 백오피스가 바로 붙임. 기본 백오피스는 PC 2 가 bff 화면 응답(승인함 requestedBy 등)에 붙일지 검토.
- **회신(2026-11-19 PC 1)**: 구현·LAN 배포 — hr-service 0.8.0 **`GET /api/v1/hr/employee-labels?operatorIds=…`**(요청 예시 경로 대신 — 기존 `/hr/**` 라우트 안). 응답 `{items: [{operatorId, name, employeeNo}]}`(요청과 같은 모양), 1~50개(51개·형식 틀림 400), 퇴사자 포함, 모르는 ID 빠짐. **직원 토큰이면 누구나**(RIDER_OPS·CS_AGENT·MFC 역할 포함, 사용자 2026-11-19), 이름 안 가림. 이미지 ddfaea6e. 클라이언트 알림 `docs/handoff/clients/backoffice/2026-11-19-reply-code-names-and-operator-labels.md`·`docs/handoff/clients/rider-backoffice/2026-11-19-notice-employee-labels.md`. bff 화면 응답에 싣는 것은 PC 2 BF-1.

## HR-5. 사번으로 운영자 찾기 — `GET /hr/employee-labels?employeeNos=` (2026-11-24, 보낸 영역 backoffice — PC 2, 통합백오피스 프론트 요청 pmart-clients `docs/handoff/backoffice/2026-11-24-backend-employee-by-number.md` #443)
- **왜**: 교대 인계 "후임"·접수 건 "다른 사람에게 맡기기" 칸에 운영자 시스템 ID(OP-…)를 직접 넣게 되어 있는데 직원은 이 ID 를 모르고 **이름·사번**만 안다. hr 에는 ID → 이름·사번(`employee-labels`, HR-4)만 있고 반대 방향이 없다. `/hr/employees` 검색은 HR_ADMIN 전용.
- **요청**: `GET /hr/employee-labels` 에 `employeeNos=P2026-00012,…`(최대 50, operatorIds 와 둘 중 하나) 거르기.
  - 응답은 지금 모양 그대로 `{items:[{operatorId, name, employeeNo}]}`, 모르는 사번은 빠짐.
  - 직원 토큰이면 누구나(HR-4 와 같은 범위 — ADR-0070, 직원끼리 이름 안 가림).
  - (있으면 좋음) `activeOnly=true` — 재직자만.
- 받는 화면: 백오피스 "사번 입력 → '[이름] · 사번 에게 넘겨요' 확인 → 운영자 ID 로 보냄". bff 변경 없음(화면이 hr 를 직접).
- **회신(2026-11-24 PC 1, 사용자 "정해진 것만")**: hr-service 0.10.0 — `GET /hr/employee-labels?employeeNos=P2026-00012,P2026-00013`(반복·쉼표 둘 다, 1~50개, 형식 `P<연도 4>-<5자리>` 아니면 400). `operatorIds` 와 **둘 중 하나만**(둘 다·둘 다 없음 400). 응답·권한(직원 토큰 누구나)·퇴사자 포함·모르는 사번 빠짐은 operatorIds 와 같다. **`activeOnly` 는 넣지 않았다**(사용자 "정해진 것만" — 정하면 추가). 시험 `HrProvisioningIT.employeeLabels`. **LAN 배포 2026-11-24**(이미지 ee1ed1bd).
- **activeOnly(2026-11-24 사용자 "정정된걸로 해서 넣어", PC 2 전달 #396)**: hr-service 0.11.0 `activeOnly=true` → 퇴사자(LEFT) 뺌, 정지(SUSPENDED)는 재직이라 남김. 기본 false(지금처럼 퇴사자 포함). employeeNos·operatorIds 둘 다. 시험 `HrProvisioningIT.employeeLabels`. **LAN 배포 2026-11-24**(761e719c).

## HR-6. 직원 표시에 상태 `status` — 정지 직원 경고 (2026-11-24, 보낸 영역 backoffice — PC 2, 통합백오피스 프론트 요청 pmart-clients `docs/handoff/backoffice/2026-11-24-backend-employee-label-status.md`)
- **사용자 결정(2026-11-24, 웹 세션 경유)**: 맡기기·교대 인계 후임 고르기에서 정지(SUSPENDED) 직원은 "보이되 경고".
- **왜**: `EmployeeLabel` 에 상태가 없어 화면이 정지 직원을 알아볼 수 없다(HR-5 `activeOnly` 는 퇴사자만 뺀다).
- **요청**: `GET /hr/employee-labels` 줄에 선택 필드 `status`(hr 직원 상태 그대로 — ACTIVE·SUSPENDED·LEFT 등). 권한은 지금처럼 직원 누구나가 좋고, 어렵다면 `employeeNos` 로 부를 때만 실어도 된다.
- **PC 1 이 판단할 것**: 직원 정지 상태를 모든 직원에게 보여도 되는지(인사 정보 공개 범위) — 안 되면 "로그인할 수 없음" 같은 덜 자세한 값으로.
- 받는 화면: 확인 줄에 "정지 중이라 로그인할 수 없어요(인계를 받지 못하고, 맡겨도 처리하지 못해요)" 경고, 고르는 것은 막지 않음. bff 변경 없음.
