# 알림 — 라이더 휴대폰 푸시(FCM) 발송 코드

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17 — 사용자 "발송 코드만 먼저, 키는 나중"
- 대상: pmart-clients `apps/rider_app`
- 상태: 백엔드 PR(notification — PC 1 검토). **Firebase 서비스 계정 키가 설정되기 전까지 실제로는 보내지 않는다**(알림함만)

- 앱이 할 것: 로그인 뒤 FCM 단말 토큰을 `PUT /me/push-tokens/{deviceId}`(라이더 토큰, `{token, platform: ANDROID, appVersion}`)로 등록(이미 있는 API — 고객앱과 같다). 토큰이 바뀌면 다시 PUT, 로그아웃이면 `DELETE`.
- 서버가 보내는 것: 라이더 알림함에 새 알림이 생길 때마다(지원센터 답장·도움요청 처리 끝·문의 답변·계정 제한·수행료 조정) 같은 제목·본문의 알림 + data `notificationId`·`templateKey`·`destinationType`(HELP_REQUEST·INQUIRY·NONE)·`destinationRef`. 앱은 누르면 알림함과 같은 곳으로.
- Firebase 프로젝트: 앱의 `google-services.json` 과 **같은 프로젝트**의 서비스 계정 키가 서버에 들어가야 한다 — 사용자·PC 1 이 정한다.
