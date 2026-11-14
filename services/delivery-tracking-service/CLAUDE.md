# delivery-tracking-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `delivery-tracking-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/delivery-tracking-service.yaml` → `openApiGenerate` 가 `com.pmart.delivery.tracking.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.delivery.tracking.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:delivery-tracking-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:delivery-tracking-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8406.

## 상태
2026-11-13 DD-1 첫 묶음(계약 0.2.0, V2): 위치 배치 수신·내 마지막 위치·내부 라이더 위치(하나·여럿)·거리 행렬 구현. ETA·실시간 구독은 501(TR-1 — 사용자: 필요 없음/선택).
- 위치: 단말 시각 순 저장, `domain/LocationQuality`(LOW_ACCURACY `pmart.tracking.low-accuracy-m` 기본 100m·TIME_SKEW 시계 앞섬 `max-clock-ahead` 1분 또는 시간 역전). 버리지 않고 표시만, 마지막 위치(`rider_latest_location`)는 더 늦은 정상 점만. 같은 라이더 업로드는 advisory lock 으로 줄 세움.
- 거리 행렬 = 카카오모빌리티 길찾기(`infrastructure/KakaoMobilityClient`, ADR-0058) — 도착지 하나면 다중 출발지, 아니면 출발지마다 다중 목적지. **직선거리로 대신하지 않는다**: 키 없음·401/403 → 422 CONFIG_REQUIRED, 429·400 code -10(카카오 일 사용량 초과의 실제 응답 — 2026-11-14) → 429 QUOTA_EXCEEDED(dispatch 가 quota-backoff 5분), 그 밖 4xx·5xx·시간 초과 503. 구간 결과 코드 ≠ 0 은 NO_ROUTE. 자동차 도로 기준(수단별 경로 없음).
- 키 `pmart.tracking.kakao-mobility.rest-api-key` — pmart-config `delivery-tracking-service-<프로필>.yml` {cipher}(PC 1). 로컬은 환경변수로.
- 아직 없음: 권역 밖(OUT_OF_ZONE) 판정, 갱신 지연 `RiderLocationStale`, 위치 보존 기간, ETA.

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.
