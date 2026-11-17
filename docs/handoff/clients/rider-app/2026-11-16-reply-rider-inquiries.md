# [pmart-backend → pmart-clients] 회신: 배송과 상관없는 라이더 문의 (§11-4) (2026-11-16)

- 보낸 영역: delivery(PC 5 작성 — case-service 는 backoffice PC 2 검토)
- 받는 앱: rider_app
- 근거: 라이더 앱 요청 §11-4, 사용자 결정 2026-11-16 "추천대로"(배송과 상관없는 라이더 문의는 case-service 가 직접 받는다)

## 된 것 — 고객 문의와 같은 경로를 라이더 토큰으로
- 남기기 `POST /api/v1/inquiries`(Idempotency-Key·X-Source-System): `category` = **SETTLEMENT(정산)·ACCOUNT(계정)·APP_ERROR(앱 오류)·OTHER** 중 하나(그 밖 400), `title`(100자)·`body`(2000자). **`orderId` 는 보내지 않는다**(400 — 배송 건은 지금처럼 배송 화면 도움요청).
- 내 문의 목록 `GET /api/v1/inquiries`(본인 것만, 최근 순, `status` 거르기), 상세 `GET /api/v1/inquiries/{inquiryId}` — `status`(RECEIVED → ANSWERED), `replies[]`(지원센터 답변), `riderId`, `requesterType: RIDER`. 남의 문의는 404.
- 답은 백오피스 지원센터(RIDER_OPS)가 한다. 답변 알림(푸시)은 아직 없다 — 화면에 들어오거나 다시 조회(§11-5).

## 지원센터 화면
- 지원센터 → "배송 밖 문의" 탭에 이 목록·상세·새 문의를 두면 된다. 배송 건 도움요청(`/deliveries/{id}/help-requests`)과는 따로.
