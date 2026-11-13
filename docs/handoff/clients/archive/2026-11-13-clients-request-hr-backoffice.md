# 인사팀 백오피스(별도 웹 앱) — hr-service 화면 요청

- 작성: pmart-backend, 2026-11-13
- 대상: pmart-clients — **새 웹 앱**(인사팀 백오피스). 지금 백오피스(`web/backoffice`)에 메뉴로 넣지 않는다.
- 근거: 사용자 결정 2026-11-13 — "별도 인사팀 백오피스 앱", "SSO 켬". ADR-0056(결정 5·7·8·10, 5단계), 계약 `contracts/openapi/hr-service.yaml`(정본 — 생성기로 타입을 만든다), 흐름 `docs/business/flows/employee-account-provisioning.md`.
- 답하는 것: 11-12 회신(`2026-11-12-backend-backoffice-employee-auth-deployed.md` §4 "인사팀 백오피스는 hr-service 계약·요청서를 기다린다").
- **백엔드 상태**: hr-service·mail-provisioner·권한 요청 연동은 구현·시험 끝, **LAN 배포 전**(5-5 — 날짜가 정해지면 알림). 화면은 계약과 목 서버로 먼저 만들 수 있다.

## 1. 앱과 로그인 (지금 백오피스와 같은 방식)

| 항목 | 값 |
|---|---|
| 로그인 | employee-auth-service, 지금 백오피스와 같은 흐름(`GET /employee-auth/logins` → `/auth/callback?ticket=` → `POST /employee-auth/tokens`, PKCE·`X-Device-Id`, sessionStorage). **앱 이름 `app=hr-backoffice`(확정)** |
| SSO | 켬 — 같은 브라우저에서 지금 백오피스에 로그인돼 있으면 비밀번호 없이 들어온다. 로그아웃 200 `idpLogoutUri` 로 이동하면 **두 백오피스 모두** Keycloak 세션이 끝난다(각 앱의 PMart 세션 토큰은 각자 지운다) |
| LAN 주소(확정) | `http://192.168.0.40:8081` (PC 4, 새 nginx) — 복귀 `…/auth/callback`, 로그아웃 뒤 `…/login`. 개발 `http://localhost:5174` |
| `X-Source-System` | `hr-backoffice-web` |
| 볼 수 있는 사람 | 역할 `HR_ADMIN`(본인 조회 `/hr/employees/me` 만 모든 직원). 역할이 없으면 403 → "인사 권한이 없습니다" 안내 |

백엔드가 할 일(5-5 때 같이): employee-auth-service 앱 `hr-backoffice`(복귀 주소·SSO·로그아웃 경로), Keycloak 로그아웃 허용 주소, 게이트웨이 CORS 출처, PC 4 서빙(`lan.sh publish-hr-backoffice <dist>` — 지금 백오피스 서빙과 따로라 서로 덮지 않는다).

## 2. 화면과 API

모든 변경 요청은 `Idempotency-Key`(요청마다 새 UUID, 재시도 때는 같은 값)·`X-Source-System`, 상태 변경은 `X-Expected-Version`(직원 `version`). 오류는 Problem Details(`code`).

