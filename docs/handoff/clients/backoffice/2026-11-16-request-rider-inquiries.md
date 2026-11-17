# 요청 — 라이더 문의 보기·답하기 (case-service 0.13.0)

보낸 영역: delivery(PC 5 작성 — case-service 는 backoffice PC 2 검토)
받는 곳: pmart-clients backoffice 웹(지원센터 RIDER_OPS·관리자)
상태: case PR 대기(병합·배포는 PC 1).

## 왜
라이더 앱 요청 §11-4 — 정산·계정·앱 오류처럼 배송과 상관없는 라이더 문의. 사용자 결정 2026-11-16 "추천대로": case-service 가 직접 받는다(고객 문의 `/inquiries` 를 넓힘).

## 백엔드(이미 됨, 0.13.0)
- 문의에 `requesterType`(CUSTOMER·RIDER). 라이더 문의는 `riderId`(customerId 없음), 분류 SETTLEMENT·ACCOUNT·APP_ERROR·OTHER, 주문 없음. 사건 종류 RIDER_INQUIRY(사건함 `/cases` 에는 고객 문의처럼 안 나옴).
- 보기: **RIDER_OPS = 라이더 문의만**, CS_AGENT = 고객 문의만(예전 그대로), OPERATOR_ADMIN = 전부(보기만). 목록 `GET /inquiries?requesterType=RIDER|CUSTOMER`(못 보는 종류를 고르면 403). RIDER_OPS 가 `orderId` 로 거르면 예전처럼 403(통합 조회는 원천 불가로 표시 — 그대로).
- 답하기 `POST /inquiries/{id}/replies`: 라이더 문의 = RIDER_OPS(상담원 403), 고객 문의 = CS_AGENT(지원센터 403). 라이더 문의 답변은 고객 알림 사건을 내지 않는다.

## 화면 요청
- 지원센터 메뉴에 "라이더 문의" 목록(`requesterType=RIDER`, 상태 거르기) → 상세(본문·답변 이력) → 답하기(버전 = 문의 version). 라이더 ID 는 라이더 찾기 화면으로 이을 수 있다.
- 고객 문의 화면은 바뀌지 않는다. 관리자 화면이 두 종류를 같이 보이면 `requesterType` 표시.
