# rider-service

서비스 책임의 정본은 `docs/architecture/service-map.md` 의 `rider-service` 절이다. 여기에는 **이 서비스 안에서만 적용되는 규칙**을 둔다.

## 계약
- REST: `contracts/openapi/rider-service.yaml` → `openApiGenerate` 가 `com.pmart.rider.api.generated` 에 인터페이스·DTO 생성. `api/*Controller` 가 구현한다. 계약을 먼저 고치고 코드를 맞춘다.
- 이벤트: 발행·구독 목록은 service-map.md. 발행은 `EventPublisher`(Outbox, Debezium), 소비는 `InboxGuard` 안에서.

## 패키지
`com.pmart.rider.{api, application, domain, infrastructure}` (service-architecture.md). Controller 에 업무 규칙 금지.

## 데이터
- 스키마 변경은 `src/main/resources/db/migration/V*.sql` 로만. 적용된 파일 수정 금지.
- 다른 서비스 테이블·엔티티 접근 금지. 동기 조회는 API, 상태 전파는 이벤트.

## 실행
```bash
./gradlew :services:rider-service:check                      # 단위 + 통합(Testcontainers) + 계약 검증
./gradlew :services:rider-service:bootRun --args='--spring.profiles.active=local'   # deploy/local 기동 후
```
로컬 포트 8403.

## 상태
2026-11-13 DD-1 첫 묶음(계약 0.2.0, V2): 가입·본인 프로필·운행 상태·배차 설정·내부 적격 라이더/라이더 조회 구현. 탈퇴는 501.
- 주체 = 라이더 토큰 sub(RD-…, rider-auth-service). 가입은 전화번호 중복만(면허·주민번호 안 받음 — 사용자 결정 2026-11-10 RS-2), 본인확인 없음(사용자 2026-11-13 '나중에'), 즉시 운행 승인(원문 §1.2). 첫 가입 배차 모드 AI_RECOMMENDED(원문 §2.2).
- 운행 상태 `domain/OperatingRules`: OFF_DUTY 면 신규배차 꺼짐, SUSPENDED·TERMINATED 운행 시작 403, DISPATCH_RESTRICTED 는 신규배차 ON 403, 기준 MFC 는 배달지역 안(비우면 첫 MFC). 변경마다 `RiderStateChanged`(delivery.rider.v1, mfcId 포함).
- 버전 하나(행 version)가 프로필·운행·설정의 X-Expected-Version 기준.
- 적격 라이더 = 운행 중·신규배차 ON·NORMAL·배달지역에 mfcId. 반경은 거르지 않는다(dispatch 가 tracking 위치로 직선 반경 — 사용자 2026-11-13). 용량·주류·연속 거절은 비움.
- **계정 제한(RS-1, 2026-11-14 — 계약 0.3.0, V3 `rider_restriction_history`)**: `POST /riders/{riderId}/restrictions`(restriction·reason 필수, until·evidenceRefs·approvalRef 선택, NORMAL = 풀기)·`GET …`(지금 제한 + 이력 최근 100). **OPERATOR_ADMIN 만**(배송·지원센터 역할 없음 — 생기면 `RiderService.requireRestrictionAdmin`). 승인 화면(BO-20) 전 임시 경로(사용자 2026-11-14 "승인 화면 구현 전에는 운영자 API"). 행 버전 조건 갱신 + 이력 + `RiderRestricted`(delivery.rider.v1, payload previous 포함, actor OPERATOR) 한 트랜잭션. 제한이면 `new_dispatch_enabled=false`, **운행 상태는 그대로**(픽업한 배송은 끝까지 전달 — 위치를 계속 보내게). `until` 이 지나면 `Rider.asOf` 가 NORMAL 로 본다(응답·운행 판정·적격 SQL — 기한 끝에 사건은 없음). `RiderStateChanged` 는 따로 내지 않는다.
- dispatch 가 `RiderRestricted` 를 받아 모든 단계에서 픽업 전 배정을 풀고 재배차(사용자 2026-11-14 — D-4 변경). 컴플레인 누적 → 검토 대상 자동 등록은 RS-3(기준 미정).
- 아직 없음: 진행 중 배송 수(execution 사본 — 그래서 운행 중이면 수단 변경을 막는다), 검토 대상 목록(RS-3), BO-20 승인 연동(`ApprovalDecided`), rider-auth 세션 끊기(ADR-0054 ⑤), 마지막 통신 시각은 운행 상태 바꿀 때만.

- 구현 시작 전: `docs/architecture/service-requests/delivery-kickoff-from-mfc.md`(MFC 와의 약속 한 장, 2026-11-10)와 이 서비스의 요청 파일을 읽는다. LAN 은 PC 5.
