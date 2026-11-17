# ADR-0066 라이더 알림함 — 지원센터 답장·처리 끝·문의 답변·계정 제한 (휴대폰 푸시는 여전히 나중)

- 상태: 승인 (2026-11-16, 사용자 — 라이더 앱 요청 §11-5 "처리 결과 알림" → 채널 "라이더 알림함 먼저(추천)", 알릴 일 "도움요청 상담원 답장, 도움요청 처리 끝, 라이더 문의 답변, 계정 제한·해제" 모두)
- 대체: ADR-0064 결정 2(받는 사람은 고객만) — 라이더도 받는다. 결정 1(채널은 앱 알림함만)은 그대로.
- 관련: `notification-service.yaml` 0.4.0, 사건 `HelpRequestReplied`(새 — delivery-execution), `RiderInquiryAnswered`(새 — case), `CaseClosed`(RIDER_HELP), `RiderRestricted`(rider). 클라이언트 요청 `pmart-clients` `docs/handoff/rider-app/2026-11-16-backend-trip-info-and-signup.md` §11-5.

## 결정
1. **채널은 앱 알림함(IN_APP) 그대로** — 라이더도 고객과 같은 `/me/notifications`(라이더 토큰)에 쌓고 앱이 확인한다. 휴대폰 푸시(FCM)는 Firebase 프로젝트·서비스 계정 키가 생기면 같은 알림에 채널만 더한다.
2. **라이더에게 알리는 일(4가지, 모두 끌 수 없는 안내)**:
   - 지원센터 답장 — 도움요청 대화에 상담원 메시지(`HelpRequestReplied`, 메시지마다). 목적지 HELP_REQUEST(`<deliveryId>:<requestId>`).
   - 도움요청 처리 끝 — 사건함 RIDER_HELP 가 닫힘(`CaseClosed`, refs 의 riderId). 처리 결과·메모는 싣지 않는다.
   - 라이더 문의 답변 — `RiderInquiryAnswered`(고객용 `InquiryAnswered` 와 따로 — 그 사건은 customerId 필수). 목적지 INQUIRY.
   - 계정 제한·해제 — `RiderRestricted`(DISPATCH_RESTRICTED·SUSPENDED·TERMINATED·NORMAL). 사유·근거는 싣지 않고 "지원센터에 문의", 기한이 있으면 KST 로.
3. 문구에 메시지·답변 내용·개인정보를 싣지 않는다(앱이 원래 화면에서 읽는다). 같은 사건은 한 번.
4. `HelpRequestReplied` 는 배송 수행 버전을 올리지 않는다(aggregateVersion = 지금 수행 버전) — 올리면 배송 중인 라이더의 다음 동작이 X-Expected-Version 충돌이 난다. 다른 사건과 aggregateVersion 이 겹칠 수 있지만 공통 소비 코드는 버전으로 순서를 판정하지 않고, 이 사건은 알림함만 받는다.

## 결과
- 라이더 앱은 알림함을 주기적으로 확인해 배지·화면 안내를 띄우면 된다(푸시 전까지).
- 남은 것: 휴대폰 푸시 발송(FCM — 기기 토큰 등록 `/me/push-tokens` 는 notification 0.3.0 BR-09 에 있음), 운영자 알림함, 정산 확정 알림(사용자가 고르지 않음).
