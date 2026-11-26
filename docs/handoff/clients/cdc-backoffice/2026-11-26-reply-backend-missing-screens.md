보낸 영역: cdc(PC 1 D 클론 — CDC 세션)
받는 앱: cdc-backoffice
원 문서: pmart-clients `docs/handoff/cdc-backoffice/2026-11-26-backend-missing-screens.md`(PR #579, 계약 2961c144 기준)
작성: pmart-backend, 2026-11-26
상태: IB·RP **이미 있음(cdc-inbound 0.2.0, LAN 배포 — #520)**, LG-1 **구현 중(cdc-inventory 0.20.0, 다음 PR)**, TR **뒤로(새 서비스 — 범위·경계 정한 뒤)**

# 회신: DC 백오피스 API 없는 화면 — 적치·보충·재고 이동 이력·운송

요청서가 본 2961c144 뒤에 cdc-inbound-service 가 생겼습니다(0.2.0, 포트 8605). 계약은 main 의 `contracts/openapi/cdc-inbound-service.yaml` 을 다시 받아 주세요.

## IB-1·2 적치 — 이미 있음
- 서비스: `services/cdc-inbound-service`(0.2.0). 적치 지시 만들기·조회·취소, 가상 설비가 받아 가서(claim) 결과를 올리면 cdc-inventory 이동 반영·cdc-location 공간 예약 소비.
- **목록**: `GET /cdcs/{cdcId}/putaway-tasks?state=&kind=&size=&cursor=` (OPERATOR, 자기 센터)
  - 최신 순, state·kind 거르기(생략 = 전부), size 기본 50·최대 200.
  - **쪽 나눔이 page/hasNext 가 아니라 cursor 방식**입니다: 응답 `{items, nextCursor}` — 다음 쪽은 `cursor=<nextCursor>`, `nextCursor` 가 null 이면 끝.
  - 줄 = `PutawayTask`(단건 GET 과 같음): taskId·kind·state·sku·lot·qualityState·fromLocationCode·toLocationCode·qty·completedQty·deviceId·createdBy·reason·failureReason·results[]·createdAt·updatedAt.
  - 요청서의 "Q = C + U + K" 분해는 없습니다 — qty(지시)·completedQty(옮긴 것)만. 상품 이름·화면 번호도 아직 없습니다(필요하면 다시 요청 주세요).
- 상태: CREATED·RESERVED(만드는 중 짧은 상태)·DISPATCHED(설비 대기)·IN_PROGRESS·PARTIALLY_COMPLETED·COMPLETED·FAILED·CANCELLED.
- 만들기 `POST /cdcs/{cdcId}/putaway-tasks`, 취소 `POST /cdcs/{cdcId}/putaway-tasks/{taskId}/cancellations`(CDC_SUPERVISOR) — 둘 다 `Idempotency-Key`·`X-Source-System` 필수.
- 경로가 이미 `/cdcs/{cdcId}/…` 아래라 센터 권한 검사는 다른 직원 조회와 같습니다.

## RP-1·2 보충 — 이미 있음(같은 목록의 kind)
- 보충은 따로 "MOV" 자원이 아니라 **같은 작업 표의 `kind=REPLENISHMENT`** 입니다. 보충 목록 = `GET /cdcs/{cdcId}/putaway-tasks?kind=REPLENISHMENT`. 계약의 `/replenishment-moves` 경로는 만들지 않았습니다.
- 자동만 있습니다(수동 보충 생성 없음 — 운영자가 옮기려면 PUTAWAY 지시).
- **산식 입력값(최소·목표·현재)** 은 `GET /cdcs/{cdcId}/replenishment-checks?reason=&limit=` — 피킹 선반마다 마지막 계산: currentQty·minQty·targetQty·openQty(진행 중 보충)·needQty·orderedQty·unitQty(박스 입수)·reason·taskId·checkedAt. 칸별 재고·평면도의 "이 칸 보충 중"은 reason `CREATED`·`OPEN_TASK_COVERS` 로 표시하면 됩니다.
- ⚠ **지금 LAN 에서는 보충 작업이 생기지 않습니다**: 피킹 선반 최소·목표 설정이 아직 없어(규칙 사용자 확인 중) 설정 없는 칸은 건너뜁니다. 박스 입수가 없는 상품은 reason `UNIT_MISSING`. 빈 목록이 정상이니 "보충 없음"으로 그려 주세요.

## LG-1 재고 이동 이력 — 다음 PR(cdc-inventory 0.20.0)
- 원장 줄을 그대로 보여 주는 `GET /cdcs/{cdcId}/stock-movements` 를 만듭니다. 거르기·줄 모양은 요청서대로 하되, 확정되면 이 파일에 덧붙입니다.

## TR-1~3 운송 — 뒤로
- transport-planning·execution 은 새 서비스라 범위·서비스 경계(`service-map.md`)를 사용자와 정한 뒤 시작합니다. 그때까지 출하·운송 메뉴는 지금처럼 "서버 API 없음"으로 두세요. 출발 결과는 기존 `departures`·`lpns` 로.
