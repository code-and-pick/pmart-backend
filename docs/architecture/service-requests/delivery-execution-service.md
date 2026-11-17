# delivery-execution-service 에 대한 요청 (MFC 쪽에서)

delivery-execution-service 는 아직 스캐폴딩(모든 엔드포인트 501)이다. 구현을 시작할 때 아래를 함께 넣는다.

## DE-1. 픽업 완료 판단 (2026-11-08, 사용자 결정 D-6) — **완료 2026-11-13**(계약 0.2.0, `PickupJudgement`)

> 처리: 2·3·4 는 이 서비스가 한다 — PackageHandedOver(점원 확인 TAKEN_BY_RIDER 포함, 같은 규칙) + 출고 포장 목록(ShipmentReady·PackingFinished)으로 모든 포장이 인계됐을 때만 PICKED_UP, 버튼(`POST …/pickups`)은 확인만. 1(포장별 handover-commits 호출·409 다시 찍기 안내)은 라이더 앱 몫 — `docs/handoff/clients/rider-app/2026-11-13-notice-execution-apis.md`.

- **결정**: 라이더 앱의 "픽업 완료" 버튼이 포장마다 MFC 인계 확정(`POST /api/v1/packages/{id}/handover-commits`)을 부르고, 배송 상태 PICKED_UP 은 **그 배송의 모든 포장 `PackageHandedOver` 가 왔을 때만** 바꾼다 — 버튼만 눌리고 재고는 안 넘어간 상태가 생기지 않게.
- **MFC 가 주는 것(구현됨)**: 인계 허가·확정 API(6c, ADR-0047), `PackageHandedOver`(mfc.fulfillment.v1, packageId·shipmentId·deliveryId·riderId·inventoryCommitId·lines). 확정 응답이 UNKNOWN_RESULT 면 MFC 가 같은 인계 ID 로 다시 보내고, 결과는 사건으로 온다.
- **delivery-execution 가 할 것**
  1. 픽업 완료 버튼 = 포장별 handover-commits 호출(라이더 토큰). 일부 포장이 409(만료 등)면 그 포장은 다시 찍기 안내(같은 라이더는 확인 없이, D-1 안 C).
  2. `PackageHandedOver` 구독 → 배송의 포장 전부가 오면 PickupCompleted(PICKED_UP). 버튼만으로 PICKED_UP 하지 않는다.
  3. 점원 확인 TAKEN_BY_RIDER(D-1 — 라이더가 확정 없이 들고 간 포장)로 온 `PackageHandedOver` 도 같은 규칙.
  4. 순서 "가게 도착 → QR → 픽업 완료"는 라이더 앱·이 서비스가 지킨다(D-3 — MFC 는 도착을 보지 않음).

## DE-CASE. 사건함 사건 받아 도움요청에 사건 번호 붙이기 (2026-11-16, 보낸 영역 platform — case R-4 구현 뒤)
- **이제 case-service 가 사건을 연다**(case 0.4.0, LAN 2026-11-16): `DeliveryIncidentRaised` → 사건 RIDER_HELP·DELIVERY_INCIDENT → `CaseOpened`(platform.case.v1, refs 에 `{refType: "incidentId", refId: "<deliveryId>:<요청 ID>"}`·deliveryId). 닫으면 `CaseClosed`(outcome = RESOLVED 등).
- **execution 이 할 것**(R-4 §"case-service 가 생기면 요청한 쪽에서 바꿀 것"): `CaseOpened`·`CaseClosed` 구독(type RIDER_HELP·DELIVERY_INCIDENT 만) → `help_request.case_id`·state(IN_PROGRESS·RESOLVED) 갱신. 그 뒤 임시 "처리함 닫기"(BOD-4 `POST …/help-requests/{id}/resolutions`)는 사건함 닫기로 대신하고 정리(지우거나 사건 닫기로 넘김 — PC 5 판단).
- **구현(2026-11-16, PC 5 — execution 0.5.1)**: `caseEvents` 가 `CaseOpened`·`CaseClosed`·`CaseReopened`(type RIDER_HELP·DELIVERY_INCIDENT)도 받는다. 열림 → 도움요청 `caseId`·IN_PROGRESS(refs deliveryId·requestId, 없으면 incidentId 를 나눔), 닫힘 → RESOLVED(resolvedBy `case:<caseId>`, 메모 = 결과), 다시 열림 → IN_PROGRESS. **처리함 닫기는 남기되 사건이 붙은 도움요청은 409 HELP_REQUEST_HAS_CASE**(사건함에서 닫기) — 사건이 없는 예전 도움요청만 그 경로로 닫는다(PC 5 판단: 지우면 예전 것을 닫을 길이 없다).


