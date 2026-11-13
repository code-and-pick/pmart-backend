# center-master-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `center-master-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/center-master-service.yaml` → `openApiGenerate` 가 `com.pmart.center.master.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.center.master.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:center-master-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:center-master-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8507.

## 센터·영업 시간표 (2026-10-31)
- 변경은 approval-service `CENTER_MASTER` 승인(역할 `CENTER_MASTER_APPROVER`)으로만. 승인 행 attributes = 계약 ApplyCenterChangeRequest 모양(`changeType`·`centerId`·`profile`·`calendar`). 지원: CREATE, UPDATE_PROFILE, UPDATE_CALENDAR(휴폐점은 이후).
- 승인된 내용이 잘못되면(시각 형식·겹침·없는 센터) 반영하지 않고 `approval_application.result` 에 `INVALID:` 사유(재처리 반복 없음).
- 시간표는 종류별(다섯 가지) 요일 시간대 + 휴일 + 임시 제한, 시각 "HH:mm"·끝 "24:00"까지, Asia/Seoul. 승인 안 된 종류는 422 CONFIG_REQUIRED(상시 영업으로 보지 않음).
- `GET /internal/centers/{id}/calendar`(내부 호출): capacity·order 가 고객·비회원 요청 중 부른다(사용자 토큰 전파 방지).
- 사건 CenterMasterChanged·CenterCalendarChanged(앞으로 7일 영업 구간 windows) → `platform.center.v1` 명시.

## 상태
센터 조회·목록, 영업 캘린더(공개·내부), CENTER_MASTER 승인 반영 구현(2026-10-31). 센터 변경 동기 반영(`POST /internal/centers`)·결과 조회는 501, 과거 버전 조회 없음.
- 내부 센터 조회(2026-11-13, CM-1 — delivery PC 5 가 넣음, 계약 0.1.1): `GET /internal/centers/{id}`(내부 호출) — 공개 조회와 같은 응답. delivery-dispatch 가 MFC 좌표를 읽는다.
