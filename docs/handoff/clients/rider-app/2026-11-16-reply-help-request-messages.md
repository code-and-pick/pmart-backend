# [pmart-backend → pmart-clients] 회신: 도움요청 대화 API (§11-3) (2026-11-16)

- 보낸 영역: delivery
- 받는 앱: rider_app (지원센터 → 요청 → 대화 화면) · 백오피스는 `backoffice/2026-11-16-notice-help-request-messages.md`
- 근거: pmart-clients `docs/handoff/rider-app/2026-11-16-backend-trip-info-and-signup.md` §11-3, 계약 `delivery-execution-service.yaml` 0.6.0

## 만든 것 — 도움요청 한 건에 딸린 라이더 ↔ 지원센터 메시지
| 할 일 | 호출 |
|---|---|
| 대화 보기 | `GET /api/v1/deliveries/{deliveryId}/help-requests/{requestId}/messages?after=<마지막 seq>` → `{items[], helpState}` — 오래된 것부터, `after` 를 주면 그 뒤 메시지만(4초 확인에 쓰기 좋다), 한 번에 200개까지 |
| 보내기 | `POST …/messages` `{body(1~1000자), photoRefs?(최대 5)}` + `Idempotency-Key`·`X-Source-System` → 201 메시지. 같은 키·같은 본문 재전송은 같은 메시지 |

- 메시지: `messageId`, `seq`, `senderType`(RIDER·SUPPORT), `senderId`(라이더에게는 상담원 메시지의 ID 가 null — "지원센터"로 표시), `body`, `photoRefs`, `createdAt`.
- 사진: 먼저 `POST /deliveries/{id}/evidence-photos` 로 올린 뒤 그 `photoRef` 를 붙인다(이 배송에 본인이 올린 사진만, 아니면 422 PHOTO_NOT_FOUND). 보기는 `GET /deliveries/{id}/evidence-photos/{photoRef}`. 사진 올리기는 배송 상태 제한이 그대로라 전달 완료 뒤에는 글만 보낼 수 있다.
- `helpState` 가 RESOLVED 면 더 보낼 수 없다(409 HELP_REQUEST_CLOSED — "처리가 끝난 도움요청입니다. 새로 도움요청을 보내 주세요"). 사건함에서 다시 열면 IN_PROGRESS 로 돌아와 다시 쓸 수 있다.
- 남의 도움요청은 404.

## 아직 없는 것(§11 의 나머지)
- 1(상담원 답변 칸)은 대화로 대신할 수 있다 — 처리 메모(`resolutionNote`)는 여전히 직원 전용.
- 2(본인 도움요청 목록 §10), 4(배송 밖 문의), 5(새 메시지·처리 결과 푸시)는 이번에 하지 않았다. 새 메시지는 `after` 로 확인한다.
