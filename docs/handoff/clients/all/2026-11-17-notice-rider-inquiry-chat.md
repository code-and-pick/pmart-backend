# 안내 — 라이더 문의가 대화가 됨(case 0.21.0): 직원 화면

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17 — 라이더 앱 요청 §19, 사용자 "문의에 대화 붙이기"
- 대상: 라이더 문의를 보는 직원 화면 — 기본 백오피스(`web/backoffice`, PC 2) 라이더 문의, 라이더 백오피스(`web/rider-backoffice`, PC 5)
- 상태: 백엔드 PR(case-service — platform, PC 1 검토·배포)

## 바뀐 것
- 라이더가 자기 문의에 메시지를 이어 쓸 수 있다(`POST /inquiries/{id}/messages`, 라이더만). 이어 쓰면 문의가 **RECEIVED** 로 돌아간다 → 지원센터 목록 `status=RECEIVED` 에 다시 뜬다.
- 지원센터는 **지금처럼** `POST /inquiries/{id}/replies` 로 답한다(ANSWERED + 라이더 알림).
- 대화 보기: `GET /inquiries/{id}/messages?after=<seq>`(RIDER_OPS·OPERATOR_ADMIN — 상세와 같은 권한) → `items[]`(`senderType` RIDER·SUPPORT, 직원에게는 `senderId` 도) + `status`.
- 상세 `replies[]` 에 **`authorType: RIDER`** 줄이 섞인다 — 지금 화면이 replies 를 모두 "답변"으로 그리면 라이더 메시지도 답변처럼 보인다. `authorType` 으로 나누거나 `/messages` 로 그려 주세요.