## DE-2. 도움요청 대화(#60) — 답장 권한·새 메시지 값·가림·처리 중 시점 (2026-11-16, 보낸 영역 backoffice)
- **왜**: 백오피스 웹 요청(pmart-clients PR #45 `docs/handoff/backoffice/2026-11-16-backend-rider-help-chat.md`)과 PC 5 #60(execution 0.6.0 대화 API)을 맞춰 보니 빈 곳 넷. **사용자 결정(2026-11-16 "진행해" — PC 2 추천대로)**. 대화는 #60 대로 도움요청 쪽(execution)에 둔다.
- **요청**
  1. **답장 권한을 접수 건 규칙에 맞추기**: 답장(POST …/messages) = RIDER_OPS·OPERATOR_ADMIN 만. **CS_AGENT 는 보기만**(접수 건에서 상담원은 보기만 — 사건함 공통 결정), **센터 관리자(MFC_SUPERVISOR)는 답장 없음**(접수 건 쪽에서 RIDER_HELP 를 보지 않음). 접수 건이 **관리자 팀(ORDER_OPS)으로 넘어가면 관리자만** 답장(case 0.7.0 이관 — `CaseTransferred` payload `toGroup` 을 구독해 도움요청에 지금 팀을 기록하면 된다; 같은 토픽 platform.case.v1, DE-CASE 구독에 더하기).
  2. **새 메시지 값**: 도움요청 조회(`GET …/help-requests/{requestId}`, 직원)에 `lastRiderMessageAt`(라이더 마지막 메시지 시각)·`lastSupportMessageAt` 을 싣기. 직원별 읽음은 서버에 두지 않는다 — 화면이 직원별로 기억(백오피스 bff 가 사건함 목록에 붙인다 — PC 2).
  3. **관리자 가림**: 대화에 고객 전화번호·출입 비밀번호가 들어갈 수 있다 — case 메모(0.10.0 `…/maskings`)와 같게 `POST …/messages/{messageId}/maskings`(OPERATOR_ADMIN, 사유 필수) — 글을 지우고(body null) 누가·언제·왜는 남김, **라이더에게도 가려져 보임**.
  4. **"처리 중(IN_PROGRESS)" 시점**: 지금(DE-CASE)은 접수 건이 **열리는 순간**. → **상담원이 집을 때**(`CaseAssigned`)로 — 열렸지만 아무도 보지 않은 상태를 라이더에게 "처리 중"이라 하지 않는다(라이더 앱 요청서 표와도 맞음). 열림 ~ 집기 사이는 지금의 OPEN 그대로, 사건 번호는 열릴 때 붙인다.
- **받는 쪽(backoffice)**: 2 가 나오면 bff 사건함(`case-inbox`) 목록에 RIDER_HELP 건의 `lastRiderMessageAt` 을 붙인다. 웹 화면은 #60 병합 뒤.
- 급한 순서: 1(권한 — 넘긴 건·상담원 답장이 지금 열려 있음) → 4 → 2 → 3.
- **회신(PC 5, 2026-11-17 — execution 0.8.0, V10, 브랜치 `delivery/de-2-help-chat`)**: 넷 다 구현. 1 답장 = RIDER_OPS·OPERATOR_ADMIN(넘긴 건 관리자만 — `CaseTransferred.toGroup` 구독) · 4 처리 중 = `CaseAssigned`(열림·다시 열림·이관은 OPEN) · 2 직원 도움요청 조회 `lastRiderMessageAt`·`lastSupportMessageAt` · 3 `POST …/messages/{messageId}/maskings`(OPERATOR_ADMIN, 사유 필수 — 응답·목록 `masked`·`maskedAt`, 직원에게만 `maskedBy`·`maskReason`, body null). 설정 바인딩 변경 없음(caseEvents 그대로).

## DE-3. 전달 불가 등록과 지원센터 대화 묶기 (2026-11-17, 라이더 앱 요청 §13 — PC 5 구현, 브랜치 `delivery/failure-help-chat`)
- 사용자 결정(2026-11-17) "실패 등록이 대화도 열기": 실패 등록이 같은 ID 의 도움요청(DELIVERY_FAILURE)을 열고(응답 resultId), 라이더 전달 재개 요청(`POST …/failures/resume-requests`)은 실패 기록·대화·직원 배송 목록(`resumeRequestedAt`)에 남는다. execution 0.14.0.
- 백오피스(PC 2): 전달 실패 대기 목록에 `resumeRequestedAt` 표시(반려 판단), 사건함 RIDER_HELP 건이 실패 대화와 같은 건임 — 화면 문구만.
