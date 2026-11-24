# cdc-location-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `cdc-location-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/cdc-location-service.yaml` → `openApiGenerate` 가 `com.pmart.cdc.location.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행 `LocStateChanged`(토픽 `cdc.location.v1`, outbox). 소비 없음.

## 패키지
`com.pmart.cdc.location.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 점유 수량은 cdc-inventory 정본 — 여기 두지 않는다. 사본을 받기 전에는 `occupiedQty`·`availableQty` 를 null(신선도 UNAVAILABLE)로 돌려주고 0 으로 채우지 않는다.

## 실행
```bash
./gradlew :services:cdc-location-service:check
./gradlew :services:cdc-location-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8602. 설정 정본(pmart-config `cdc-location-service.yml`)은 아직 없다 — PC1 요청(`docs/operations/pc1-todo.md`). 시험은 `src/integrationTest/resources/pmart-config/` 사본으로 돈다.

## 상태
- 2026-11-25 새로 만듦(계약 0.2.0). 위치 마스터:
  - (0.3.0 경로 `/cdcs/{cdcId}/locations…` — 게이트웨이 `/api/v1/locations/**` 는 mfc-location) 등록 `POST /cdcs/{cdcId}/locations`(운영자, DRAFT, 본문 centerId = 경로)·일괄 등록 `POST /internal/location-batches`(2,000행, 전부 또는 전무, `activate` 면 ACTIVE)·조회·목록(구역·층·유형·상태 거르기, 커서 = 마지막 코드).
  - 상태 전환 ACTIVATE·BLOCK·UNBLOCK·DEACTIVATE·RETIRE — version 조건부, 이력 `location_state_history`, `LocStateChanged`. RETIRE 는 DRAFT 만(점유를 확인할 길이 없어 INACTIVE 폐기는 막음), approvalRef 필수.
  - 만들 때는 사건을 내지 않는다.
- 코드 문법(`domain/LocationCode`): 모든 센터 = 계약 패턴 + 첫 구간 = centerId. **칠곡 `CDC01`** = `docs/simulator/cdc/chilgok-dc.md` CDC-3~5·7 — 구역 글자 ↔ 온도대(A 냉동·B 냉장·C~H 상온), 통로 01~60 팔레트 랙(층 5~7·베이 01~40·단 1~4·슬롯 1~2·RESERVE/QUARANTINE/TEMPORARY), 61~89 피킹 선반(층 5~7·베이 01~·단 1~5·칸 1~3·PICK_FACE), 90 DOCK(B1)·91·92 STAGING(5~7, 단 0·슬롯 1), 93~99 비움. 층 배치(어느 층에 어느 구역)는 검사하지 않는다 — 데이터.
- 피킹면 최소·목표량은 PICK_FACE 만, 기본값 없음(비우면 미설정).
- 아직 501: 적치 후보·공간 예약·도크 예약. 게이트웨이·LAN 배치·Helm 미등록. 칠곡 위치 목록 사본 `docs/simulator/cdc/layout/chilgok-locations.json`(32,436칸)은 `ChilgokLayoutIT` 가 실제 규칙으로 모두 넣어 본다 — 사본을 바꾸면 이 시험이 같이 확인한다. 넣기 스크립트 `deploy/lan/seed/cdc_locations.py`.
