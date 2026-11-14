# delivery-dispatch-optimizer

AI 추천 배차 계산 서버(Python). 결정은 `docs/adr/0058-delivery-ai-dispatch-python-optimizer-server.md` 결정 9~13. **delivery 영역**(PC 5). delivery-dispatch 만 부르는 내부 계산 서버 — DB·Kafka 사건 없음, 게이트웨이에 열지 않음, 상태 없음.

## 계약
- `contracts/openapi/delivery-dispatch-optimizer.yaml` — `POST /api/v1/internal/dispatch-plans`, `GET /health`. 계약을 먼저 고치고 `app/schemas.py`(pydantic, JSON camelCase)를 맞춘다. 이 계약은 Gradle 프로젝트가 아니라 생성 코드가 없다 — Java 쪽 `AiOptimizerClient` 도 손으로 맞춘다.

## 구조
- `app/main.py` FastAPI(계산·상태 확인, 모델이 없으면 503 MODEL_UNAVAILABLE), `app/planner.py` 모델과 무관한 규칙(묶음 검증·나누기, 라이더 ↔ 묶음 짝짓기 — dispatch `BundleProblem` 과 같은 비용식), `app/routefinder_model.py` RouteFinder 적재·추론.
- RouteFinder(ai4co, MIT, v0.4.0 커밋 고정) — 가중치 HF `ai4co/routefinder` `checkpoints/100/rf-transformer.ckpt`. 구성: `RouteFinderPolicy(normalization="rms", parallel_gated_kwargs={}, encoder_use_post_layers_norm=True, encoder_use_prenorm=True)`(prenorm=False 면 CVRP50 평균 비용 33.2 vs 10.9 — 2026-11-13 확인).
- **가중치는 제한 언피클러로 텐서만 꺼낸다**(`weights_only=False` 금지 — 체크포인트 하이퍼파라미터에 옛 torchrl 객체가 피클로 들어 있어 weights_only=True 로는 안 열린다). 다른 체크포인트로 바꿀 때도 같은 방식.
- 문제 모양: 알뜰배달 고객 = 노드, MFC = 출발점, 열린 경로(OVRP), 용량 = 묶음 최대 3건. 좌표는 같은 축척 [0,1] 로 바꿔 넣는다. 도로 시간(카카오, dispatch 가 줌)으로 지연 한도를 다시 보고 넘으면 묶음을 나눈다.
- 로그에 요청 본문(좌표)을 남기지 않는다 — 건수·시간만.

## 실행·시험
```bash
uv sync                      # Python 3.12 + PyTorch CPU (잠금 파일 uv.lock)
uv run pytest                # 규칙·API 시험 + 실제 모델 시험(-m model, 가중치를 처음엔 HF 에서 받는다)
uv run uvicorn app.main:app --port 8407
docker build -t pmart/delivery-dispatch-optimizer:dev .   # 가중치를 이미지에 넣는다(실행 때 인터넷 불필요)
```
환경변수: `OPTIMIZER_MODEL_PATH`(가중치 파일, 이미지 안 `/models/rf-transformer.ckpt`), `OPTIMIZER_DISABLE_MODEL=1`(시험), `OPTIMIZER_MULTISTART`(기본 1 — 출발점 n개를 한꺼번에 풀고 가장 싼 답, 0 이면 한 번), `LOG_LEVEL`.

## 상태 (2026-11-13)
- 기반 이미지 digest·HF 가중치 revision `4edf73d6…` 고정, betweenSeconds·allowedDeliveryIds 최대 50(계약 0.1.1) — PC 1 검토 L6(2026-11-13). 이미지는 고친 뒤 다시 빌드하지 않았다.
- 구현·시험: pytest 10 통과(실제 가중치 시험 2 포함), Docker 이미지 빌드·실행 확인(상태 UP, 계산 7~8ms, 메모리 약 390MB). 이미지 **2.16GB**(두 단계 빌드 — 처음 5GB 는 uv 캐시·git 이 남았던 것. 가상환경 1.5GB 대부분 PyTorch).
- **성능 검증(2026-11-13)**: `tools/evaluate.py` — 합성 문제 60개 × 2 에서 RouteFinder 가 OR-Tools 보다 배정 22~23% 적고 비용 20~30% 높음(`docs/delivery/ai-optimizer-evaluation.md`). 라이더 수 제한이 없는 VRP 로 학습된 모델이라 — 추가 학습 전에는 LAN 에서 끄고 OR-Tools 로(사용자 결정 대기).
- 지연 한도를 RouteFinder 시간창으로 넣는다(`planner._deadlines` — 도로 초 ↔ 평면 거리는 직선 미터당 도로 초 중앙값으로 환산).
- dispatch 연결: `pmart.dispatch.ai.base-url`(비우면 OR-Tools 만), 시간 초과 3초, 실패·검증 실패는 OR-Tools 로 대신(지표 `pmart.dispatch.ai.fallback`·`pmart.dispatch.ai.dropped`).
- LAN 배치는 PC 1(PC 5, 포트 8407, CPU). 아직 안 함.
- **추가 학습(2026-11-13)** `tools/finetune.py`: 보상 = Σ고객 도착 + λ × 경로 수(공개 가중치는 경로 길이 합). λ 2.0·3000 단계·CPU 17분 → 덜 배정 −22% → −2%, 비용 차이 +8% 안팎 — 아직 OR-Tools 밑이라 LAN 은 계속 끔. 가중치 `models/`(git 제외, PC 5 만). 결과 `docs/delivery/ai-optimizer-evaluation.md` 끝 절.
- **큰 MFC·다중 시작(2026-11-13)**: 20~40건에서 운영 CP-SAT(1 작업자·2초, 모든 조합)는 80~90% 해를 못 내 규칙 기반으로 떨어졌다 — 그때는 RouteFinder 가 나았지만, dispatch 에 **묶음 후보를 가까운 4명으로** 줄이자(`bundle-neighbors`) OR-Tools 가 큰 MFC 에서도 다시 앞선다(배정 같거나 많고 비용 6~8% 낮음). AI 는 계속 끔. 다중 시작은 서버 기본 켬(`OPTIMIZER_MULTISTART`). 결과 평가 문서 끝 두 절.
- **실제 카카오 도로(2026-11-13)** `tools/evaluate.py --road kakao`(구간 저장 `.cache/`, git 제외): 작은 MFC 에서 RouteFinder 배정 −3%·비용 +11% — 합성 때보다 차이 큼. 큰 MFC(2026-11-14, `--pool 45` — 카카오 92번)는 RouteFinder 배정 −7.5%·비용 +18.5% — AI 계속 끔. 다중 목적지 길찾기는 하루 1,000건을 LAN 과 나눠 쓴다 — `--pool`·`--max-calls` 로 아껴 쓴다.
- 남은 것: 라이더 수를 입력으로 넣는 학습(구조 변경), GreedRL 비교. 지금 AI 의 이점은 계산 시간뿐.
