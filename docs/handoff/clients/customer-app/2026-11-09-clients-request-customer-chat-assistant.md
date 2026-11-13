# [pmart-backend → pmart-clients] 요청: 고객앱 "고객센터 채팅 상담"(AI 상담) 화면 (2026-11-09)

- 성격: **새 API 안내 + 화면 요청.** 백엔드는 클라이언트를 수정하지 않는다. 화면 구성·시기는 클라이언트가 정한다.
- 결정(사용자 2026-11-09): 고객앱에 AI 상담을 연다(ADR-0051). **회원만**(C2, 비회원은 로그인 안내). 고객마다 **1분 5개·하루 50개**(C3). **질문 원문은 서버에 저장하지 않는다**(C5 — 채팅 기록은 앱 화면에만, 질문 목록 API 없음). 답하지 못하면 **고객센터 문의 페이지**로(C4). 내 주문은 **최근 주문에서 골라** 묻는다(C6). 만족도 조사 없음(C9).
- 계약: `contracts/openapi/customer-assistant-service.yaml` **0.1.0**. 게이트웨이 `/api/v1/customer-assistant/**`.
- 같이 볼 요청서: `../all/2026-11-09-clients-request-customer-inquiry-page.md`(고객센터 문의 페이지 — 여기 handoff 버튼이 연다).
- 참고 화면: `pmart-clients/docs/customer-app-design/. 고객센터/` — 1(메인)·2-1(일반/주문 건 선택)·2-3(일반 문의)·2-4(주문 건 문의)는 그대로 참고. **2-5 "상담사 연결" 자리는 "고객센터에 문의 남기기" 버튼으로**, 2-2-2 만족도 조사는 두지 않는다.

## 1. 화면 흐름

1. 고객센터 메인 → "채팅 상담". 비회원이면 로그인 안내(API 403).
2. 첫 안내: "일부 문의는 AI 가 답변해요" + **일반 문의** / **주문 건에 대한 문의** 선택.
   - 주문 건: `GET /api/v1/customer-assistant/recent-orders?size=5` 로 최근 주문 목록 → 고른 `orderId` 를 이후 질문에 함께 보낸다.
3. 고객이 질문 → `POST /api/v1/customer-assistant/questions` → 답 말풍선(+ 근거 접기, + 버튼들).
4. 채팅 종료는 화면에서만(서버에 세션 없음).

## 2. API

### 질문
`POST /api/v1/customer-assistant/questions` — 고객 토큰, 헤더 `Idempotency-Key`(질문마다 새 UUID)·`X-Source-System: customer-app`.
```json
{ "question": "상품 준비가 시작된 주문도 취소할 수 있나요?", "orderId": "ORD-…" }
```
- `question` 1~500자. `orderId` 는 주문 건 문의일 때만(본인 주문 아니면 422 `ORDER_NOT_FOUND`).
- 응답 시간: 모델 생성이라 **수 초~최대 60초**. 게이트웨이 시간 제한 65초. 입력 중 표시를 띄우고, 네트워크가 끊기면 **같은 Idempotency-Key** 로 다시 보내거나 `GET ?requestId=` 로 찾는다(같은 답, 횟수에 세지 않음).

### 응답(`CustomerAnswer`) — `state` 별 화면
| state | 화면 |
|---|---|
| `ANSWERED` | `answer` 말풍선. `citations[]`(title·section·excerpt)는 "근거 보기"로 접어서 |
| `NO_GROUNDING` | `answer` + **`handoff`** 버튼("고객센터에 문의 남기기") |
| `REALTIME_DATA_REQUIRED` | "어떤 주문이 궁금하신가요?" + **`recentOrders[]`** 를 고를 수 있는 카드로 → 고르면 같은 질문을 `orderId` 와 함께 **새 키로** 다시 보낸다 |
| `ORDER_STATUS` | `answer`(고른 주문의 지금 상태, 정해진 문장) + `order` 카드(statusLabel·도착 예정 시간대) + `handoff` 버튼 |
| `OUT_OF_SCOPE` | `answer`("AI 상담은 취소·환불을 직접 처리하지 않아요…") + `handoff` 버튼. 주문 내역으로 가는 버튼도 같이 두면 좋다 |
| `PROCESSING`(202) | 같은 키가 처리 중 — `Retry-After` 초 뒤 `GET /api/v1/customer-assistant/questions?requestId=<키>` |

- `handoff`: `{ target: "INQUIRY", message, buttonLabel, orderId }` → 버튼을 누르면 **고객센터 문의 페이지**를 열고 `POST /api/v1/inquiries` 를 `origin: "ASSISTANT_HANDOFF"`, `orderId`(있으면) 로 미리 채운다. 질문 내용은 서버에 없으니 앱이 문의 본문 초안으로 넣어 준다(고객이 고칠 수 있게).
- `order` 는 응답할 때마다 새로 읽는다(못 읽으면 없음). 주소·결제 정보는 오지 않는다.
- `answerMode`: GENERATIVE(AI 생성) · BASELINE_EXTRACTIVE(안내 원문 발췌 — "관련 안내를 그대로 보여 드릴게요"로 시작) · ORDER_TEMPLATE · NONE. 화면 구분은 선택.

### 최근 주문
`GET /api/v1/customer-assistant/recent-orders?size=1..10`(기본 5) → `items[]`: orderId·status·statusLabel·orderedAt·itemCount·totalAmount. 요청 제한에 세지 않는다.

### 오류
- 403: 비회원·운영자 토큰, `X-Source-System` 이 `customer-app` 이 아님.
- 409 `IDEMPOTENCY_CONFLICT`: 같은 키에 다른 질문·주문 — 질문마다 새 키.
- 422 `ORDER_NOT_FOUND`: 남의·없는 주문. 422 `CONFIG_REQUIRED`: 안내 문서 색인 전(운영 문제 — "잠시 뒤 다시").
- **429 `RATE_LIMITED`** + `Retry-After`(초): 1분 5개(→ "질문이 너무 빨라요…") 또는 하루 50개(→ "오늘 질문할 수 있는 횟수를 모두 썼어요…"). 서버 `detail` 문구를 그대로 보여도 된다. 이때도 문의 페이지 버튼을 두면 좋다.
- 503 `DEPENDENCY_UNAVAILABLE`: 모델·검색·주문 조회 장애 — 같은 키로 다시.

## 3. 지금 없는 것
- 대화 맥락: 질문 하나씩 답한다(앞 질문을 기억하지 않음). "그건요?" 같은 이어 묻기는 앞 질문을 다시 적게 안내.
- 사진 보내기, 상담원 실시간 연결, 만족도 조사.
- 답변 알림(문의 답변은 고객센터 "내 문의"에서).

## 4. 확인(백엔드)
- customer-assistant-service 시험: 단위 100·통합 22 통과(회원만, 근거 인용, 근거 없음 → handoff, 실행 요청 → OUT_OF_SCOPE, 최근 주문 고르기, 고른 주문 상태 문장, 남의 주문 422, 1분 5개 429 + 같은 키 재전송 안 셈, 하루 50개, 질문 원문 미저장).
- **LAN 배포 2026-11-09**(pc2, 709cc06): 고객 안내 문서 13개 색인(63 조각). `deploy/lan/checks/customer_rag_e2e.py` — 게이트웨이 경로·비회원 403·운영자 403 통과. 실제 답변은 고객앱에서 Google 로그인한 고객 토큰으로 확인 예정.
