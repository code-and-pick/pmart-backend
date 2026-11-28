# 요청 — 상담 접수 화면 옆 "상담 도우미" 칸 (ai-assistant-service 1.4.0)

보낸 영역: platform
받는 앱: backoffice (백오피스 웹 — 고객센터 상담원·운영 관리자)
작성: pmart-backend PC 1, 2026-11-28 — 사용자 "백오피스 운영도우미에서 고객쪽 인입이 왔을때 해당 상담사를 돕는쪽이 필요한것같아 … 클라이언트 작업도 먼가 달라져야할것같고" → "다 해줘"
결정: **ADR-0093**(`docs/adr/0093-cs-consult-assistant-panel.md`), 본인 확인 정책 = 운영자 RAG `docs/rag/rag-docs/backoffice/05-customer-center-cases.md` **제15조**(사용자 승인 2026-11-28)
계약: `contracts/openapi/ai-assistant-service.yaml` 1.4.0 `GET /assistant/consult-guides`(태그 `consult`)
상태: **백엔드 구현·시험 전**(PC 1 로컬 — push·배포 안 함). 배포되면 이 파일에 적는다.

## 무엇이 바뀌나
- 지금 운영 도우미는 상담원이 질문을 써야 한다. 전화·채팅을 받는 중에는 그럴 틈이 없다.
- **상담 접수 화면**(`2026-11-16-request-cs-intake.md` — 전화·채팅·메일 연락 남기기) 옆에 칸을 두고, 상담원이 **문의 종류를 고르는 순간** 도우미가 먼저 보여 준다:
  1. **본인 확인** — 무엇을 어떤 순서로 묻는지(제15조 표·문구)
  2. **그 문의의 정책 근거** — 문의 종류·주문 상태에 맞는 조항 몇 개(문서·절·본문)
- 도우미는 아무것도 실행하지 않는다(취소·환불은 그대로 클레임·주문 메뉴).

## 부르기
`GET /api/v1/assistant/consult-guides?category=<종류>[&memberFound=<true|false>][&orderStatus=<상태>]` (X-Source-System `backoffice-web`)
- `category`: 접수 화면의 문의 종류 그대로 — ORDER·DELIVERY·PAYMENT·REFUND·PRODUCT·ACCOUNT·OTHER
- `memberFound`: 연락한 번호로 회원이 조회됐는지(화면이 고객을 찾은 결과). 응답에 그대로 돌아온다 — 아래 표의 열을 고르는 데 쓴다.
- `orderStatus`: 연결한 주문의 상태 이름(order-service 값 그대로, 예 CONFIRMED·PREPARING·IN_DELIVERY·COMPLETED). 없으면 빼고.
- **고객 ID·주문 번호·전화번호·주소는 보내지 않는다**(받지도 않는다).
- 다시 부를 때: 종류·회원 조회 결과·주문 상태가 바뀔 때마다. 응답은 저장되지 않는다(LLM 없음 — 보통 1초 안쪽).
- 권한: CS_AGENT·OPERATOR_ADMIN. 그 밖 403 → 칸을 숨긴다.

## 응답 보여 주기
```json
{
  "category": "ORDER", "memberFound": false, "orderStatus": "CONFIRMED",
  "identity": { "path": "docs/rag/rag-docs/backoffice/05-customer-center-cases.md", "section": "… > 제15조 (상담 본인 확인과 주문 확인)", "excerpt": "1. 상담원은 …", "asOf": {…} },
  "policies": [ { "path": "docs/rag/rag-docs/order_claim/03-cancel.md", "section": "…", "excerpt": "…" } ],
  "indexSnapshotId": "IDX-…"
}
```
- `identity`(Citation, 없을 수 있음): 맨 위 "본인 확인" 상자. `excerpt` 는 마크다운(표 포함) — 그대로 그린다. 표에서 **지금 문의 종류의 줄**과 **memberFound 에 맞는 열**(조회될 때 / 조회되지 않을 때)을 강조하면 좋다(줄 고르기는 화면이 — 서버는 절 전체를 준다).
- `policies`(Citation 목록, 최대 6): "정책 근거" 목록 — 절 제목(`section` 마지막 마디)·문서 제목 + 펼치면 `excerpt`. 운영 도우미 답변의 인용 카드와 같은 모양이면 된다.
- 503 `DEPENDENCY_UNAVAILABLE`: 칸에 "지금 안내를 불러오지 못했습니다"만 — **접수는 막지 않는다**.

## 자유 질문
- 칸 아래에 질문 입력 — 기존 `POST /api/v1/assistant/policy-questions`(채팅 세션 `sessionId` 로 이어 묻기, ADR-0084). context `screen: "cs-intake"`.
- 접수를 저장하면 그 칸의 세션은 그대로 두거나(내 채팅 목록에 남음) 지워도 된다(`DELETE /assistant/chat-sessions/{id}`) — 화면이 정한다.

## 개인정보
- 도우미 요청·응답에 개인정보가 없다. 본인 확인 결과는 지금처럼 접수의 `identityCheck`(방법만)로 남긴다 — 고객이 말한 번호·주소는 적지 않는다(제15조 10항).
