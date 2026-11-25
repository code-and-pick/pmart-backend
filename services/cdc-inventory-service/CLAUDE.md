# cdc-inventory-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cdc-inventory-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cdc-inventory-service.yaml` → `openApiGenerate` 가 `com.pmart.cdc.inventory.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.cdc.inventory.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:cdc-inventory-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:cdc-inventory-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8601.

## 상태
- 스캐폴딩(2026-10-30, tools/scaffold — 86xx 대역 첫 서비스).
- 상품 등록 슬라이스(2026-10-30): `commerce.product.v1` 의 SkuIssued·ProductVersionPublished → `sku_projection`(물류 규격 units_per_box·boxes_per_pallet·ti·hi 포함, CDC-F-002; 오래된 product_version 무시). 센터 취급 `center_sku_handling` — **레코드 없음 = OFF**, 자기 센터 운영자가 `/cdcs/{cdcId}/sku-handlings`(단건·일괄)로 켠다. 규칙·이력은 mfc-inventory 와 같다. 취급 변경 이벤트는 발행하지 않는다.
- 장부(0.2.0, 2026-11-25, Flyway V3): `stock_lot`(소비기한·식별·최초 인수시각)·`stock_balance`(위치·품질별 현물/예약, 예약은 RELEASED 에만)·`safety_stock`(행 없음 = 미설정 → CONFIG_REQUIRED, 0 으로 대신하지 않음)·`allocation*`·`transit*`·`inventory_commit*`.
  - 초기 보유: 센터·SKU·LOT 당 1회(INITIAL_BALANCE_EXISTS), SKU 투영 필요, 취급 ON 불필요, `lotIdentification` 없으면 UNVERIFIED(적격 아님).
  - 적격 E = RELEASED·VERIFIED·잔여일수 D ≥ max(1, minRemainingDays)(소비기한 당일 차단, Asia/Seoul). 가용·할당 앞 검사 순서: SKU 투영 → 취급 ON → 안전량 설정.
  - 할당: SKU 별 한도 max(0, E'−R'−S) 안에서 FEFO(기한 → 최초 인수 → LOT → 피킹 선반 먼저(0.5.0) → 위치). 부분 PARTIAL, 0 이면 FAILED. 해제는 늦은 LOT 부터. 같은 TRF 다른 버전이 살아 있으면 ALLOCATION_EXISTS.
  - TOUT: TRF 당 1회, 현물 −q·예약 −q·Transit +q. 같은 departureId·같은 줄 묶음 재전송은 기존 commitId. 남은 할당은 호출자가 RESIDUAL_CLOSED 로 해제.
  - 발행(토픽 `cdc.inventory.v1`, outbox): `AllocationResult`, `TransferDeparted`(departureSource SIMULATED 면 봉투 source 도 SIMULATED). TransferDeparted 줄 = 출발 줄 하나: `lpn` 은 LPN 이 하나일 때만(여럿이면 LPN 별 수량을 모르므로 `lpns` 목록만 — mfc-inbound 에는 lpn 없음으로 보인다), `expiryDate` 는 LOT 소비기한.
  - 같은 TRF 의 할당·출발은 권고 잠금(`pg_advisory_xact_lock`)으로 줄 세운다 — 다른 trfVersion 첫 할당 동시 요청도 하나만 들어간다.
  - 아직 계산하지 않음: 품목군 최소 잔여일수(제조일 입력 없음), 승인량 초과 검사(replenishment 없음).
- 내부 이동(0.3.0, 2026-11-25, Flyway V4): `/internal/move-postings` — 같은 SKU·LOT·품질로 위치만 바꾼다(총량·LOT 최초 인수시각 그대로). 줄에 `allocationId` 가 있으면 그 할당이 원위치에 잡은 예약을 함께 옮긴다(`allocation_lot.moved_out/moved_in`, 남은 예약 = 할당 + 옮겨 옴 − 옮겨 감 − 해제 − 출발) — 팔레트 랙 → 피킹 선반 보충(칠곡 CDC-7). 없으면 예약 안 된 현물만. 예약은 RELEASED 에만. 위치 상태는 cdc-location 이 본다(여기서는 안 봄). 원결과 MOVE.
- 재고 스냅샷(0.4.0): `/internal/snapshots?cdcId&sku?` — 지금 장부 그대로(snapshotId 는 이번 조회 식별, 보관 안 함 — 지난 id 는 404). sku 를 비우면 현물 행이 있는 SKU 전부. 취급 OFF·안전량 미설정도 거절하지 않고 handlingActive·safety.state NOT_SET·available null 로 보인다. `lots[].locations[]` = 위치·품질별 현물·예약(시뮬레이터가 칸별 재고를 그린다).
- 피킹 선반 먼저(0.5.0, 2026-11-25): 할당 FEFO 순서 기한 → 최초 인수 → LOT → **PICK_FACE 먼저** → 위치. 위치 유형은 cdc-location `POST /internal/location-lookups`(`LocationClient`, 주소 `pmart.inventory.cdc-location-base-url`, 연결 0.5초·응답 1.5초, 호출마다 새 Idempotency-Key — 없으면 공통 헤더 검사가 400, LAN #462 배포에서 확인)를 트랜잭션·행 잠금 **밖**에서 미리 읽는다. 조회가 안 되면 경고 로그만 남기고 위치 코드 순(랙 01~ 이 선반 61~ 보다 앞)으로 잡는다 — 동선 선호라 할당을 막지 않는다.
- 아직 501: 수령 확인·품질·손실·조정·일일 대사. 게이트웨이·LAN 배치·Helm 미등록.
