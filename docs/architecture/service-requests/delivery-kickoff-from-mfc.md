# 배송 백엔드 시작 안내 — MFC 쪽에서 전달 (2026-11-10)

배송 서비스(delivery-dispatch·delivery-execution·rider·delivery-tracking·delivery-capacity·delivery-serviceability)를 만들기 시작할 때 **먼저 읽는 한 장**. MFC 쪽은 이미 만들어 LAN 에서 돌고 있고, 배송 쪽이 없어서 멈춰 있는 지점과 맞춰야 할 약속을 모았다. 항목별 상세는 아래 링크(받는 서비스 파일)가 정본이다.

## 1. 지금 상태
- 배송 6개 서비스 — **구현됨 2개**: delivery-serviceability(권역 매핑·좌표·주소 판정, 배송 조건 — 고객 주소 등록에 쓰임), delivery-capacity(예약 슬롯 조회·점유 — 주문에 쓰임). **스캐폴딩(501) 4개**: delivery-dispatch·delivery-execution·rider·delivery-tracking. LAN 은 **PC 5**(192.168.0.101, 2026-11-10 PC 3 에서 옮김, DB 새로 — `docs/worklog/2026-11-10.md`). Outbox 커넥터 6개는 PC 5 DB 를 보고 RUNNING(발행만 하면 Kafka 로 나간다).
- MFC 쪽: 피킹 → 포장 → **배차 요청** → 출하 대기(READY) → **라이더 QR 인계** 까지 구현·LAN 배포. 실제 고객 주문 1건(ORD-0e67de38-7acf-3e71-8a28-7aeadd986bb0, 2026-11-10)이 **출고 READY 에서 배차를 기다리는 중** — 배차가 생기면 처음 이어서 시험할 대상(단, §6 시작 위치 참고).
- 이전 사고(2026-11-10): PC 5 로 옮길 때 옛 DB 를 지워 **권역 매핑이 사라졌다**(고객 주소 판정 불가) → 시드(`deploy/lan/seed/Seed.java`, 승인 경로)로 같은 시드 권역을 버전 1 로 다시 발효. 슬롯 점유(slot_hold) 기록도 사라졌다. 구현된 두 서비스의 DB 를 옮기거나 지울 때는 정확한 행 수(`count(*)`)로 확인한다 — 통계 추정값(`pg_stat_user_tables.n_live_tup`)은 0 으로 나올 수 있다.
- 인계 관련 사용자 결정 D-1~D-9 는 모두 결정됨(2026-11-08, `docs/business/flows/package-handover-decisions.md`).

