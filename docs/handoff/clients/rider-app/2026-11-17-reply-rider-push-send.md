# 회신 — 라이더 앱 푸시 발송(P1·P2)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app` — 요청 `docs/handoff/rider-app/2026-11-17-backend-rider-push-send.md`
- 상태: 백엔드 PR #136(notification — platform 영역, PC 1 검토·배포). **서버 FCM 키가 설정에 들어가기 전까지는 보내지 않는다**(알림함만)

## P1 — 알림함 알림을 푸시로도: 됨
- 라이더 알림함에 새 알림이 저장되면 커밋 뒤 그 라이더의 모든 등록 단말로 보낸다: 지원센터 답장·도움요청 처리 끝·문의 답변·계정 제한·풀림·수행료 조정.
- `notification` = 알림함과 같은 `title`·`body`(개인정보 없음). `data` = `notificationId`·`templateKey`·**`type`·`ref`**(알림함 목적지와 같은 값 — `HELP_REQUEST` + `<deliveryId>:<requestId>`, `INQUIRY` + `<inquiryId>`, `NONE`). `destinationType`·`destinationRef` 도 같은 값으로 함께 실린다.

## P2 — 새 배차 제안: 됨
- dispatch 가 제안을 보낼 때(`DispatchOfferSent`) 그 라이더 단말로 **푸시만** 보낸다(알림함에는 넣지 않는다).
- `title` "새 배차 제안이 왔어요", `body` "예상 배달료 N원 · 1분 안에 수락해 주세요."(주소·고객 정보 없음). `data` = `type: DISPATCH_OFFER`·`offerId`·`deliveryId`·`expiresAt`.
- **TTL = 제안 만료까지**(만료 뒤 도착하면 FCM 이 버린다). 이미 만료된 사건이면 보내지 않는다. Android `tag: dispatch_offer` 라 새 제안이 앞 제안 알림을 덮는다. 앱은 누르면 `GET /offers` 로 다시 읽는다(만료면 목록에 없음 — "이미 끝난 제안" 안내).
- `/internal/dispatch-notification-records`(계약만, 501)는 **쓰지 않는다** — 제안의 정본은 dispatch `dispatch_offer`, 푸시 결과는 기록하지 않는다(알림 실패로 배차를 다시 하지 않는다).

## 정해 달라고 한 것
1. **FCM 프로젝트·자격증명**: 앱의 `google-services.json` 과 **같은 Firebase 프로젝트**의 서비스 계정 키를 서버가 쓴다(사용자가 만들어 PC 1 `.secrets/` 에 → PC 1 이 설정 `pmart.notification.fcm.service-account-json` 에 {cipher}). 앱은 할 것 없음.
2. **토큰 종류**: 서버는 FCM HTTP v1 `message.token` 으로 보낸다 = **지금처럼 등록 토큰(`getToken`)을 그대로 보내 주세요**. 다른 방식으로 바꾸면 서버도 바꿔야 하니 그때 다시 요청.
3. **메시지 형식**: **notification + data**. 앱이 꺼져 있거나 뒤에 있으면 시스템이 띄운다. **앱을 보고 있을 때(포그라운드)는 시스템이 띄우지 않으니 앱이 직접**: 배차 제안은 제안 화면을 바로 새로 읽거나 배너, 나머지는 알림함 배지·짧은 배너 권장.
4. **우선순위·채널**: 서버가 정한다. Android `priority` — **배차 제안 HIGH, 나머지 NORMAL**. `android.notification.channel_id` — **`dispatch_offer`**(배차 제안), **`support`**(지원센터 답장·처리 끝·문의 답변), **`account`**(계정 제한·풀림), **`settlement`**(수행료 조정). 앱은 이 ID 로 채널을 미리 만든다(이름·소리는 앱이 정함 — 배차 제안은 소리·진동 강하게 권장).
5. **보내지 않는 조건**: 배차 제안은 dispatch 가 **운행 중·신규배차 켬·계정 제한 없음**인 라이더에게만 만들므로 따로 막지 않는다. 라이더 "알림 끔" 설정은 지금 없다(`/riders/me/settings` 는 배차 방식뿐) — 필요하면 요청해 달라. 계정 제한·풀림 알림은 정지 상태에도 보낸다(알아야 하므로). **FCM 이 404·UNREGISTERED 로 답한 토큰은 서버가 지운다** — 앱은 로그인·토큰 갱신 때 다시 PUT 하면 된다.
