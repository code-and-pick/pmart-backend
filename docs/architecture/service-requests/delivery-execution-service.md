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

## DE-4. 배송 조회 `packageIds` 가 인계 전에 비어 있음 → 라이더 앱이 모든 포장 QR 을 "이 배송의 포장이 아니에요"로 막음 (2026-11-18, 보낸 영역 mfc — PC 3, **급함**)
- **증상(LAN 2026-11-18 사용자 시험)**: 주문 74089765(`ORD-a6d4e01a-…`, 배송 `DLV-83649921-…`, 포장 `PKG-e52c52d5-…` SEALED). 라이더가 MFC 도착(ARRIVED_AT_STORE) 뒤 포장 QR 을 찍으면 앱이 "이 배송의 포장이 아니에요" — 서버에 인계 요청을 보내지도 않는다.
- **원인**: `ExecutionViews.delivery` 가 `packageIds` 를 **인계 기록(`v.handovers()`)의 포장**으로 채운다 → 첫 인계 전에는 늘 `[]`. 그런데 회신 `docs/handoff/clients/rider-app/2026-11-14-reply-br23-br24-qr-and-drop-info.md` 는 "배송의 포장 목록(`GET /deliveries/{id}` `packageIds`)에 없는 packageId 면 서버에 보내지 말고 '이 배송의 포장이 아닙니다'"라고 안내했고 앱(`delivery_controller.dart` `scan`)이 그대로 막는다.
- 사건은 다 왔다(읽기 확인): mfc.fulfillment.v1 `PackingFinished`·`ShipmentReady`(03:43:45Z, packages[].packageId = `PKG-e52c52d5-…`) — delivery-execution·dispatch 소비 그룹 lag 0, DLT 없음. execution 은 `shipment_package` 에 이미 갖고 있다(`ExecutionEventHandler.onFulfillment` → `putShipmentPackages`).
- **요청**: `packageIds` = **그 배송이 넘겨받을 포장 전부** — `shipment_package`(출고 포장 목록) ∪ 인계 기록 포장(재포장으로 바뀐 새 ID 포함). 출고 포장을 아직 모르면(밀봉 전) 인계 기록만. 계약 `DeliveryExecution.packageIds` 설명도 그렇게. 회수·반납 배송(출고 없음)은 지금처럼.
- 다른 길(앱만 고치기 — 서버 검사에 맡기고 앱 사전 거르기를 빼기)도 되지만 회신과 어긋나 화면 "몇 개 중 몇 개 인계"도 못 그린다 → 서버 쪽을 권함.
- **받는 쪽(PC 3 mfc)**: 할 것 없음(포장 사건은 그대로).
- **구현(2026-11-18, PC 5 — execution 0.23.1, 브랜치 `delivery/de-4-package-ids`)**: 배송 조회 `packageIds` = 출고 포장 목록(`shipment_package`) ∪ 인계 기록 포장(`ExecutionService.View.packageIds`) — 밀봉 전이면 인계 기록만, 회수·반납은 출고가 없어 인계 기록만. 계약 설명 갱신. 시험: 첫 포장만 넘긴 상태에서 두 포장 모두 보임. 앱 변경 없음.

