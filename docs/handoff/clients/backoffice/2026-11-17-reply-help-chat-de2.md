# 회신 — 도움요청 대화 보완(DE-2) (delivery-execution 0.8.0)

보낸 영역: delivery(PC 5)
받는 곳: pmart-clients backoffice 웹(지원센터)·backoffice-bff(PC 2)
근거: `docs/architecture/service-requests/delivery-execution-service.md` DE-2(사용자 2026-11-16 "진행해" — PC 2 추천대로)

## 된 것
1. **답장 권한**: `POST /deliveries/{id}/help-requests/{requestId}/messages` 는 지원센터(RIDER_OPS)·관리자(OPERATOR_ADMIN)만. 상담원(CS_AGENT)은 대화 보기만, 센터 관리자는 답장 없음(403). 접수 건을 관리자 팀(ORDER_OPS)으로 넘기면 관리자만.
2. **처리 중 시점**: 도움요청 `state` 는 접수 건이 열려도 OPEN(사건 번호만 붙음), **상담원이 집을 때** IN_PROGRESS. 다시 열기·이관(담당이 비는 때)은 OPEN 으로.
3. **새 메시지 값**: 직원이 `GET …/help-requests/{requestId}` 를 부르면 `lastRiderMessageAt`·`lastSupportMessageAt`. 직원별 읽음은 화면(bff)이 기억.
4. **관리자 가림**: `POST …/messages/{messageId}/maskings` `{reason}`(2~500자, OPERATOR_ADMIN). 응답·대화 목록의 메시지에 `masked: true`·`maskedAt`, 직원에게만 `maskedBy`·`maskReason`, `body` 는 null. 라이더 앱에도 가려진 채 보인다.

## 화면에 반영할 것
- 답장 입력은 지원센터·관리자에게만(상담원은 읽기 전용), 넘긴 건은 관리자만.
- 메시지 메뉴에 "가리기"(관리자) — 사유 입력, 가린 메시지는 "관리자가 가린 메시지"로.
- bff 사건함 목록의 RIDER_HELP 건에 `lastRiderMessageAt` 을 붙여 새 메시지 표시.
