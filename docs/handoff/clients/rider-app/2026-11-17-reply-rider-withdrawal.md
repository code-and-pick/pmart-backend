# 회신 — 라이더 탈퇴(§17)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app` — 요청 `docs/handoff/rider-app/2026-11-16-backend-trip-info-and-signup.md` §17
- 계약: `rider-service.yaml` 0.6.0(`WithdrawalBlocker` enum) — **`gen:api` 다시**
- 상태: 백엔드 PR 단계(rider·execution·rider-auth). PC 1 병합·배포 뒤 이 파일에 날짜를 적는다 — 그 전까지 LAN 은 501 그대로

## 사용자 결정(2026-11-17)
- 막는 것: 진행 중 배송, **확정 전** 수행료(완료 5시간 안 — 확정된 금액은 탈퇴 뒤에도 지급일에 지급), 산정 불가(지원센터 조정 대기), 열린 도움요청, 제한 검토 중. **이미 걸린 계정 제한(정지 등)은 막지 않는다.**
- 탈퇴하면 이름·전화·계좌·거주지·면허를 바로 지운다. 같은 전화·면허로 다시 가입할 수 있다(같은 Google 계정으로 다시 로그인하면 새 라이더로 가입 화면).

## 앱이 할 것
| 경우 | 서버 | 앱 |
|---|---|---|
| 신청 | `POST /riders/me/withdrawals`(Idempotency-Key, X-Source-System, 본문 `{reason?}`) | — |
| 막힘 | **409 `INVALID_STATE`**, problem `details` = `[{field: "blockers", reason: <코드>}]`. 결과 조회 `GET …/withdrawals/{requestId}` = `state: REJECTED`, `blockers` | 코드별 안내 — `ACTIVE_DELIVERY` 진행 중 배송을 끝내 주세요 / `UNCONFIRMED_EARNING` 수행료 확정(완료 5시간) 뒤 / `EARNING_ADJUSTMENT_PENDING` 지원센터 정산 확인 / `OPEN_HELP_REQUEST` 도움요청이 끝난 뒤 / `RESTRICTION_REVIEW` 지원센터 문의. 글자 추측 대신 enum 으로 |
| 다시 시도 | 같은 Idempotency-Key 로 다시 내면 **다시 검사**한다(막던 것이 풀렸을 수 있다) | — |
| 완료 | **202**, `status: APPLIED`, `nextAction: LOGOUT`. 결과 조회 `state: COMPLETED`(PENDING 은 지금 없다) | 로그아웃·로컬 데이터 지우기 |
| 세션 | 완료 즉시 rider-auth 가 **모든 세션을 끊는다** — 이미 나간 access token 도 게이트웨이에서 401, refresh 도 401. Google 연결도 지워 다음 로그인은 `newAccount: true`(새 riderId) | 401 을 받으면 로그인 화면 |
| 탈퇴 뒤 | `GET /riders/me` 등 404 RIDER_NOT_FOUND. 같은 riderId 로 가입은 409 `RIDER_WITHDRAWN` | 다시 로그인 → 가입 |
| 서버 장애 | execution 을 못 부르면 **503**(기록 없음) | "잠시 뒤 다시" — 결과를 모른다고 하지 않는다(아무 것도 바뀌지 않았다) |

## 참고
- 산정 불가 수행료를 지원센터가 조정하는 API 는 아직 없다 — 산정 불가가 있는 라이더는 그때까지 `EARNING_ADJUSTMENT_PENDING` 으로 막힌다.
- 2026-11-17 추가: 산정 불가 수행료 조정 기능을 만들었다(execution 0.13.0 — 지원센터가 귀책으로 지급/미지급). 조정되면 `EARNING_ADJUSTMENT_PENDING` 이 풀리고, 라이더 알림함에 "수행료가 조정되었어요"(`RIDER_EARNING_ADJUSTED`, 지급이면 금액·지급 예정일)가 온다.
