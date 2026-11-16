# 요청 — 오배송 확인에 귀책·고객 선택 받기 (case-service 0.11.0, ADR-0065)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(지원센터 — 오배송 확인 화면, 상담원·지원센터·관리자)
상태: **백엔드는 LAN 배포됨**(case 0.11.0 — PC 5 작성 #44, PC 1 병합·배포 2026-11-16). 화면만 남음.

> 화면 용어(2026-11-16 사용자 결정): 사건(case) = "접수 건". 아래 API 이름은 백엔드 이름 그대로.

## 1. 정한 것 (ADR-0065, 사용자 2026-11-16)
오배송을 확인할 때 **누구 잘못인지(귀책)**와 **고객이 고른 처리**를 함께 기록한다.

| 귀책 `fault` | 고를 수 있는 처리 `resolution` | 지금 실제로 일어나는 일(2026-11-16 LAN) |
|---|---|---|
| `RIDER` 라이더 실수 | `RESHIP` 재발송(기본) · `REFUND` 전 품목 환불 | 회수 → MFC, 재발송 또는 전 품목 환불(재고 없는 품목만 부분 환불), **라이더 원래 배달료 미지급** |
| `SYSTEM` 지도·시스템 오류 | `RESHIP`(기본) · `REFUND` | 라이더 잘못과 같고, **배달료는 지급** |
| `CUSTOMER` 고객 주소 오입력 등 | `REDELIVER` 다시 배송(기본) · `REFUND` | **아직 뒤쪽 처리 없음**(아래 3절) — 기록만 남고 회수·재발송·환불이 자동으로 일어나지 않는다 |

- 귀책을 보내지 않으면 예전과 같다(회수 + 재발송, 배달료 지급). **화면이 귀책을 고르게 바뀌면 늘 보낸다.**

## 2. API (이미 있음)
`POST /api/v1/cases` 본문(kind `MISDELIVERY`)에 `misdelivery` 를 더한다:
```json
{ "kind": "MISDELIVERY", "subjectType": "DELIVERY", "subjectRef": "DLV-…", "summary": "확인 근거",
  "misdelivery": { "fault": "RIDER", "resolution": "REFUND" } }
```
- 422 `INVALID_MISDELIVERY_RESOLUTION` — 맞지 않는 조합(예: RIDER + REDELIVER, 귀책 없이 resolution).
- 422 `REDELIVERY_DESTINATION_REQUIRED` — CUSTOMER + REDELIVER 인데 `redeliveryDestinationId`(실제 주소 ID) 없음.
- 기록 뒤 접수 건 `refs` 와 `MisdeliveryConfirmed` 에 귀책·선택이 실린다.

## 3. 고객 잘못(CUSTOMER) — 아직 준비 중
- ADR-0065 의 고객 잘못 처리(200m 안이면 원래 라이더가 다시 배송, 넘으면 고객 앱 9,000원 결제 뒤 다시 배송, 취소면 상품값 − 9,000원 환불)는 **주문 쪽 다음 단계**로 남아 있다(order-orchestration CLAUDE.md "다음 단계").
- 다시 배송에 필요한 **실제 주소를 기록해 `redeliveryDestinationId` 를 받는 API 가 아직 없다** — 그래서 CUSTOMER + REDELIVER 는 지금 보낼 수 없다.
- **화면 추천**: 귀책 세 가지는 보여 주되, **CUSTOMER 는 "준비 중 — 지금은 라이더·시스템 잘못만 자동 처리"로 비활성**. 고객 잘못이 분명하면 지금은 귀책 없이 기록하지 말고 관리자와 상의(후속 처리·메모로).

## 4. 화면에 바라는 것
- 오배송 확인 입력에 "누구 잘못인가요?"(라이더 실수 / 지도·시스템 오류 / 고객 주소 오입력 — 준비 중) 와 "고객이 고른 처리"(재발송 / 전 품목 환불). 기본값은 재발송.
- 라이더 실수를 고르면 "라이더 원래 배달료가 지급되지 않습니다" 안내.
- 접수 건 상세에 귀책·선택 표시(`refs`).

## 5. 계약
`contracts/openapi/case-service.yaml` 0.11.0 — `CreateCaseRequest.misdelivery`(`MisdeliveryDecision`), `POST /cases` 설명. ADR `docs/adr/0065-misdelivery-handling-by-fault.md`.