| 화면 | API | 메모 |
|---|---|---|
| 직원 목록 | `GET /hr/employees?status&affiliationCode&q&size&cursor` | 검색어 = 이름·메일 ID·사번 앞부분. 열: 사번·이름·메일·고용 형태·소속·상태·계정 발급 단계 |
| 직원 등록 | `POST /hr/employees` | 한 화면: 이름, 메일 ID(→ `<id>@pmart.com`, 소문자·숫자·`._-` 3~32자), 고용 형태(REGULAR·PART_TIME·DAILY), 소속(본사 부서 / 센터 + 코드), 근무 시작일, 계약 종료일(PART_TIME·DAILY 필수, REGULAR 비움), **요청 권한**(행: 역할·센터·기간·근거, 비워도 됨). 409 `MAIL_ID_TAKEN`(퇴사자 ID 도 다시 못 씀) |
| 직원 상세 | `GET /hr/employees/{operatorId}` | 기본 정보, `provisioning`(단계·시도·마지막 오류 코드·다음 시도), `requestedGrants`(아래 §3) |
| 소속 변경 | `POST …/affiliation-changes` | 사유 필수. **권한은 바뀌지 않는다** — 안내 문구 "센터 이동은 권한을 따로 요청" |
| 정지 / 복귀 / 퇴사 | `POST …/suspensions` · `…/reinstatements` · `…/departures` | 사유 필수. 정지·퇴사는 그 직원의 로그인이 바로 막힌다(이미 받은 토큰도) — 확인 대화상자 |
| 안내 메일 다시 | `POST …/invite-resends` | 202. 비밀번호 설정 링크(3일) |
| 발급 다시 시도 | `POST …/provisioning-retries` | `provisioning.step` 이 FAILED·COMPENSATED 일 때만 버튼 |
| 사서함 초기 비밀번호 | `POST …/mailbox-password-resets` | 응답의 `initialPassword` 는 **한 번만** 보여 주고 저장·로그·클립보드 자동 복사 금지. 잃어버리면 다시 발급. 웹메일(`https://192.168.0.101:8190`) 비밀번호이고 로그인(Keycloak) 비밀번호와 다르다 |

계정 발급 단계(`provisioning.step`) 표시: REQUESTED·MAILBOX_CREATED·IAM_CREATED·INVITE_SENT = "발급 중"(몇 초~수 분, 상세 화면은 몇 초마다 다시 조회), DONE = "완료", FAILED = "실패 — 다시 시도", COMPENSATED = "실패(되돌림) — 다시 시도". 오류 코드는 그대로 작게 보여 준다.

## 3. 요청 권한 표시(5-4)

- 등록 때 받은 요청 권한은 계정 발급이 끝나면 approval-service 에 권한 요청으로 자동 제출된다. `requestedGrants[].submission`: PENDING(발급 전) → SUBMITTED(승인 대기) → APPROVED / REJECTED.
- **소속 센터의 `MFC_WORKER` 는 자동 승인**(몇 초 뒤 APPROVED). 그 밖 역할·다른 센터는 승인자(`OPERATOR_ADMIN`)가 **지금 백오피스 승인함**에서 결정한다.
- 인사팀 앱은 상태만 보여 준다(승인 버튼 없음 — 인사와 권한 분리, BO-01). 역할 고르기 목록: `OPERATOR_ADMIN, HR_ADMIN, CENTER_MASTER_APPROVER, CS_AGENT, DISPLAY_APPROVER, INVENTORY_APPROVER, MD, MFC_SUPERVISOR, MFC_WORKER, PRICE_APPROVER, PRODUCT_APPROVER, SERVICEABILITY_APPROVER`.

## 4. 지금 백오피스(`web/backoffice`)에 필요한 작은 변경

- 승인함에 새 종류 **`GRANT`(직원 권한)** 가 나온다. 행(`lines[].attributes`)에 `role`·`centers`·`validFrom`·`validTo`·`reason`, 대상(`targetRef`) = 운영자 ID. 요청자 = 등록한 인사팀 직원 — 같은 사람은 승인할 수 없다(403). 자동 승인 건은 승인자가 `RULE:GRANT-MFC-WORKER-OWN-CENTER@v1` 로 보인다.

## 5. 회신 부탁

1. §2 화면 중 계약만으로 만들기 어려운 것(API·필드가 모자란 것)이 있으면 알려 달라.
2. 목 서버로 만든 뒤, 백엔드 LAN 배포(5-5) 알림을 받으면 실로그인 확인.
(주소·포트·앱 이름은 사용자가 백엔드에 맡겨 위 값으로 확정 — 2026-11-13.)