## 2. 받는 서비스별 할 일 (정본 링크)
| 번호 | 서비스 | 할 일 | 순서 |
|---|---|---|---|
| [DD-1](delivery-dispatch-service.md) | delivery-dispatch | `DispatchRequested` 구독 → 배송 생성 → 라이더 배정 → 배정 사건 발행, 취소·조정 | **1순위** — 이게 없어 주문이 READY 에서 멈춤 |
| [DE-1](delivery-execution-service.md) | delivery-execution | 픽업 완료 = 포장별 MFC 인계 확정 호출, `PackageHandedOver` 가 모두 오면 PICKED_UP | 2 |
| [RS-1](rider-service.md) | rider | 라이더 단계 사건(신규배차 제한·일시 중지·계약 종료) → dispatch 가 배정 풀기 | 2 |
| [DD-2](delivery-dispatch-service.md) | delivery-dispatch | 확인된 오배송 회수 라이더 자동 배차(ADR-0052) | DD-1 뒤 |
| [CP-1](delivery-capacity-service.md) | delivery-capacity | `CapacityReduced` 구독 → 영향 주문 처리 | **사용자 결정 필요**(#15) |
| [TR-1](delivery-tracking-service.md) | delivery-tracking | `DeliveryEtaUpdated` | 사용자: 필요 없음(선택) |
| [O-1](order-service.md) | order(배송 아님) | `PackageHandedOver` → 인계 수량·주문 상태 | 인계 시험 전에 같이 |

## 3. MFC 가 이미 주는 것
### 사건 — 토픽 `mfc.fulfillment.v1` (스키마 `contracts/events/<이름>.v1.json`, 목록 `docs/architecture/events.md`)
| 사건 | 언제 | 배송이 쓰는 항목 |
|---|---|---|
| `DispatchRequested` | 점원이 포장 중(또는 READY 뒤) 예상 시간과 함께 요청 — **점원 요청만, 자동 없음(2026-11-13)**. **출고당 한 번** | orderId·shipmentId·mfcId·trigger(MANUAL/AUTO_ON_READY)·estimatedPackMinutes(MANUAL)·estimatedReadyAt·requestedAt |
| `PackingFinished` | 포장 완료(인계 아님) | shipmentId·packages[{packageId, bagCount, weight, volume, alcohol}] — 결품으로 줄어든 내용도 여기 |
| `ShipmentReady` | 출하 대기 | orderId·shipmentId·mfcId·readyAt·packages[]·pickupLocation — `DispatchRequested` 보다 먼저 와도 같은 출고 하나로 |
| `PackageHandedOver` | 라이더 인계 확정 + 재고 차감 뒤 | orderId·shipmentId·deliveryId·packageId·riderId·handoverId·inventoryCommitId·lines[]·handedAt |
| `WorkStopResult` | 주문 중지 판정 | perPackage[{packageId, STOPPED/HANDOVER_IN_PROGRESS/HANDED_OVER}] — 출고 취소면 배차 취소 |

### API — `contracts/openapi/mfc-fulfillment-service.yaml`
- `POST /packages/{packageId}/handover-authorizations` — 라이더 QR 스캔(토큰 **RIDER**, 주체 = riderId). 포장 SEALED·출고 READY 또는 PARTIALLY_HANDED_OVER·배정 라이더여야 함. 실패 코드: `DELIVERY_MISMATCH`·`NOT_ASSIGNED`·`RIDER_MISMATCH`(403)·`FENCE_ACTIVE`·`PACKAGE_INVALIDATED`·`HANDOVER_RESULT_PENDING`.
- `POST /packages/{packageId}/handover-commits` — 인계 확정(같은 라이더). 결과 모름이면 MFC 가 같은 인계 ID 로 다시 보내고 결과는 `PackageHandedOver` 로 온다. `FENCE_EXPIRED` 면 다시 찍기(같은 라이더는 확인 없이 — D-1 안 C).
- 허가 유효시간: 센터 설정 `handover-fence-ttl`, 기본 3분(D-2).

## 4. MFC 가 배송에게서 받으려는 것 — 토픽 `delivery.delivery.v1` (이미 구독 중)
mfc-fulfillment 는 아래 4개로 "이 출고를 누가 가져갈 수 있나"(배정 투영)를 만들고 인계 허가에 쓴다(`HandoverService.onDeliveryEvent`). **이 항목 이름 그대로** 발행해야 한다(스키마 `contracts/events/Delivery*.v1.json`).

| 사건 | MFC 가 읽는 항목 | MFC 동작 |
|---|---|---|
| `DeliveryCreated` | deliveryId, **shipmentId**, orderId | 배송 ↔ 출고 연결. shipmentId 가 없으면 인계 허가가 `DELIVERY_MISMATCH` |
| `DeliveryAssigned` | deliveryId, orderId, riderId, assignmentVersion | 배정. 다른 라이더의 진행 중 허가는 취소 |
| `DeliveryAssignmentChanged` | deliveryId, **newRiderId**(null = 배정 해제), assignmentVersion | 재배정·해제 |
| `DeliveryCancelledBeforePickup` | deliveryId, orderId | 배정 취소 |

- 발행은 Outbox(`EventPublisher`) → Debezium. 같은 aggregate 의 낮은 version 이 늦게 오면 상태 전이는 버린다(`events.md` 순서 규칙).

## 5. 결정된 규칙(배송 쪽에 영향 있는 것)
- ADR-0053: delivery-dispatch 가 라이더 배정에 OR-Tools 를 넣으면 그 서비스는 **GraalVM Native 로 만들지 않고 JVM** 으로 실행(넣는 커밋에서 ADR 표 갱신).
- D-3: MFC 는 라이더 **가게 도착을 보지 않는다**. "도착 → QR → 픽업 완료" 순서는 라이더 앱·delivery-execution 몫.
- D-4: MFC 는 라이더 단계를 직접 보지 않는다 — dispatch 가 일시 중지·계약 종료 라이더의 배정을 풀면(`DeliveryAssignmentChanged`) 인계가 막힌다. 신규배차 제한 라이더는 이미 배정된 것 그대로.
- D-5: 배차 요청 시점 = 점원 요청(포장 중) + 포장 끝까지 없으면 READY 때 자동.
- D-6: PICKED_UP 은 그 배송의 **모든 포장** `PackageHandedOver` 뒤. 버튼만으로 바꾸지 않는다.
- D-7: 인계 뒤 취소 회수비 — 고객 부담 9,000원·고객센터 경유·회사 잘못 면제, 환불은 검수 뒤(claim 쪽).
- 취소: 배차 취소는 그 주문의 **모든 포장이 STOPPED** 일 때만(S3 취소 사가, `cancel-vs-handover.md`). 하나라도 인계됐으면 회수(미구현).
- 주문당 출고 1개(D6-2, 10kg 분할 없음) — 배송 1 ↔ 출고 1 로 시작해도 된다.

## 6. 시작 전 결정(2026-11-10 결정됨)과 남은 것
- **구독 시작 위치 — 결정(2026-11-10, 사용자 "그래")**: delivery-dispatch 의 `mfc.fulfillment.v1` 소비 그룹은 **처음 켜기 전에 시작 위치를 2026-11-10 09:30 KST(00:30Z)로** 맞춘다(`kafka-consumer-groups --bootstrap-server <kafka> --group <dispatch 소비 그룹> --topic mfc.fulfillment.v1 --reset-offsets --to-datetime 2026-11-10T00:30:00.000Z --execute`, 그룹에 실행 중인 소비자가 없을 때). 그 전의 E2E 시험 출고는 건너뛰고 READY 로 기다리는 실제 주문(ORD-0e67de38…)부터 받는다. 같은 출고의 배차 요청이 두 번 와도 배송은 하나(shipmentId 로 멱등) — 그래서 이후 시작 위치와 상관없이 안전하다. 이후 E2E 시험 출고는 정상 흐름(생성 → 중지면 배차 취소)으로 처리한다.
- **문서끼리 다른 값 — 결정(2026-11-10)**: 사건 스키마를 API 에 맞췄다 — `DeliveryCreated.type` = `FORWARD / RETURN / RESHIPMENT`(분할은 유형이 아님 — 도입 때 별도 항목, 오배송 회수 DD-2 는 RETURN), `DispatchFailed.reason` = `NO_RIDER / REPEATED_REJECTION / EXPIRED / SYSTEM_FAILURE`. 포장 상태는 구현이 정본 — `SEALED → INVALIDATED`(재포장·피킹 뒤 중지), `OPEN → VOIDED`(밀봉 때 재고 거절). 상태 문서의 "VOIDED = 취소" 문구를 고쳤다(배송 쪽 영향 없음 — 둘 다 QR 409 `PACKAGE_INVALIDATED`).
- 오배송 확인 사건을 누가 기록하는지(DD-2, ADR-0052 남은 것).
- **배송 구현 범위·방식 — 결정(2026-11-10, 사용자)**:
  1. 첫 범위는 세로 조각 — rider(등록·운행 상태) + tracking(위치 수신·최신 위치) + dispatch(배차 요청 → 배송 생성 → 제안 → 수락 → 배정 사건) → MFC 인계까지. execution 은 그다음.
  2. **반경 단계(1km → 2km → 3km)는 배차 방식이 아니라 "라이더 탐색·제안 정책"**이다(원문 §3.2). 배차 방식은 원문 §2.2 의 **AI 추천 배차모드**와 **일반 배차모드**(§2.4 배달리스트에서 라이더가 골라 수락) 두 가지를 모두 구현한다. 두 모드 모두 같은 서버 배정 결과를 쓰고, 라이더 수락 없이 배정을 확정하지 않는다(§2.2·§3.3). 반경·1분·재시도 2회 10분 간격(§3.2·§3.5)은 설정값, 기본값 = 원문 값.
  3. 제안 전달은 폴링(`listMyOffers`, `pollAfterSec`) + `DispatchOfferSent` 사건. SSE·FCM 은 라이더 앱 때 정한다(원문 §4.1 "별도 결정").
  4. **거리·시간은 원문 §2.1 ② 대로 카카오모빌리티 길찾기 API 의 도로 거리·예상 소요시간**(기사 → MFC, MFC → 고객, 고객 → 다음 고객 행렬). 직선거리로 이 기능을 대신하지 않는다.
  5. **알뜰배달은 원문대로 여러 건 묶음 배정**이고, 배정·방문 순서 계산은 OR-Tools 또는 AI 로 한다(최대 3건 연속, §5.3). OR-Tools 를 넣으면 dispatch 는 ADR-0053 대상(JVM).
  6. 라이더 앱: 사용자가 `code-and-pick/PMart-Delivery-Test`(임의로 만든 시험 앱·서버)를 기반 후보로 제시 — 검토 결과와 남은 결정은 아래.
- 남은 결정(2026-11-10): AI 배정 계산 엔진(원문 §2.1 ③ 은 Python GreedRL·RouteFinder, `tech-stack.md` 는 Python 을 3단계 별도 승인으로 둠), 라이더 앱 기반(위 6), 카카오모빌리티 REST 키 등록(사용자가 별도 터미널에서), 배차 가능 시간 09~24시와 LAN 시험 센터 주문 08~24시 충돌.
- **시험 라이더 토큰 — 결정(2026-11-10)**: 라이더 로그인(auth)·라이더 앱이 아직 없어, 시험은 **LAN 서명키로 만든 시험 라이더 토큰**(`typ=RIDER`, 주체 `RD-claude-*`, 15분, 파일 `deploy/lan/.secrets/rider-token` 은 쓰고 지움 — 고객 시험 토큰과 같은 방식). LAN 전용, 운영 금지. 2026-11-10 부터 `aud: pmart-api` 도 넣는다(ADR-0055 결정 6 — 발급자 표가 aud 를 확인). 실제 라이더 가입·로그인은 라이더 앱을 만들 때(인증 방식은 그때 사용자 결정). auth 에 임시 라이더 로그인 코드는 만들지 않는다.

## 7. 시험
- MFC 쪽 끝까지: `deploy/lan/checks/mfc_e2e.py`(배차 요청·READY·인계 판정까지, 라이더 인계 없음).
- 실제 주문 진행 기록: `docs/worklog/2026-11-10.md`, 추적은 Zipkin `http://192.168.0.40:9411`(Outbox 가 traceparent 를 싣기 때문에 MFC → 배송 사건도 이어질 것 — 배송 쪽 구독이 생기면 확인).
- 근거 문서: `docs/business/flows/package-handover-contract.md`(§5 서비스별 할 일, §7 열린 질문), `package-handover-decisions.md`, ADR-0006·0047·0052, `docs/business/Delivery-business-logic.md`(업무 원문).
