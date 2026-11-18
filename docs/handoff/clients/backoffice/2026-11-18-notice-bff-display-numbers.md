# [pmart-backend → pmart-clients] 알림: bff 화면 응답에 화면 번호 — 배송 번호·주문번호·라이더 번호 (2026-11-18)

- 보낸 영역: backoffice(PC 2)
- 대상: `web/backoffice`
- 계약: `backoffice-bff.yaml` **0.14.0**(필드 추가만 — 기존 필드 그대로). 병합·배포 뒤 `gen:api`.
- 근거: ADR-0070 화면 번호 규칙(사용자 2026-11-18 승인), 모든 앱 알림 `docs/handoff/clients/all/2026-11-18-notice-display-numbers.md`.

## 1. 무엇이 붙나
화면이 배송·라이더마다 따로 조회하지 않아도 되게, bff 가 원천 값을 그대로 실어 준다. 시스템 ID(`orderId`·`deliveryId`·`riderId`)는 그대로 있다 — 링크와 상세의 "시스템 번호" 칸에 쓴다.

| 화면 | 새 필드 | 값 |
|---|---|---|
| 실시간 배송 현황 `GET /views/live-delivery` | `items[].deliveryNumber`, `items[].orderNumber`, `items[].rider` | 배송 번호 "주문번호-순번"(예 `74939255-1`)·주문번호 8자리는 delivery-execution 값 그대로. `rider` = 배정 라이더 표시(아래), 배정 전이면 null |
| 포장 인계 현황 `GET /views/packing-handover` | `items[].deliveryNumber`, `items[].rider`, `items[].packages[].rider` | 출고의 배송 번호, 배송의 배정 라이더, 포장을 인계 받은 라이더. `orderNumber` 는 0.11.0 부터 있음 |
| 접수 건 화면 `GET /views/case-inbox` | `items[].linked[].displayNumber`, `items[].linked[].orderNumber`, `items[].riders[]` | 연결 배송은 배송 번호·그 배송의 주문번호, 연결 주문은 주문번호(도움요청 칸은 null). `riders[]` = 접수 건이 가리키는 라이더(refs `riderId`) |
| 통합 조회 `GET /views/unified-lookup` | 주문·배송 `summary.orderNumber`, 배송 `summary.deliveryNumber` | 원천 값 그대로 |

**라이더 표시 `RiderDisplay`** = `{ riderId, riderNumber, nameMasked }` → 화면 `김*수 · 482913`.

## 2. 비어 있을 때
- 화면 번호가 아직 없는 옛 기록·번호 전 주문은 null입니다. ADR-0070 대로 시스템 ID 끝 8자리를 회색으로 보이고 "번호 없음"을 붙이면 됩니다.
- 라이더 번호는 rider-service 에서 화면 응답마다 한 번 읽습니다. rider 를 못 읽으면(장애, 또는 센터 관리자처럼 라이더 조회 권한이 없는 역할의 403) `riderNumber`·`nameMasked` 만 null 이고 `riderId` 는 그대로입니다. 이때 `unavailableSources` 에 `rider-service` 가 들어갑니다. 화면의 나머지는 정상입니다.
- 탈퇴한 라이더는 rider 찾기에서 빠지므로 번호·이름이 null 입니다.

## 3. 화면에서 할 일(제안)
- 목록·표의 배송·주문·라이더 칸을 화면 번호로 바꿉니다. 배송은 번호 옆에 종류(`type` — 배송·재발송·반납·회수)를 붙입니다.
- 접수 건 목록의 대상 칸은 `linked[].displayNumber` 를 씁니다.
- 회신은 필요 없습니다. 모양이 다르게 필요하면 이 폴더에 요청을 남겨 주세요.
