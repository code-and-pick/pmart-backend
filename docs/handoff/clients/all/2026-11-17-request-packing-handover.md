# [pmart-backend → pmart-clients] 요청: 포장 인계 현황 화면 (2026-11-17)

- 보낸 영역: backoffice(PC 2)
- 받는 앱: **mfc-backoffice**(센터 관리자 — 모니터에 띄워 두는 화면) · **backoffice**(본사 역할 — 센터를 골라서 봄)
- 근거: 배송 업무 원문 `docs/business/Delivery-business-logic.md` §10.1(관리자 페이지 "포장 인계 현황"), §3.1(출고대기·인계 구역 화면 — 출고요청·피킹중·패킹중·출고대기를 주문번호로, MFC 관리자가 모니터에 띄움), §3.4(포장마다 인계 시각·라이더), §3.7(일부 인계 — 가져간 포장과 남은 포장). 사용자 2026-11-17 "포장 인계 현황 진행하자", "BFF로 단계표시", 센터 관리자 사용자에 "그래 넣고".
- 계약: backoffice-bff **0.11.0** `GET /api/v1/backoffice/views/packing-handover`(원천 mfc-fulfillment 0.15.0 `GET /shipments` — PC 3 F-9). 이 경로는 전에 501 이었다 — 옛 계약의 `packing`·`handoverFence` 칸은 없어졌다(`shipment`·`packages[].fenceState`).
- 백엔드 상태: 구현·시험 끝, PR(병합·LAN 배포는 PC 1). 배포되면 다시 알린다.

## 1. 누가 어디서
| 역할 | 앱 | mfcId |
|---|---|---|
| MFC_SUPERVISOR(센터 관리자) | mfc-backoffice | 비우면 자기 센터(센터가 여럿이면 400 — 고르게), 다른 센터 403 |
| OPERATOR_ADMIN·CS_AGENT·RIDER_OPS | backoffice "조회·현황" 묶음 | **필수**(비우면 400) — 센터 고르기 |
| MFC_WORKER 만 | — | 403(작업자는 PDA). 메뉴에 넣지 않는다 |

메뉴 배치는 역할별 메뉴 확정(미룸) 때 같이 정한다 — 지금은 두 앱 모두 메뉴 하나로 붙여도 된다.

## 2. 조회
- `state`(반복 가능): `PREPARING`·`READY`·`PARTIALLY_HANDED_OVER`·`HANDED_OVER`·`CANCELLED`·`CLOSED_PARTIAL`. 비우면 끝나지 않은 출고(앞 셋). 기본 화면은 비워서.
- 만든 순(오래된 것부터). `size` 1~50(기본 30), 다음 쪽 `page.nextCursor` → `cursor`.
- 모니터 새로고침 주기는 화면이 정한다(원문 미결 §4.1). 한 번 부를 때 MFC 1회 + 출고 수만큼 배송 조회라, **30초보다 자주 부르지 않기**를 권한다.

## 3. 항목 → 화면
| 칸 | 화면 |
|---|---|
| `orderNumber`(8자리, 없으면 null) | 주문번호 — null 이면 `orderId` |
| `stage` | 단계: REQUESTED 출고요청 · PICKING 피킹중 · PACKING 패킹중 · READY_FOR_HANDOVER 출고대기 · PARTIALLY_HANDED_OVER 일부 인계 · HANDED_OVER 인계 완료. **null 이면 "—"** 에 원천 값(`shipment.state`·`pick.state`·`pack.state`)을 작게(예: 피킹 끝·패킹 시작 전, 패킹 막힘, 취소) |
| `pick`·`pack` | 작업 상태·`blockReason`(막힘이면 강조)·`startedAt` |
| `workOrderKind` = RESHIPMENT | "재발송" 표시 |
| `packageCount`·`handedOverQty.qty`·`remainingPackages` | "포장 3 · 인계 1 · 남음 2"(§3.7) |
| `packages[]` | 펼치면 포장별: 상태·인계 허가(`fenceState`)·라이더 스캔 시각(`handoverAuthorizedAt`)·인계 시각(`handedOverAt`)·받은 라이더(`riderId`) |
| `readyAt`·`dispatchRequestedAt` | 출고대기 된 시각·배차 요청 시각 |
| `deliveryId`·`delivery.state`·`riderId` | 배송 상태(예: ARRIVED_AT_STORE = 라이더 도착)·배정 라이더. 배송이 아직 없으면 null |

- 단계 규칙은 bff 계약 설명에 적혀 있다. 화면에서 단계를 다시 계산하지 않는다.

## 4. 원천 표시
- `unavailableSources` 에 `mfc-fulfillment-service` 면 목록 전체를 못 읽은 것 — "센터 출고를 불러오지 못했습니다"(0건이 아님).
- `delivery-execution-service` 면 배송 칸만 모름(`delivery.source.confidence` UNKNOWN) — 그 칸 "—".
- 개인정보(고객 이름·연락처·주소)·QR 없음.

## 회신
화면 반영 뒤 PR 본문 또는 `pmart-clients/docs/handoff/` 회신으로(항목 1~4 별 수용·대안·보류).
