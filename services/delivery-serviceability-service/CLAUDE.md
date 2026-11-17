# delivery-serviceability-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `delivery-serviceability-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/delivery-serviceability-service.yaml` → `openApiGenerate` 가 `com.pmart.delivery.serviceability.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.delivery.serviceability.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:delivery-serviceability-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:delivery-serviceability-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8401.

## 권역 매핑·판정 (2026-10-30)
- 권역 = GeoJSON Polygon/MultiPolygon([경도, 위도]), `ZoneMatcher` 레이 캐스팅. 중첩은 우선순위가 가장 큰 권역이 하나일 때만, 아니면 OVERLAP(임의 선택 금지).
- 매핑 버전: `POST /serviceability-versions`(운영자 초안, 내용 해시) → approval SERVICEABILITY(역할 `SERVICEABILITY_APPROVER`) → ApprovalDecided → 시험 주소 검증 → ACTIVE + `ServiceabilityVersionApplied`(토픽 `delivery.serviceability.v1` 명시). 해시 불일치·시험 주소 불일치는 REJECTED.
- 판정(`POST/GET /serviceability-checks`)은 공개(계약 `security: []`): 비회원 둘러보기·비회원 배송지(customer-service 익명 호출). 주소 원문은 저장하지 않는다.

## 주소 ↔ 좌표·판정 좌표 (2026-10-31, ADR-0023)
- 카카오 로컬 어댑터 `infrastructure/kakao/KakaoLocalClient`(포트 `application/Geocoder`). 키 `pmart.serviceability.kakao.rest-api-key`(pmart-config `delivery-serviceability-service-<프로필>.yml`, {cipher}. 없으면 CONFIG_REQUIRED). 429 → QUOTA_EXCEEDED, 401·403 → CONFIG_REQUIRED, 5xx·시간 초과 → DEPENDENCY_UNAVAILABLE, 400 → 0건.
- `GET /address-geocodes`(유사 검색)·`/address-reverse-geocodes` — 고객·비회원(guest-paths), 저장 없음. 주소 질의·결과는 로그 금지.
- 판정 좌표 = roadAddress 를 정확 일치로 변환한 좌표(판정 트랜잭션 밖에서). 요청 lat·lng(지도 핀)는 쓰지 않는다. 없음·0건·여러 곳 → NEEDS_INPUT(roadAddress), 검색 장애 → UNKNOWN. `TEST_ADDRESS` 만 운영자 토큰으로 요청 좌표 사용(아니면 403).
- 쿼리 파라미터 제약 위반은 공통 ProblemDetailAdvice 가 400 VALIDATION_FAILED(2026-10-31 추가).

## 상태
권역 매핑 버전·좌표 판정 구현(2026-10-30), 카카오 주소 변환·도로명 좌표 판정(2026-10-31). 배송 가능 조건(`/delivery-conditions`, 2026-10-31): 발효 권역의 MFC 만(아니면 404), 확정값만 — 방식 3가지·배송당 10,000g·00:00~08:00 제한, 부피·보냉·수단은 NOT_SET·빈 목록(`DeliveryPolicy`). 비회원 조회 허용.

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.
- 배차용 주소 → 좌표(2026-11-13, 계약 0.1.1): `GET /internal/address-geocodes`(내부 호출) — 판정과 같은 정확 일치 변환(`ServiceabilityService.geocodeExact`). delivery-dispatch 가 주문 배송지 좌표를 얻는다. 저장·로그 없음.
- **승인 반영 회신(RC-1, 2026-11-17 — ADR-0039)**: `applyApproval` 이 승인(APPROVED·PARTIALLY_APPROVED)을 처리한 같은 트랜잭션에서 `ApplicationReceiptRecorded`(토픽 `platform.approval-receipt.v1`) — 발효 APPLIED(domainRef `mapping-version:<v>`), 발효 안 함 REJECTED(사유 = IGNORED·APPROVAL_SCOPE_MISMATCH·TEST_ADDRESS_MISMATCH). 반려(REJECTED 결정)는 회신 없음(pricing 과 같게).
