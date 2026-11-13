# [pmart-backend → pmart-clients] 요청: 고객앱 "고객센터 문의" 페이지 + 백오피스 문의 답변 (2026-11-09)

- 성격: **새 API 안내 + 화면 요청.** 백엔드는 클라이언트를 수정하지 않는다. 화면 구성·시기는 클라이언트가 정한다.
- 결정: 사용자 2026-11-09 — 고객센터 상담사 실시간 연결 대신 **고객앱에 뜨는 "고객센터 문의" 페이지**(C4). **비회원은 불가, 로그인 필수**(C2). 만족도 조사는 두지 않는다.
- 계약: `contracts/openapi/case-service.yaml` **0.2.0** (tag `inquiries`). 게이트웨이 `/api/v1/inquiries/**`.
- 참고 화면: `pmart-clients/docs/customer-app-design/. 고객센터/` (메인·채팅 흐름). 이 요청은 그중 "상담사 연결" 자리를 문의 페이지로 바꾼 것이다.

## 1. 고객앱 — 고객센터 문의 페이지

### 들어오는 곳
- 고객센터 메인의 "문의하기"(새 메뉴).
- 주문 상세의 "문의하기"(주문을 고른 상태로).
- 고객센터 채팅에서 AI 가 답하지 못했을 때 — "고객센터에 문의 남기기" 버튼(채팅 화면 요청서에서 따로 안내, `origin: ASSISTANT_HANDOFF`).

### 문의 남기기
`POST /api/v1/inquiries` — 고객 토큰, 헤더 `Idempotency-Key`(UUID)·`X-Source-System: customer-app`.

```json
{ "category": "DELIVERY", "orderId": "ORD-…", "title": "배송 문의", "body": "배송 완료인데 못 받았어요", "origin": "INQUIRY_PAGE" }
```
- `category`: ORDER(주문)·DELIVERY(배송)·PAYMENT(결제)·REFUND(환불)·PRODUCT(상품)·ACCOUNT(회원)·OTHER(기타) — 화면에서 고르게.
- `orderId` 는 선택. 주면 서버가 **본인 주문인지** 확인한다(남의·없는 주문 422 `ORDER_NOT_FOUND`). 주문 선택은 `GET /api/v1/orders`(본인 주문 목록)로.
- `body` 1~2000자, `title` 100자까지.
- 201 응답 = 문의 상세(아래). 같은 `Idempotency-Key` 로 다시 보내면 같은 문의(중복 생성 없음).
- 오류: 403(비회원·운영자 토큰), 422 `ORDER_NOT_FOUND`, 503(주문 확인 일시 불가 — 같은 키로 재시도).

### 내 문의 목록·상세
- `GET /api/v1/inquiries?size=&cursor=&status=` — 본인 문의만, 최근 순. `page.nextCursor` 로 다음 쪽.
- `GET /api/v1/inquiries/{inquiryId}` — 상세 + 답변(`replies[]`). 남의 문의는 404.
- 상태 표시: `RECEIVED`(접수) · `ANSWERED`(답변 완료) · `CLOSED`(종료 — 아직 종료 API 없음).

### 지금 없는 것(화면에서 감안)
- **사진 첨부 없음**(1차). 사진이 필요한 문의는 본문에 적게 하거나, 반품·교환 신청 화면의 사진 첨부를 안내.
- **답변 알림 없음** — 알림 서비스 구현 전. 목록·상세를 다시 불러 확인(당겨서 새로고침 등).
- 고객이 추가 질문을 이어 쓰는 기능 없음 — 새 문의로.
- 문의는 **취소·환불을 직접 실행하지 않는다**(접수만). 취소는 상품준비 전까지 주문 내역에서(ADR-0052).

## 2. 백오피스 — 고객센터 문의 답변 (역할 `CS_AGENT`)

- 새 역할 **`CS_AGENT`**(고객센터 상담원). 운영자 계정 화면에서 지정(역할 이름은 자유 문자열 — auth 변경 없음).
- `GET /api/v1/inquiries?status=RECEIVED` — 전체 문의(상담원만, 그 밖 운영자 403).
- `GET /api/v1/inquiries/{inquiryId}` — 상세(고객 ID 포함).
- `POST /api/v1/inquiries/{inquiryId}/replies` `{ "body": "…" }` — 헤더 `Idempotency-Key`·`X-Source-System: backoffice-web`·`X-Expected-Version`(= 문의 `version`). 답하면 `ANSWERED`. 낡은 버전 409 `VERSION_CONFLICT`, 닫힌 문의 409 `INQUIRY_CLOSED`.
- 메뉴 대응(`docs/architecture/backoffice-role-menus.md`): `CS_AGENT` → "고객센터 문의"(목록·상세·답변).
- 답변 문구에 참고할 상담 문서: 백오피스 업무 도우미(운영자 RAG)가 `docs/business/cs-scripts/` 를 근거로 답한다.

## 3. 확인 (백엔드)
- case-service 시험: `InquiriesIT` 3개(본인만 조회·상담원 답변·버전 충돌, 주문 본인 확인 — 고객 토큰 전달, 회원만 작성) 통과.
- **LAN 배포 2026-11-09**(pc4): `deploy/lan/checks/inquiry_e2e.py` 5/5 — 비회원 문의 403, 상담원(CS_AGENT) 목록 200, 상담원 아닌 운영자 403, 운영자가 문의 남기기 403, 없는 문의 404. 고객 작성·본인 조회는 통합 시험(고객 토큰).
