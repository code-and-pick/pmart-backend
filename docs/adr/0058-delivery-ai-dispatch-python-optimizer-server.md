# ADR-0058 AI 추천 배차는 Python AI 최적화 서버(GreedRL·RouteFinder)로, 거리·시간은 카카오모빌리티 길찾기로

- 상태: 승인 (2026-11-13, 사용자 — PC 5 delivery 영역 시작 때 "AI 추천 배차는 Python으로 별도의 AI 최적화 서버를 만들고, 그 안에서 GreedRL·RouteFinder를 실행한다. Spring Boot는 이 서버에 계산을 요청한다.")
- 관련: 업무 원문 `docs/business/Delivery-business-logic.md` §2.1 ②·③, §2.2, §5.3. `docs/architecture/service-requests/delivery-kickoff-from-mfc.md` §6. ADR-0050(OR-Tools 사용 범위 — delivery-dispatch 라이더 배정), ADR-0053(OR-Tools 쓰면 JVM). `docs/architecture/tech-stack.md`("별도 Python 런타임은 두지 않는다 — GreedRL·RouteFinder 는 3단계 별도 승인").
- 대체: tech-stack.md 의 "별도 Python 런타임은 두지 않는다" 를 **이 서버에 한해** 바꾼다(그 "별도 승인"이 이 결정). ADR-0050 의 "delivery-dispatch 라이더 배정 = OR-Tools" 는 아래 결정 3 으로 좁힌다.

## 배경
- 원문 §2.1 은 배차 계산을 네 단계로 나눈다: ① 데이터 수집(Spring) → ② 이동거리·시간(Spring + 카카오모빌리티 길찾기, 행렬) → ③ AI 배정안·방문 순서(**Python AI 서비스 — GreedRL 또는 RouteFinder**) → ④ 검증·제안·확정(Spring).
- tech-stack 은 Python 런타임을 두지 않고 GreedRL·RouteFinder 를 3단계 별도 승인으로 미뤘고, 2026-11-10 kickoff 에서 "AI 배정 계산 엔진"이 남은 결정이었다.
- 2026-11-13 사용자가 원문대로 하라고 정했다(결정 1~5 는 사용자 말 그대로, 6~8 은 그 말을 구조로 옮긴 것).

## 결정
1. **배차 방식 두 가지 모두**: AI 추천 배차모드·일반 배차모드(원문 §2.2·§2.4). 반경 단계 제안(1km → 2km → 3km)은 **배차 방식이 아니라 라이더 탐색·제안 정책**이다(2026-11-10 결정 재확인). 어느 모드든 라이더 수락 없이 배정을 확정하지 않는다.
2. **거리·시간 = 카카오모빌리티 길찾기 API 의 도로 거리·예상 소요시간**(기사 → MFC, MFC → 고객, 고객 → 다음 고객 행렬 — 원문 §2.1 ②). Spring(delivery 쪽)이 부른다. **직선거리로 이 기능을 대신하지 않는다** — 키가 없거나 호출이 실패하면 직선거리로 조용히 넘어가지 않고 오류(`CONFIG_REQUIRED`·`DEPENDENCY_UNAVAILABLE`)로 드러낸다. 키는 카카오 디벨로퍼스 REST API 키(2026-11-13 사용자 제공, 길찾기·로컬 API 호출 확인) — 정본은 pmart-config `{cipher}`(PC 1 등록), 다른 PC 는 `deploy/lan/.secrets/kakao-rest-api-key`(git 제외).
3. **알뜰배달 = 여러 건 묶음 배정**(최대 3건 연속, 원문 §5.3). 배정·방문 순서 계산은 **OR-Tools 또는 AI**.
4. **AI 추천 배차 계산은 Python 으로 만든 별도 AI 최적화 서버**에서 한다. 그 서버 안에서 GreedRL·RouteFinder 를 실행한다. **Spring Boot(delivery-dispatch-service)는 이 서버에 계산을 요청만** 한다(입력: 기사·주문 정보, ②의 거리·시간 행렬, 배차 제약 / 출력: 기사별 담당 주문·방문 순서 — 원문 §2.1 ③).
5. **④ 검증·제안·확정은 Spring**(delivery-dispatch): 운행 상태·중복 배정·기존 배송 충돌·배차 제한을 검사하고 제안한다. AI 서버의 결과는 "배정안"일 뿐이고 상태를 바꾸지 않는다.
6. **AI 최적화 서버의 경계**: delivery-dispatch 만 부르는 **내부 계산 서버**(delivery 영역 소유). 자체 DB·Kafka 사건 없음, 게이트웨이에 열지 않음, 상태 없음(요청-응답). 계산 요청 계약은 delivery-dispatch 와 서버 사이 내부 계약으로 `contracts/openapi/` 에 둔다(만들 때).
7. **Spring 쪽은 계산 엔진을 포트 뒤에 둔다**(`application` 포트, `infrastructure` 에 AI 서버 어댑터·OR-Tools 어댑터). 알뜰배달의 방문 순서를 OR-Tools 로 계산하면 delivery-dispatch 는 ADR-0053 대상(JVM) — 넣는 커밋에서 ADR-0053 표·서비스 CLAUDE.md 를 고친다.
8. Gradle 로 빌드하지 않는다(Python 은 자기 의존성 파일 + Docker 이미지). Gradle 쪽에 Maven 등 빌드 체계를 더하는 것이 아니다.

