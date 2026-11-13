# ADR-0053 OR-Tools 를 쓰는 서비스는 GraalVM Native 이미지로 만들지 않고 JVM 으로 실행한다

- 상태: 승인 (2026-11-10, 사용자 "일단은 or-tools 라이브러리가 필요한 서비스들은 네이티브 이미지로 하지 말고 자바가상머신에서 실행되도록 예외를 둬...그리고 이 내용 꼭 기록해두고!!!!")
- 관련: ADR-0028(개발 베이스 temurin JDK, 운영은 GraalVM Native 로 따로 정함) — 그 "운영 = Native" 계획에 **예외**를 둔다. ADR-0050(OR-Tools 사용 범위). `docs/architecture/or-tools-usage.md`.

## 배경
- 운영 이미지는 GraalVM Native 로 만들 계획이다(ADR-0028, 사용자).
- OR-Tools(`ortools-java 9.15.6755`)는 C++ 엔진을 운영체제별 **미리 빌드된 공유 라이브러리**로 jar 안에 담아(리눅스 `.so` 약 100개, 합쳐 약 80MB — `libortools.so.9`·`libjniortools.so`·protobuf·SCIP·HiGHS·Abseil 등) 실행 때 임시 폴더에 풀어 JNI 로 올린다(2026-11-10 jar 내용 확인).
- Native 이미지에서는 이 구조 때문에 손이 더 가고, 문제가 **빌드가 아니라 실행 때** 드러날 수 있다:
  1. JNI 설정(C++ 쪽이 찾는 Java 클래스·필드)과 CP-SAT 의 Protobuf 리플렉션 설정이 빠지면 계산을 부르는 순간 오류·프로세스 종료.
  2. jar 가 없으므로 `.so` 를 이미지 리소스로 넣거나 정해진 경로에 두고 올리게 바꿔야 한다. 못 찾으면 피킹 경로는 대체 처리(`NEAREST_FALLBACK`)로 조용히 넘어가 **눈치채기 어렵다**.
  3. `.so` 가 glibc 기준이라 완전 정적(musl)·distroless-static 은 불가.
  4. OR-Tools 클래스가 빌드 때 초기화되며 네이티브 라이브러리를 올리려 하면 빌드 오류(실행 때 초기화 지정 필요).
- 이 프로젝트에서 OR-Tools 를 Native 로 빌드해 본 적은 없다. Native 로 바꿔도 OR-Tools 계산 성능은 같다(C++ 엔진).

## 결정
1. **OR-Tools(`libs.ortools.java`)에 의존하는 서비스는 Native 이미지로 만들지 않는다. JVM(temurin 21) 컨테이너로 실행한다.** 다른 서비스가 Native 로 바뀌어도 이 예외는 유지한다.
2. 대상(2026-11-10):
   | 서비스 | OR-Tools 용도 | 상태 |
   |---|---|---|
   | `mfc-capacity-service` | CP-SAT 작업 배정 추천, Routing 피킹 방문 순서 | 사용 중(LAN) |
   | `delivery-dispatch-service` | 라이더 ↔ 배송 배정(ADR-0050) — AI 추천 배차의 임시 계산(CP-SAT, ADR-0058) | **사용 중(2026-11-13, DD-1)** |
   | `transport-routing-service` | TMS 운송 경로(ADR-0050) | 서비스 없음 — 만들면 대상 |
   | (후보) replenishment 등 | 선형·정수계획 | OR-Tools 를 넣으면 대상 |
3. **판정 규칙은 의존성이다**: 서비스 `build.gradle.kts` 에 `libs.ortools.java` 가 있으면 JVM. 새로 OR-Tools 를 넣는 서비스는 같은 커밋에서 이 ADR 의 표와 그 서비스 `CLAUDE.md` 에 적는다.
4. Native 빌드 설정(운영 준비 때 새 ADR)을 만들 때는 **이 서비스들을 빌드 대상에서 빼는 장치**를 같이 넣는다(예: `ortools-java` 의존이 있으면 Native 작업을 거부·건너뜀). 사람이 기억하는 데 기대지 않는다.
5. JVM 으로 남는 서비스의 운영 이미지 베이스(JDK 그대로 / JRE / 다른 최소 이미지)는 Native ADR 때 함께 정한다. 그때까지는 ADR-0028 의 개발 베이스 그대로.

## 대안
- **모든 서비스 Native(OR-Tools 포함)**: JNI·`.so` 포함·glibc 기반 이미지·실행 확인(대체 처리로 나오면 실패로 보는 시험)이 필요. 검증 비용이 크고 실패가 늦게 드러나 지금은 하지 않는다. 나중에 따로 시험해 성공하면 새 ADR 로 이 결정을 바꾼다.
- **OR-Tools 계산만 별도 JVM 서비스로 분리**: 서비스 경계가 늘어난다(요청 없이 경계 변경 금지). 하지 않는다.

## 결과
- 운영에서 OR-Tools 서비스는 기동이 느리고(수 초) 메모리를 더 쓴다 — 감수한다.
- Native 와 JVM 이 섞인 운영이 된다. 이미지 빌드·배포 스크립트가 두 방식을 모두 다뤄야 한다.
- 개발 PC(Windows)의 OR-Tools 시험 실패(A6)는 이 결정과 무관 — 그 시험은 리눅스(Docker/WSL)에서 돌린다.
