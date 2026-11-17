# 회신 — 배송 밖 채팅 상담(§19)·사진 다시 보기·배포 상태·회수 화면 일정

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app` — 앱의 2026-11-17 정리(반영 끝 1~5)

## 1. 배포 상태 — 탈퇴는 LAN 에 이미 있다
PC 1 일지(2026-11-17)로 LAN 에 배포됨: 탈퇴·수행료 조정(rider V7·execution V13), 가입 전 확인(번호·계좌·은행 코드·주소 확인, rider V8), 운행시간·주문번호·마트 이름(execution V12), 전달 불가 대화·재개 요청.
- PC 5 가 LAN 실제 게이트웨이로 돌린 확인 각본에서 **`POST /riders/me/withdrawals` 202 APPLIED → `GET /riders/me/withdrawals/{id}` COMPLETED → 같은 토큰 401** 통과(2026-11-17 18시경, 28/28).
- 앱에서 아직 501 이 보이면: 앱이 부르는 경로(`/api/v1/riders/me/withdrawals`)·LAN 주소인지, 목 서버를 보고 있지 않은지 확인해 주세요. 그래도 501 이면 요청·응답(경로·시각)을 적어 주면 서버 쪽에서 찾는다.

## 2. 배송 밖 채팅 상담(§19) — "문의에 대화 붙이기"(사용자 2026-11-17)
case-service 계약 **0.21.0**(백엔드 PR — platform 영역, PC 1 검토·배포). 따로 상담 세션은 없다 — **문의 한 건이 곧 대화방**.
| 무엇 | 서버 |
|---|---|
| 상담 시작 | 지금처럼 `POST /inquiries`(라이더 분류) — 처음 내용 = 문의 `body` |
| 메시지 목록 | `GET /inquiries/{inquiryId}/messages?after=<seq>` → `{items: InquiryMessage[], status}` — 오래된 것부터, 한 번에 200개. 처음 문의 내용은 여기 없다(`GET /inquiries/{id}` 의 body) |
| 이어 쓰기 | `POST /inquiries/{inquiryId}/messages` `{body}`(1~1000자, 멱등 키) → 201 InquiryMessage. 상태가 **RECEIVED**(지원센터 답 기다림)로 돌아간다 |
| 지원센터 답 | 기존 답변 — 앱에는 senderType `SUPPORT`(상담원 ID 는 null), 상태 ANSWERED, 알림함 `RIDER_INQUIRY_ANSWERED`(+ 푸시, 키가 들어가면) |
| 닫힘 | 닫힌 문의에 쓰면 409 `INQUIRY_CLOSED` → "새 문의를 남겨 주세요" |
- `InquiryMessage` = `messageId`·`seq`(다음 `after`)·`senderType`(RIDER·SUPPORT)·`senderId`·`body`·`createdAt`. 3초마다 확인하던 목 그대로 `after` 만 넘기면 된다.
- 문의 상세 `replies` 에도 라이더가 이어 쓴 줄이 `authorType: RIDER` 로 섞인다(대화는 `/messages` 를 쓰면 된다).
- 고객 문의에는 대화가 없다(404). 운영 시간 밖 안내·상담원 배정·대기 순서는 없다 — 지원센터가 목록에서 RECEIVED 를 보고 답한다.

## 3. 내가 올린 사진 다시 보기 — 이미 있는 API 로 된다
- 전달 불가 대화(실패 등록이 연 도움요청): **`GET /deliveries/{id}/help-requests/{requestId}`** 의 `evidenceRefs` = 실패 등록 때 올린 사진, 대화 메시지 `photoRefs` = 대화에 붙인 사진.
- 이미지: **`GET /deliveries/{id}/evidence-photos/{photoRef}`**(원본, `Cache-Control: no-store` — 앱도 디스크에 남기지 않게). 이 배송에 배정된 라이더 본인이 볼 수 있다.
- 그래서 앱을 껐다 켜도 [사진] 버튼은 서버에서 다시 받아 그리면 된다(메모리 사진은 미리보기용으로만).

## 4. 회수 배송 화면 한 벌 — **지금 바로**(사용자 2026-11-17)
`2026-11-17-request-claim-return-pickup.md` 대로 시작해 주세요. 반품 회수·오배송 회수 배차는 LAN 에 배포돼 실제로 배정될 수 있다. 회수지 연락처 통합(`/recovery-contact` 에 주소)은 백엔드 PR 단계 — 그 전에는 반품 회수의 주소를 `drop-info` 로 보면 된다.

## 5. 푸시
회신 `2026-11-17-reply-rider-push-send.md` — 서버 키가 들어가 배포되면 알린다.
