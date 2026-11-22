# rider-auth-service 에 대한 요청

## RA-1. 소셜 로그인 "동의는 처음 가입할 때만" — 고객 쪽(auth-service)과 맞추기 (2026-11-22, 보낸 영역 platform — PC 1)

- **계기**: 사용자 2026-11-22 "라이더 auth 서비스랑 고객앱이랑 차이점… 라이더쪽을 수정해야해, 소셜로그인부분때문에" → "응". 고객 쪽은 2026-11-21 사용자 결정("처음 가입할 때만"·"1, 2 둘다")대로 바뀌었고 라이더는 아직이다. 정리: `docs/operations/2026-11-20-naver-social-login-summary.md` "2026-11-21 추가" 절.
- **정한 동작(고객과 같게)**: 로그인 버튼마다 계정 고르기·다시 로그인(앱), **정보 제공 동의 화면은 처음 가입할 때만**, **탈퇴하면 제공자와 앱 연결을 끊어** 다시 가입할 때 동의가 다시 나온다. 동의만 하고 가입을 마치지 않으면 다음 로그인에 동의를 다시 받는다.

| # | 할 일 | 고객 쪽 구현(참고 — 같은 모양으로) | 라이더 지금 |
|---|---|---|---|
| ① | 탈퇴 때 **카카오** 연결 끊기 | `CustomerWithdrawalService` 탈퇴 반영 커밋 뒤 `KakaoUnlinkClient`(`POST https://kapi.kakao.com/v1/user/unlink`, `Authorization: KakaoAK {admin-key}`, target_id_type=user_id·target_id=sub, 연결 2초·응답 3초, 실패는 로그만). 키 `pmart.auth.customer.kakao.admin-key`({cipher}) 없으면 안 부름. 시험 `KakaoUnlinkClientTest` | `RiderWithdrawals` 가 우리 DB 연결(`rider_identity`)만 지움 — 카카오 동의가 남아 재가입 때 동의 화면 안 나옴 |
| ② | 탈퇴 때 **네이버** 연결 끊기 | 탈퇴 재인증의 네이버 코드 교환을 `purpose=WITHDRAWAL` 로 받아, 프로필을 읽은 뒤 그 access token 으로 끊음(`NaverCodeExchangeService.exchange(…, withdrawal)`·`NaverTokenClient.unlink` — `grant_type=delete`) | 없음 |
| ③ | 네이버 **가입을 마치지 않은** 계정은 교환 때 끊기(BR-79) | LOGIN 교환에서도 id_token `sub` 로 가입을 마친 계정이 없으면(연결 없음·회원이 된 적 없음) 프로필을 읽은 뒤 끊음. 회원은 안 끊음, sub 못 읽음·조회 실패면 안 끊음, 끊기 실패는 교환 200. 로그 `purpose=SIGNUP_PENDING`(sub 없음). 시험 `NaverCodeExchangeIT.unlinksNaverWhenSignupNotFinishedButNotForMembers` | 없음. **판단 근거가 rider-auth 에 없다** — 가입은 rider-service `POST /riders/signups` 이고 가입 사건이 없다 → rider-service 에 내부 조회가 필요(아래 RS 쪽 줄) |
| ④ | 네이버 **이메일**(BR-77) | 교환 때 같은 프로필 API(`/v1/nid/me`) 응답의 email 을 id_token sub 에 10분 묶어 두고(원 sub 대신 SHA-256), 그 sub 의 검증된 id_token 으로 로그인할 때 꺼내 씀 | 네이버 ID 토큰에는 이메일이 없어 `rider_identity.email_masked` 가 네이버만 비어 있음 — 같은 방식으로 받아 **가린 이메일**만 채우면 됨(원문 저장 안 함 규칙 그대로) |

