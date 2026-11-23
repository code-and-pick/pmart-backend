# 회신: 배송이 끝나면 열린 도움요청 자동 완료

보낸 영역: delivery (PC 5)
받는 앱: rider-app — 요청 `pmart-clients docs/handoff/rider-app/2026-11-23-backend-help-auto-close-on-delivery-end.md`

- 작성: 2026-11-23, delivery-execution 0.39.0

## 한 것
1. 배송이 COMPLETED·FAILED·CANCELLED·RETURNED_TO_STORE 가 되면(라이더 완료·직원 대신 완료·자동 완료·반납 완료·실패 확정·픽업 전 취소) 그 배송의 OPEN·IN_PROGRESS 도움요청을 RESOLVED 로 닫는다.
   - `resolvedBy` = `system:delivery-ended`, `resolutionNote` = `배송이 끝나 자동 완료`, `resolvedAt` = 끝난 시각.
   - 덤: 픽업 전 배정이 풀리거나 다른 라이더로 바뀌면, 빠진 라이더의 열린 도움요청만 같은 값으로 닫는다. 메모는 `배송에서 빠져 자동 완료`다. 그 라이더는 배송을 더 볼 수 없기 때문이다.
   - 라이더에게는 원래 `resolvedBy`·`resolutionNote` 를 숨기는데, `system:` 으로 닫힌 것은 라이더에게도 내려 준다. 직원이 닫은 것은 지금처럼 숨긴다.
2. 연결된 case 사건은 **열어 둔다.** 사고·보상 같은 후속은 지원센터가 사건함에서 닫는다. 나중에 사건이 닫혀도 이미 닫힌 도움요청은 그대로다. 사건이 다시 열려도 자동 완료된 도움요청은 다시 열리지 않는다.
3. 닫힌 뒤 직원이 대화를 보내면 409 `HELP_REQUEST_CLOSED`(그대로). 라이더가 보내도 409다. 전달 불가 결정 대기 대화만 예외로 다시 열리는데, 이 경우는 배송이 끝 상태가 아니라서 해당하지 않는다.

## 끝난 뒤 조회(404 여부)
- `GET /deliveries/{id}`: 끝 상태에서도 배정 라이더 본인은 200이다. 끝나도 `rider_id` 가 남기 때문이다. 다만 **픽업 전 배정이 풀리거나 다른 라이더로 바뀐 배송은 빠진 라이더에게 404**다. 그 배송이 더는 본인 것이 아니기 때문이다.
- `GET …/help-requests`, `GET …/help-requests/{id}`, `GET …/help-requests/{id}/messages`: 요청한 라이더 본인 기준이라 배정이 바뀐 뒤에도 200이다.
- 시험: `ExecutionFlowIT.openHelpRequestsCloseWhenDeliveryEnds`, `leavingRidersOpenHelpRequestsCloseOnReassignment`.
