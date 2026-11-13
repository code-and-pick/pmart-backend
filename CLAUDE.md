# CLAUDE.md — PMart Backend

Spring Boot 마이크로서비스 / Gradle Multi-Project (Kotlin DSL) / Java 21

## 구조
```
services/           # 업무 마이크로서비스 (독립 Spring Boot 앱, bootJar)
platform-services/  # api-gateway 등 플랫폼 앱
libraries/          # 공통 기술 라이브러리 (jar, 업무 로직 금지)
contracts/          # openapi/, events/ — 서비스 간 계약의 정본
build-logic/        # convention plugin (included build)
gradle/libs.versions.toml  # 의존성 좌표 중앙 관리
deploy/local/, lan/, remote/, helm/  # 로컬 인프라·LAN PC 배포·원격 실행기·Helm 차트 (Jenkinsfile 은 아직 없음)
```
서비스 내부: `com.pmart.<domain>.{api, application, domain, infrastructure}`

## 문서 지도 (설계의 정본, 필요 시 읽을 것)
- 전체 비즈니스 흐름·확정 정책: `docs/business/overview.md`
- **서비스 목록(53개 — ADR-0056 employee-auth·hr 포함)과 책임 경계**: `docs/architecture/service-map.md` — 어느 서비스 책임인지 불명확하면 여기를 먼저 확인하고, 그래도 모호하면 질문한다
- 전체 구조·통신 패턴·사가 목록·단계 계획: `docs/architecture/architecture-design.md`
- 이벤트 카탈로그(발행자 → 구독자): `docs/architecture/events.md`
- 사가·보상 흐름: `docs/business/flows/` — 새 saga를 만들거나 수정하면 같은 커밋에서 갱신한다
- 아키텍처 결정 기록: `docs/adr/` — 결정을 바꾸면 새 ADR로 대체한다
- 공통 용어: `docs/business/glossary.md`, 문서↔서비스 대응: `docs/business/domain-map.md`
- **시뮬레이터 관련**: `docs/simulator/`(README) — 공통 범위 `scope.md`, **MFC 는 `mfc/`**(연결 결정 SIM-1~ `mfc-simulator-integration.md`, 평면 도면 `layout/`), **CDC 는 `cdc/`**. 결정이 생기면 날짜와 함께 적고, 시뮬레이터 관련 결과물은 여기에 둔다(handoff 제외). MFC 근거는 `MFC-business-logic.md`, CDC 근거는 `WMS-business-logic.md` — 섞지 않는다
- **서비스 간 요청**(다른 서비스에 아직 없는 API·사건·화면): `docs/architecture/service-requests/` — 받는 서비스마다 파일, `README.md` 에 전체 목록. 서비스를 구현·수정하기 전에 그 서비스 파일을 읽고, 다른 서비스에 필요한 것이 생기면 같은 커밋에서 여기에 적는다
- 다른 저장소와의 요청·회신: `docs/handoff/`(README) — 클라이언트(pmart-clients)는 `docs/handoff/clients/`, MFC 시뮬레이터(pmart-mfc-simulation)는 `docs/handoff/simulator/`. 회신은 여기에만 쓰고 상대 저장소는 읽기만 한다
- 업무 원문: `docs/business/*-business-logic.md` (확정 정책은 설계가 바꾸지 않는다. 문서에 없는 비즈니스 규칙은 추측하지 말고 질문)

## PC 나눠 작업하기 (2026-11-13)
- 영역: **platform**(PC 1 — 인증·HR·승인·게이트웨이·라이브러리·배포, 그 밖 서비스), **mfc**(PC 3), **delivery**(PC 5). 영역·서비스·계약 주인·규칙: `docs/operations/pc-split.md`. 배포는 PC 1 만.
- 작업 일지는 **`docs/worklog/<영역>/YYYY-MM-DD.md`**(자기 영역 파일에만, 2026-11-14 부터 — 2026-11-13 까지의 일지는 `docs/worklog/YYYY-MM-DD.md` 루트에 그대로 둔다). handoff 파일 이름은 받는 앱 영역(`YYYY-MM-DD-<앱 영역>-<종류>-<주제>.md`)과 첫머리 `보낸 영역:`.
- 영역 인수인계: mfc `docs/mfc/HANDOVER.md`, rag `docs/rag/HANDOVER.md`.
- PC 1 이 아닌 곳에서는 먼저 `docs/operations/dev-on-other-pcs.md` — 직원 토큰 받는 법(로컬 `dev-tokens`·LAN `staff_auth.py`), 시험 계정·비밀번호 파일, 없어진 auth-service 운영자 로그인.

## 상세 규칙 (작업 영역에 따라 자동 로드)
- 빌드/의존성: `.claude/rules/gradle-build.md`
- 서비스 구조·데이터·트랜잭션: `.claude/rules/service-architecture.md`
- API·Kafka 이벤트: `.claude/rules/api-and-events.md`
- 테스트: `.claude/rules/testing.md`
- 보안·설정: `.claude/rules/security-config.md`
- CI/CD: `.claude/rules/ci-cd.md`
- 기술 스택 전체 표: `docs/architecture/tech-stack.md` (필요 시 참조)

## Commands
```bash
./gradlew :services:<name>:test
./gradlew :services:<name>:integrationTest
./gradlew :services:<name>:check
./gradlew :services:<name>:bootJar
```
Windows는 `.\gradlew.bat`. 실행 전 프로젝트 경로와 task 존재 여부를 먼저 확인한다.

설정(ADR-0029): 서비스 `application.yml` 은 부트스트랩만. 설정의 정본은 GitHub `code-and-pick/pmart-config`(로컬 사본 `deploy/lan/.config-repo`, 프로필 local·lan·prod) — 설정을 바꾸면 그 저장소와 각 서비스의 시험 사본(`src/integrationTest/resources/pmart-config/`)을 같이 고친다. 로컬 `bootRun` 전에 `./gradlew :platform-services:config-server:bootRun --args='--spring.profiles.active=local'`.

## 절대 규칙
- 다른 서비스의 구현 클래스·DB·JPA 엔티티에 직접 접근하지 않는다. API 또는 이벤트만.
- 요청 없이 서비스 경계 병합, 새 프레임워크 도입, 프레임워크 주 버전 변경 금지.
- Maven 등 별도 빌드 체계 추가 금지. 새 의존성은 `libs.versions.toml`에 등록.
- 비밀정보를 Git·`application.yml`·이미지에 넣지 않는다. 개인정보·토큰은 로그 금지.
- 명시적 요청 없이 원격 배포·운영 DB 변경·Slack 발송 금지.
- 실행하지 않은 테스트나 배포를 완료했다고 보고하지 않는다.
- `libraries/` 변경 시 영향받는 서비스를 확인하고 보고한다.