- **설정·비밀값(사용자·PC 1)**: ① 은 **라이더용 카카오 앱의 Admin 키**가 필요하다(고객 앱 키와 다른 앱). 원본 위치 제안 `deploy/lan/.secrets/kakao-rider-admin-key.txt` → pmart-config `rider-auth-service-lan.yml` `{cipher}`. 지금 PC 1 에 없음 — 사용자에게 받는다. 키가 없으면 그 호출만 건너뛰게(고객과 같음).
- **rider-service 쪽(같은 PC 5)**: ③ 을 위해 "이 라이더 주체가 가입을 마쳤는가"를 묻는 내부 조회(예 `GET /internal/riders/{riderId}/signup-status` → 가입 행 있음·탈퇴 여부). 또는 가입 완료 사건을 내고 rider-auth 가 구독. 어느 쪽이든 PC 5 가 정한다.
- **라이더 앱(라이더 앱 세션 몫, 서버 변경 없음)**: 구글 — 로그인 버튼마다 `GoogleSignIn.authenticate()`(자동 선택 없음), 탈퇴 성공 뒤 `disconnect()`→`signOut()`. 카카오 — 늘 `loginWithKakaoAccount(prompts: [Prompt.login])`, 로그아웃·탈퇴 때 SDK `logout()`. 네이버 — 인가 요청 `auth_type=reauthenticate`. 가입을 그만둘 때 구글·카카오는 앱이 끊기(고객 앱 2e3ec6da 참고).
- **설정 주의**: 고객 쪽에 있던 `naver.unlink-after-exchange`(로그인마다 끊기, BR-74)는 11-21 에 껐다 — 라이더에 새로 넣지 않는다.
- **시험**: 고객 쪽 `NaverCodeExchangeIT`(WireMock — 끊기 호출 유무)·`KakaoUnlinkClientTest` 와 같은 수준. 끊기 실패가 탈퇴·교환을 막지 않는지.
- **완료 뒤**: PR(바꾼 것·시험·영향) → PC 1 병합·LAN 배포(PC 5 rider-auth·rider). 이 줄에 회신.
- **회신(2026-11-22 PC 5 — rider-auth 0.4.0·V4, 브랜치 `delivery/rider-social-consent-once`)**: 네 가지 모두 서버 구현.
  - ① `RiderWithdrawals` 가 `RiderWithdrawn` 반영 때 카카오 sub 를 지우기 전에 읽고 **커밋 뒤** `infrastructure/KakaoUnlinkClient`(고객과 같은 호출, 연결 2초·응답 3초, 실패는 로그만). 키 `pmart.rider-auth.kakao.admin-key`(없으면 안 부름 — **PC 1 이 pmart-config `rider-auth-service-lan.yml` 에 `{cipher}` 로 넣어야 켜짐**), `pmart.rider-auth.kakao.api-base-url`(기본 `https://kapi.kakao.com`).
  - ② 교환 본문 `purpose`(LOGIN 기본·WITHDRAWAL). WITHDRAWAL 이면 프로필을 읽은 뒤 `grant_type=delete`(`{token-base-url}/oauth2.0/token`).
  - ③ LOGIN 에서 id_token sub 로 연결이 없거나, 연결된 주체가 rider-service `GET /internal/riders/{id}` 에서 404(가입 전·탈퇴)면 끊음. 200 이면 안 끊음, 조회 실패·sub 못 읽음이면 안 끊음. **rider-service 변경 없음**(그 내부 조회가 이미 "가입한, 탈퇴하지 않은 라이더"만 200). 설정 `pmart.rider-auth.rider-base-url`(기본 `http://rider-service:8403`).
  - ④ 프로필 `/v1/nid/me` email 을 **가려서**(a***@n***) sub 의 SHA-256 에 10분(`naver_email_hold`, V4) → 그 sub 의 네이버 로그인 때 `rider_identity.email_masked` 로 옮기고 지움. 원문 없음. 설정 `pmart.rider-auth.naver.profile-base-url`(기본 `https://openapi.naver.com`).
  - `unlink-after-exchange` 는 넣지 않음. 끊기·프로필 실패는 교환·탈퇴를 막지 않음.
  - **라이더 앱 몫 하나 더**: 지금 라이더 탈퇴(`POST /riders/me/withdrawals`)에는 재인증이 없다 — 네이버로 가입한 라이더는 탈퇴 전에 네이버 인가를 한 번 더 받아 `purpose: WITHDRAWAL` 로 교환해야 ② 가 일어난다(고객 앱과 같은 순서). 카카오는 서버가 하므로 앱 일 없음.
