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
환경변수: `OPTIMIZER_MODEL_PATH`(가중치 파일, 이미지 안 `/models/rf-transformer.ckpt`), `OPTIMIZER_DISABLE_MODEL=1`(시험), `LOG_LEVEL`.

## 상태 (2026-11-13)
- 구현·시험: pytest 10 통과(실제 가중치 시험 2 포함), Docker 이미지 빌드·실행 확인(상태 UP, 고객 3·라이더 2 계산 8ms, 메모리 약 390MB). **이미지 5GB** — rl4co 가 학습용 의존성(lightning·wandb 등)을 끌고 온다. 줄이기는 다음 일.
- dispatch 연결: `pmart.dispatch.ai.base-url`(비우면 OR-Tools 만), 시간 초과 3초, 실패·검증 실패는 OR-Tools 로 대신(지표 `pmart.dispatch.ai.fallback`·`pmart.dispatch.ai.dropped`).
- LAN 배치는 PC 1(PC 5, 포트 8407, CPU). 아직 안 함.
- 남은 것: 성능 검증(같은 문제를 OR-Tools 와 비교 — 원문 §2.1 ③), 필요하면 추가 학습(PC 5 GPU), 이미지 줄이기, GreedRL 비교(나중).
