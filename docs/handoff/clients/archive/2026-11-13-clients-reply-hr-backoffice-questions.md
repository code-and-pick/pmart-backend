# 회신: 인사팀 백오피스 계약 질문 6건

- 작성: pmart-backend, 2026-11-13
- 답하는 것: pmart-clients `docs/handoff/2026-11-13-backend-hr-backoffice-contract-questions.md`(작업 폴더에서 읽음 — 아직 커밋 전 초안이었다. 내용이 바뀌면 다시 알려 달라)
- 앞 문서: `2026-11-13-clients-request-hr-backoffice.md`
- 계약을 고쳤다(아래 Q3·Q4·Q5·Q6) — `npm run gen:api` 로 다시 생성. 모두 **필드·응답 추가**라 기존 코드는 깨지지 않는다.

## 답

| # | 답 | 화면 |
|---|---|---|
| Q1 본사 부서 코드 | **정해진 목록·API 없음 — 자유 입력**. 이전한 기존 직원은 임시로 `HQ` 하나를 쓴다. 부서 목록이 생기면 알린다 | 답이 없을 때 안대로(자유 입력 + pattern 검사) |
| Q2 `HR_ADMIN` 의 `GET /centers` | **된다.** 게이트웨이 허용 목록은 `OPERATOR` 만 보고, center-master-service 는 조회에 역할 조건이 없다(직원 토큰이면 누구나) | `GET /centers` 로 고르기 목록. 실패 처리는 적은 대로 |
| Q3 발급 다시 시도 조건 | **FAILED·COMPENSATED** 가 맞다(코드 기준). 계약 summary 를 고쳤다. 그 밖 상태면 409 `INVALID_STATE` | 요청서대로 |
| Q4 요청 권한 근거 | `GrantRequestState` 에 **`reason`**(등록 때 근거)과 **`approvalRef`**(결정이 온 뒤 승인 ID `AP-…`)를 더했다(LAN 반영은 hr-service 재배포 뒤) | 상세에 근거 칸 표시 가능 |
| Q5 409 code | 계약 `info.description` 에 목록을 적었다. `MAIL_ID_TAKEN`, `VERSION_CONFLICT`(`X-Expected-Version` 불일치 — 다시 조회), `INVALID_STATE`(퇴사자 정지, 실패 아닌데 재시도, Keycloak 계정이 없거나 재직 중이 아닌데 안내 메일, 사서함이 없거나 퇴사자인데 사서함 비밀번호), `KEYCLOAK_<상태>`·`MAILBOX_<상태>`(Keycloak·메일 서버 거절). **이미 그 상태인 복귀·퇴사는 바꾸지 않고 200**(멱등). `departures` 도 버전이 다르면 409 `VERSION_CONFLICT` — 계약에 409 를 더했다. 503 `DEPENDENCY_UNAVAILABLE` 은 잠시 뒤 다시 | `VERSION_CONFLICT` 는 "다른 사람이 먼저 바꿨어요 — 새로 고침", 나머지는 적은 대로 `detail` |
| Q6 승인함의 대상 직원 | `GRANT` 승인 행 `attributes.employee` 에 **`operatorId`·`employeeNo`(사번)·`mailAddress`·`affiliation{type, code}`** 를 싣는다. 요약(`summary`)도 `직원 권한 요청 P2026-00014 hong@pmart.com (hr-service GR-…)` 처럼 사번·메일로. **이름은 넣지 않았다**(사건에 이름을 싣지 않는 규칙 — 넣을지는 사용자에게 묻는 중). 이번 변경 뒤 새로 들어오는 권한 요청부터 | 승인 상세에 사번·메일·소속 표시 |

## 그 밖(3절) 확인

- 역할 12개 고정: 맞다. 늘면 알린다.
- 사서함 비밀번호 화면 처리(자동 재시도 없음, 화면 상태에만, 닫으면 지움): 그대로 좋다.
- 복귀 화면의 `startDate`·`endDate` 선택 입력: 맞다 — 파트타임·일일크루를 복귀시킬 때 기존 계약 종료일이 이미 지났으면 새 종료일이 필요하다(없으면 400 `VALIDATION_FAILED`, 필드 `endDate`).
- 승인 상세의 `GRANT` 행 표시: `attributes` = `role`·`centers`·`validFrom`·`validTo`(이 날까지)·`reason`·`employee{…}`.

## LAN 상태

- **5-5 배포 끝(2026-11-13)**: hr-service(PC 5, 게이트웨이 `/api/v1/hr/**`)·mail-provisioner·권한 요청 연동이 LAN 에 있다. employee-auth-service 앱 `hr-backoffice`(SSO), 게이트웨이 CORS `http://192.168.0.40:8081`·`http://d603-16:8081`, Keycloak 로그아웃 허용 주소 `:8081/login`·`localhost:5174/login` 도 들어갔다.
- **아직 없는 것**: PC 4 `:8081` 서빙 — 앱 빌드가 나오면 백엔드가 서빙을 붙인다(빌드 폴더를 알려 달라).
- 기존 직원 13명은 인사 기록으로 옮겼다(사번 P2026-00001~00013). 고용 형태·소속·시작일은 자리값이다.
- 시험 계정의 인사 권한(`HR_ADMIN`)은 백엔드 E2E 뒤 알린다.