## DE-5. 전달지 정확 좌표 — 주문 사본에 좌표가 없으면 배차 지오코딩 좌표 (2026-11-18, 보낸 영역 platform — 클라이언트 rider-app 요청, PC 5 구현, **급함**)
- **문제**: 라이더 앱 `GET /deliveries/{id}/drop-info`·직원 `GET /deliveries/{id}/drop-location` 의 lat·lng 는 주문 사본(O-5) 좌표 — 고객이 지도 핀 없이 주소를 저장하면 null 이라 라이더는 배차 제안의 대략 좌표(약 1km 격자)로만 핀을 찍는다(11-18 실주문 74089765 부산 삼정타워가 남쪽에 찍힘).
- **사실**: delivery-dispatch 가 배차 때 도로명주소를 serviceability `/internal/address-geocodes` 로 바꾼 정확 좌표를 `drop_lat`·`drop_lng` 에 이미 둔다(`DispatchEngine`).
- **요청**: 주문 사본에 좌표가 없으면 그 배송의 dispatch `drop_lat`·`drop_lng` 를 lat·lng 로(사건 또는 dispatch 내부 조회 — PC 5 가 정함), 출처 `coordinateSource` = ORDER_PIN / ADDRESS_GEOCODE(계약 칸 추가). 클라이언트는 같은 lat·lng 칸이라 앱 변경 없음.
- **PC 1 쪽(같이 함)**: customer-service 0.5.0 — 배송지 저장 때 핀이 없으면 지오코딩 좌표로 채움(`geoSource` GEOCODE). 새 주소부터 주문 사본에 좌표가 생기지만 옛 주소·옛 주문은 DE-5 가 필요.
- 근거: pmart-clients `docs/handoff/rider-app/2026-11-18-backend-drop-exact-coordinates.md`, 회신 `docs/handoff/clients/rider-app/2026-11-18-reply-drop-exact-coordinates.md`.
- **구현(2026-11-18, PC 5 — 이미 PR #183 으로 반영·배포, execution 0.25.1)**: drop-info·직원 drop-location 이 사본 핀이 없으면 dispatch `route-points`(배차 지오코딩 — 반품 회수는 픽업지, 그 밖 도착지) + `coordinateSource`(ORDER_PIN·ADDRESS_GEOCODE). customer 0.5.0(새 주소 좌표 채움)과 함께 옛 주소·주문도 핀이 찍힌다.

## DE-6. "주문하신 곳으로 가고 있어요" 사건 — 묶음 배달에서 다음 차례가 이 고객 (2026-11-19, platform PC 1 → delivery PC 5)
- **구현(2026-11-19 PC 5, execution V23 + dispatch 배정 사건 bundleId·stopSequence)**: 전달 완료마다 같은 라이더의 다음 차례(같은 묶음 순서 → 배정 순, 픽업했고 고객 도착 전)에 한 번. 스키마 `contracts/events/DeliveryHeadingToCustomer.v1.json`.
- **요청한 곳**: notification(고객 휴대폰 알림 — 고객 앱 레퍼런스 `10. 알림/3-3` "주문하신 곳으로 가고 있어요 / 안전하게 배달해드릴게요"). 레퍼런스 3-2 "근처 주문을 함께 배달하고 있어요" 다음 단계.
- **사용자 결정(2026-11-19 "응 해줘")**: 넣는다.
- **필요한 것**: `DeliveryHeadingToCustomer`(토픽 **delivery.delivery.v1**, aggregate Delivery) — 한 라이더가 여러 배송을 들고 있을 때(알뜰·묶음), **앞 배송이 전달 완료(DeliveryCompleted)되고 다음 전달 차례가 이 배송이 될 때 한 번**. 단건 배송(픽업 = 바로 이 고객으로)은 내지 않는다 — 배송 출발 알림이 그 역할.
  - payload: `deliveryId`, `orderId`, `purpose`, `at`. 순서는 execution 이 아는 전달 순서(경로)대로.
- **받는 쪽(PC 1, 만들어 둠 — 2026-11-19)**: notification `deliveryEvents` 가 `DeliveryHeadingToCustomer` → 고객 알림 `DELIVERY_HEADING`(목적지 DELIVERY, 휴대폰 푸시 포함). 스키마 `contracts/events/DeliveryHeadingToCustomer.v1.json` 는 PC 5 가 만든다.

## DE-7. 배송 확인 목록의 상태별 수 — 목록 맨 위 숫자 띠 (2026-11-19, 보낸 영역 backoffice — PC 2, 받는 쪽 delivery PC 5)
- **구현(2026-11-19 PC 5, execution 0.33.0)**: `GET /deliveries/counts` — 모든 상태 0 까지, 정확한 수라 capped false. 게이트웨이 허용 목록 포함(경로는 기존 `/deliveries/**`).

- **요청한 곳**: 백오피스 웹(pmart-clients `docs/handoff/backoffice/2026-11-19-backend-list-status-counts.md`).
- **필요한 것**: `GET /deliveries/counts?<목록과 같은 거르기>` — 직원 `GET /deliveries` 와 같은 거르기(orderNumber·riderId·mfcId·type 등, `state` 제외)로 `state`(ExecutionState)별 수.
- 응답 모양(웹 요청 그대로, case 0.26.0 `StatusCounts` 와 같게): `{"counts":[{"key":"<상태>","count":12,"capped":false}],"asOf":"…"}` — **모든 상태 값을 0 까지 빠짐없이**, 셀 상한을 넘으면 `capped=true`(화면 "N+"), 원천을 못 읽으면 503(화면 "?").
- 참고: bff `views/work-counts`(0.15.0)가 지금 끝나지 않은 상태를 50건 × 10쪽까지 쪽 넘김으로 센다 — 이 수가 생기면 bff 도 이걸로 바꾼다(PC 1 검토 의견 "count API 로").

## DE-8. `ReturnDelivered` 반납 줄에 SKU (2026-11-20, 보낸 영역 mfc — PC 3, MFC PDA BR-52 R13)
- **왜**: mfc-inbound 반납 도착 대기(`GET /return-arrivals`, inbound 0.13.0)의 `lines[].sku` 가 null 이라 점원 PDA 가 상품 바코드를 대조할 수 없다.
- **요청**: `ReturnDelivered`(CLAIM_RETURN·MISDELIVERY_RECOVERY·FAILED_DELIVERY_RETURN) 줄마다 `sku`(+ `orderLineId`·`qty`).
- **구현(2026-11-20, PC 5 — 백엔드 PR, execution V24)**: 계약 `ReturnDelivered.v1.json` 줄에 선택 칸 `sku`.
  - 고객 반품(CLAIM_RETURN): claim 회수 요청 줄의 sku 를 그대로(전에도 실렸다).
  - 오배송 회수·전달 실패 반납: 원래 배송의 인계(`PackageHandedOver`) 줄 sku. 인계 사건에는 원래 sku 가 있었는데 execution 이 줄·수량만 저장했다 — V24 `package_handover.line_skus` 로 저장.
  - **배포 전에 이미 인계된 배송**은 sku 가 없다(그 줄은 지금처럼 null).
  - `ReturnPickedUp` 줄에도 같은 sku 가 붙는다(같은 줄 계산).
- **상태**: 구현(PR, 병합·배포 전).