## 덧붙임 (2026-11-13)
- **알뜰배달은 묶음 배정**(사용자 "알뜰배달은 한건씩 배정하지 말고 묶음 배정으로 진행한다"): 같은 MFC 의 알뜰배달(BUNDLED) 1~3건을 한 라이더에게 묶어 제안(배송마다 제안, 같은 bundleId·방문 순서, 수락·거절은 묶음 전체). 지금 계산은 OR-Tools CP-SAT(라이더 ↔ 묶음) — 결정 3·7. 묶을지는 "라이더 → MFC 이동 한 번 절약 vs 뒤 고객 도착 지연"을 비용으로 비교, 어느 배송이든 바로 갈 때보다 20분 넘게 늦으면 안 묶음(설계값, 사용자 확인 필요). 한집·예약배달은 묶지 않는다.
- Python AI 서버 세부의 후보(추천 먼저) — 아래 결정 9~13 으로 확정(같은 날): 이름 `delivery-dispatch-optimizer` / 위치 `pmart-backend/ai-services/delivery-dispatch-optimizer/` / FastAPI + uvicorn + pydantic, uv, `python:3.12-slim` + PyTorch CPU / **RouteFinder 먼저**(ai4co, 관리 중, HF 가중치, 열린 경로·용량·시간창 변형) — GreedRL 은 2022년 이후 정체·CUDA 빌드 위험 / PC 5 CPU 로 시작 / 응답 없음(시간 초과 3초)이면 OR-Tools 배정으로 대신 + 경고.

## 서버 세부 — 결정 (2026-11-13, 사용자 "AI 서버는 추천대로 진행해")
9. **이름 `delivery-dispatch-optimizer`**, 위치 **`pmart-backend/ai-services/delivery-dispatch-optimizer/`**(Gradle `services/` 밖 — 빌드 체계가 섞이지 않는다). delivery 영역 소유. 계약 `contracts/openapi/delivery-dispatch-optimizer.yaml`(내부 계산 API).
10. **FastAPI + uvicorn + pydantic**, 패키지 관리 **uv**(잠금 파일 `uv.lock`), 이미지 **`python:3.12-slim` + PyTorch CPU**. 시험은 pytest.
11. **RouteFinder 먼저**(ai4co, MIT, HF `ai4co/routefinder` 가중치 `checkpoints/100/rf-transformer.ckpt`). PyPI 배포가 확인되지 않아 Git 을 커밋으로 고정해 설치한다. GreedRL 은 나중(비교가 필요할 때).
    - 문제 모양: 알뜰배달 고객 = 고객 노드, MFC = 출발점(depot), 열린 경로(라이더는 MFC 로 돌아오지 않음, OVRP), 용량 = 묶음 최대 3건(고객 수요 1/3). 모델이 묶음과 방문 순서를 내고, 라이더 ↔ 묶음 짝짓기는 서버가 같은 비용식(dispatch `BundleProblem`)으로 한다. 한집·예약배달은 1건 묶음.
    - 모델은 좌표를 쓴다(학습 때 [0,1] 정사각형) — 서버가 MFC·고객 좌표를 [0,1] 로 바꿔 넣고, 도로 거리·시간(카카오모빌리티)으로 지연 한도를 다시 확인해 넘는 묶음은 나눈다. 100노드로 학습한 모델이 5~30노드에서 잘 맞는지는 확인되지 않았다 — OR-Tools 결과와 비교해 검증한다(원문 §2.1 ③ "성능 검증").
12. **실행: PC 5, CPU**(GPU 는 학습할 때만 — ollama 와 나눠 쓰지 않는다). 배포는 PC 1.
13. **응답이 없거나(시간 초과 3초)·오류·검증 실패면 OR-Tools 배정으로 대신**하고 경고 + 지표(`pmart.dispatch.ai.fallback`). Spring 은 AI 결과를 그대로 믿지 않고 검증한다(결정 5): 반경 단계·용량·하나씩 배달·묶음 크기·알뜰배달만 묶음·배송 중복·지연 한도. 틀린 묶음은 버리고 그 배송은 다음 판단에서 다시. AI 서버 주소 설정이 비어 있으면 처음부터 OR-Tools.

## 남은 것
- 모델 성능 검증(OR-Tools 와 같은 문제 비교), 필요하면 추가 학습(PC 5 GPU).
- service-map·architecture-design 에 이 서버 추가(PC 1 이 합칠 때 — `pc-split.md` §5), LAN 배치(placement·compose — PC 1).

## 대안
- **OR-Tools(Java)만**: 이미 승인된 스택이라 런타임이 하나로 끝나지만, 원문 §2.1 ③(GreedRL·RouteFinder)과 다르다 — 사용자가 원문대로를 골랐다. OR-Tools 는 알뜰배달 경로 계산 쪽으로 남긴다(결정 3).
- **Spring 안에서 모델 추론(ONNX·DJL 등)**: Python 런타임을 피하지만 GreedRL·RouteFinder 를 옮기는 비용·검증이 크고 원문·사용자 결정과 다르다.

## 결과
- Python 런타임이 처음 들어온다 — Docker 이미지·의존성 관리·보안 갱신이 Java 와 따로 필요하다.
- 배차 한 번에 카카오 길찾기 호출(행렬)과 AI 서버 호출이 생긴다 — 호출 수 제한(카카오 쿼터 429 → `QUOTA_EXCEEDED`)·시간 초과·캐시는 구현 때.
