# PMart Backend — Tech Stack

공통 스택은 사용 기준이다. 모든 서비스에 모든 의존성을 추가하지 않는다.
실제 버전의 정본은 `gradle/libs.versions.toml`과 빌드 파일이다.

| 영역 | 기본 기술 | 적용 기준 |
|---|---|---|
| 개발 언어 | Java 21 | Gradle Java Toolchain으로 통일 |
| 애플리케이션 | Spring Boot | 서비스별 독립 애플리케이션 |
| 빌드 | Gradle Wrapper | 시스템 Gradle 대신 Wrapper 사용 |
| 빌드 스크립트 | Gradle Kotlin DSL | `build.gradle.kts`, `settings.gradle.kts` |
| 프로젝트 구성 | Gradle Multi-Project | 서비스·공통 라이브러리를 서브프로젝트로 관리 |
| HTTP API | Spring MVC | 일반 업무 서비스의 기본 웹 스택 |
| API Gateway | Spring Cloud Gateway | 라우팅·요청 제한·공통 접근 제어 |
| 인증·인가 | Spring Security + OAuth2/OIDC | 서비스별 토큰·권한 검증 |
| 데이터베이스 | PostgreSQL | 서비스별 데이터 소유권과 계정 분리 |
| 영속성 | Spring Data JPA + JDBC | 일반 엔티티 처리와 명시적 SQL 처리 구분 |
| DB 마이그레이션 | Flyway | 서비스별 스키마 변경 이력 |
| 캐시 | Redis + Spring Data Redis | 캐시·임시 데이터·위치 최신값 |
| 메시징 | Apache Kafka, KRaft | 도메인 이벤트·비동기 처리 |
| Kafka 연동 | Spring for Apache Kafka | 발행·소비·재시도·오류 처리. 재시도 소진 → `{topic}.DLT` + 서비스 DB `dead_letter`(재처리·폐기 API, ADR-0030) |
| 이벤트 스키마 | JSON Schema(draft 2020-12). Schema Registry 없음(ADR-0009) — 발행 전 `EventSchemaValidator` 로 검증 | `contracts/events/` 정본. 봉투 allOf 구조, 필드 추가만 허용 |
| 배치 | Spring Batch | 대사·집계·계획 계산 등 필요 서비스에 적용 |
| API 명세 | OpenAPI + springdoc-openapi | REST API 계약 문서화 |
| 입력 검증 | Jakarta Bean Validation | 요청 형식과 기본 제약 검증 |
| 장애 제어 | Resilience4j | 타임아웃·제한적 재시도·서킷 브레이커 |
| 로그 | SLF4J + Logback(JSON stdout) → Filebeat → Logstash → Elasticsearch + Kibana (ADR-0031, Elastic Stack) | 구조화 로그와 추적 식별자, 전 서비스 로그 한곳 검색(LAN 3일·색인 1GB·전체 4GB) |
| 메트릭 | Actuator + Micrometer | 서비스·업무 상태 관측 |
| 모니터링 | Prometheus + Grafana + Alertmanager (ADR-0030) | 수집·대시보드·알림(전송 채널은 아직 없음) |
| 분산 추적 | Micrometer Tracing + OpenTelemetry + Zipkin | HTTP·Kafka 추적 연결 |
| 단위 테스트 | JUnit Jupiter + Mockito + AssertJ | 빠른 업무 규칙 검증 |
| 통합 테스트 | Spring Boot Test + Testcontainers | PostgreSQL·Kafka 등 실제 의존 기술 검증 |
| 외부 API 테스트 | WireMock | 외부 시스템 응답·오류·지연 재현 |
| 커버리지 | JaCoCo | 테스트 커버리지 보고서 |
| 품질 분석 | SonarQube | 정적 분석·보안·품질 게이트 |
| 형식 검사 | Spotless | Java·빌드 파일 형식 일관성 |
| 소스 관리 | Git + GitHub | PR·리뷰·보호 브랜치 |
| CI/CD | Jenkins | 빌드·검증·이미지 발행·배포 조정 |
| CI 알림 | Slack | Jenkins가 CI 단계 결과 공지 |
| 컨테이너 | Docker / OCI image | 서비스별 실행 이미지 |
| 이미지 저장소 | Docker Hub 개인 계정, 공개 저장소 (ADR-0012) | 서비스별 저장소 `<계정>/pmart-<service>`, git SHA 태그, digest 배포 |
| 실행 플랫폼 | Kubernetes | 서비스별 배포·확장·상태 확인 |
| 배포 정의 | Helm | 환경별 배포 설정 |
| 비밀정보 | Vault 또는 Cloud Secret Manager | 토큰·PG 키·DB 자격증명 관리 — LAN 은 Vault(ADR-0100·0101) |

## 선택적 스택
- 검색 서비스: Elasticsearch 8.18 + nori(한국어 형태소) + n-gram, 색인은 Logstash 가 도메인 이벤트로(ADR-0031 — 2026-11-03 OpenSearch 에서 변경)
- AI 서비스: Spring AI + pgvector
- 경로·배정 최적화: Google OR-Tools **Java 바인딩** (`transport-routing-service`, `mfc-capacity-service`, `delivery-dispatch-service`(라이더 배정), 피킹 순서 담당 서비스(구현 때 정함) — ADR-0050). **OR-Tools 를 쓰는 서비스는 GraalVM Native 로 만들지 않고 JVM 으로 실행한다(ADR-0053).** 별도 Python 런타임은 두지 않는다 — **예외: 배송 AI 추천 배차용 Python AI 최적화 서버(GreedRL·RouteFinder, delivery-dispatch 가 계산 요청 — ADR-0058, 2026-11-13 승인)**. 그 밖의 Python 전용 모델은 따로 승인 후 검토.
- 설정: Spring Cloud Config Server(`platform-services/config-server`). 정본은 GitHub `code-and-pick/pmart-config`, 서비스 yml 은 부트스트랩만, 환경은 프로필(local·lan·prod), 비밀은 Vault(pc2 `https://192.168.0.34:8200`) — config-server 가 Git + Vault 복합 백엔드로 내려주고 서비스는 Vault 를 직접 읽지 않는다(ADR-0100·0101, 전환 후 `{cipher}`(ADR-0027·0029) 삭제 예정). LAN 실행 중 갱신은 GitHub 웹훅(smee.io 중계) → `/monitor`(spring-cloud-config-monitor) → Spring Cloud Bus(Kafka, ADR-0033). 서비스 검색: local·LAN 은 Eureka(`platform-services/eureka-server`, 클라이언트는 lan 프로필만 켬, ADR-0032), 운영(Kubernetes)은 Service DNS.
- 일반 업무 서비스에 검색·AI 의존성을 일괄 적용하지 않는다.

## 전체 구조 예시
```
pmart-backend/
├── CLAUDE.md
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradlew, gradlew.bat
├── gradle/ (libs.versions.toml, wrapper/)
├── build-logic/            # convention plugins
├── services/
│   ├── product-service/  customer-service/  cart-service/
│   ├── order-service/  payment-service/
│   ├── cdc-inventory-service/  mfc-inventory-service/
│   └── ...
├── platform-services/api-gateway/
├── libraries/
│   ├── common-observability/  common-security/
│   ├── common-messaging/  common-test/
│   ├── common-jwt/  (발급자 표·발급자↔사용자 종류 짝 규칙 — common-security·api-gateway 공용, ADR-0054)
├── contracts/ (openapi/, events/)
├── deploy/helm/
└── Jenkinsfile
```
