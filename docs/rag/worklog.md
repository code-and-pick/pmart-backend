# RAG 작업 일지

- 대상: `ai-assistant-service`(RAG, [`README.md`](README.md)).
- 2026-11-04~05 기록은 일반 작업 일지(`docs/worklog/2026-11-04.md`·`2026-11-05.md`)에서 RAG 부분만 모아 다시 정리했다. 원래 기록은 그 일지에 남아 있다.
- 앞으로 RAG 작업은 이 파일에 쓴다.
- 검토 원문은 [`codex-opinion/`](codex-opinion/README.md), 검증 자료는 [`verification/`](verification/2026-11-05/README.md)에 있다.

---

## 2026-11-04

### 계획 (검토 1~3차 반영)
- 요청: 3단계로 미뤄 둔 RAG 중 1차 범위를 앞당겨 구현. 범위는 내부 운영자의 정책·업무 절차 질의응답과 근거 표시다. 실시간 조회·명령·클라이언트 수정·LAN 배포·외부 모델 연결은 빼고 시작했다.
- 계획을 쓰고 Codex 검토 10개 → 2차 7개 → 3차 6개를 반영했다. 1차의 4·5·6번(트랜잭션 경계, 인용 실패 처리, FORBIDDEN_SCOPE 추론)은 계획의 결함이었다.
  - 계획: `codex-opinion/2026-11-04-ai-assistant-plan.md`
  - 원문: `codex-opinion/…review-1~3.md`

### 15:30~17:10 1차 구현 (ADR-0036)
- 정리 3가지:
  - 실패는 오류 코드별 422/503
  - 출처는 `backoffice-web` 고정, 이력 검색은 SUPERSEDED 만
  - 스냅숏 기록도 선점 조건부, INGEST 세션 잠금과 SNAPSHOT 전환 잠금 분리
- 빌드: `settings.gradle.kts` 등록, 카탈로그 `spring-ai 2.0.2`(bom·`spring-ai-model`, 이 서비스만). 해석 결과 Boot 4.0.9 · spring-core 7.0.10(다른 서비스와 같음).
- 계약 `ai-assistant-service.yaml` 1.0.0.
- `V1__init.sql`: vector·pg_trgm, 표 `index_snapshot`·`index_source_file`·`doc_chunk`·`embedding_cache`·`policy_answer`.
- domain: 지위·생애·의도 분류·멱등 해시·출처 검사·순위·발췌·인용 검사.
- infrastructure: JDBC 저장 3개, `LocalHashEmbeddingModel`, 색인(manifest·청크 분할·대체 판정·적재).
- application:
  - `PolicyQuestionService`: 선점 → 분류 → 스냅숏 고정(공유 잠금, 조건부) → 트랜잭션 밖 검색·답변(하드 타임아웃) → 조건부 완료/실패
  - 발췌·생성 답변
  - `KnowledgeJobs`: 색인·정리·보존
- 색인 대상 `knowledge/manifest.yml`: 60 문서, ADR-0015 → Commerce override. Gradle `ingestKnowledge -Prepo=`·`pruneSnapshots`.
- 설정:
  - 설정 저장소 `ai-assistant-service{,-local,-lan}.yml`(8508)
  - 게이트웨이 `/api/v1/assistant/**`
  - lan DB 비밀번호는 비워 둠(배포 결정 뒤 `{cipher}`)
- 로컬 전용 `deploy/local/ai-assistant/docker-compose.yml`(pgvector/pgvector:pg17, 15433). 공용 compose 는 그대로 뒀다.
- 문서: ADR-0036(제안), service-map·architecture-design 표기, 서비스 CLAUDE.md, 클라이언트 안내 `docs/handoff/clients/backoffice/2026-11-04-clients-request-ai-assistant.md`.
- 시행착오:
  - 통합 시험 컨테이너를 `@Container` 로 두면 시험 클래스마다 멈춰, 공유 컨텍스트가 꺼진 포트를 잡았다 → 정적 start 로 바꿨다.
  - jsonb::text 의 `": "` 공백 형식이 시험 치환 SQL 과 맞지 않았다 → 정규식으로 고쳤다.
- 시험(실행함):
  - `:services:ai-assistant-service:check` 통과 — 단위 83, 통합 29
  - 게이트웨이 check 통과
  - `config-copies.sh check` 통과
- 검색 기준선: 문서 60·청크 1824, 질문 25, recall@5 0.76 · MRR 0.628(**문서 경로 기준** — 11-05 명시).
- 커밋·push `a387410`(18:00). pmart-config `7676cb8` 은 같은 날 18:05 LAN 배포 작업 때 push 했다(서비스는 LAN 에 배포하지 않음).

### 17:20~17:50 검증 마무리 (외부 검토 요청)
- 전체 `./gradlew check --continue`(13분 47초): mfc-capacity `ServiceBootIT` 1건 실패(Kafka 시험 컨테이너 종료 코드 126). 그 서비스만 재실행해 6/6 통과. 나머지는 모두 통과.
- `dependencyInsight`: spring-ai-bom·model·commons·template-st 2.0.2, spring-boot-starter 4.0.9, spring-core 7.0.10. `config-copies.sh check` 통과.
- 로컬 HTTP(인증 우회 없음):
  - 구성: 로컬 postgres·kafka + config-server·auth-service·ai-assistant(local)
  - 인증: 로컬 전용 관리자 `admin`/`pmart-local`(ADR-0018:23) 토큰
  - 대표 질문 R1~R5 모두 200 ANSWERED(발췌 기준선)
  - 재전송 같은 answerId, requestId 회수 200, 토큰 없음 401, 다른 출처 403
- 미검증:
  - 게이트웨이 경유 호출
  - `src` 있는 토큰의 HTTP(통합 시험만)
  - 실제 모델(의미 검색·생성 충실도·환각·주입 방어)

### 20:30~21:30 BR-46 질문 목록 (클라이언트 요청 BR-44~48 묶음)
- 근거:
  - 클라이언트 요청서 `../pmart-clients/docs/handoff/clients/2026-11-04-backend-ai-assistant-contract.md` §4
  - 계약 1.0.0 대조 결과 남은 항목: 질문 목록·원문 열람·상태 확인 경로·LAN 배포
- `GET /assistant/question-history`(계약 1.1.0): 본인·출처 범위, 완료 답변은 현재 권한 재검사.
- 원문 열람 없음, actuator 비공개, LAN 배포 보류.
- 회신 `docs/handoff/clients/archive/2026-11-04-clients-reply-br44-br48.md`.
- 커밋·push `bcb2fd4`(20:46).

---

## 2026-11-05

### 오전 검증 보강 (검토 4·5)
- 09:23 현재 HEAD 에서 다시 실행:
  - ai-assistant check 통과(단위 83, 통합 30 — BR-46 시험 포함)
  - dependencyInsight 같은 결과
  - config-copies 통과
- 09:35 전체 `./gradlew check --continue`: 12분 7초, **첫 실행 통과**(재실행 없음). 수정 뒤 126 은 다시 나지 않았다.
- 증거 정리:
  - 세 커밋(`a387410`·`bcb2fd4`·pmart-config `7676cb8`)은 11-04 에 이미 push 됐다. "커밋 안 함"은 검증 단계에서 새 커밋이 없다는 뜻이었다.
  - 두 저장소 모두 작업 트리가 깨끗하고 origin 과 차이가 없었다.
  - BR-46 근거와 diff, Kafka 수정 diff·근거를 정리했다.
  - 실패표의 정답 경로를 전체 이름으로 복구했다.
- 정리 중 **BR-46 페이지 결함 발견**: 권한 재검사로 빠지는 행이 있으면 다음 쪽 행이 누락될 수 있었다. 기능 추가 금지 지시가 있어 보고만 했다.

### 오후 검토 6 반영
- **BR-46 페이지 결함 수정**:
  - 페이지 계산을 `domain/HistoryPager` 로 분리했다.
  - 보이는 행을 size+1 개째까지 찾아 그때만 nextCursor 를 준다. 숨김 행이 연속돼도 누락·중복이 없다.
  - 회귀 시험:
    - `HistoryPagerTest` 8개: A·B·C·D, 연속 숨김·배치 경계, 256 조합 전수 등
    - `PolicyQuestionFlowIT.questionHistoryKeepsPagingWhenTheNewestAnswerIsHidden`
    - 통합 시험은 수정 전 코드로 실패 → 수정 후 통과를 확인했다.
- **검색 평가**:
  - 기존 지표를 "문서 경로 기준"으로 명시했다(결과 본문 그대로).
  - "절 기준"을 추가했다: recall@5 0.60 · MRR 0.387.
  - 정답 절은 원문을 읽어 expected 문서 안에서만 정했다. 검색 결과에 맞춰 넓히지 않았다.
  - 정답 절이 색인에 있는지 시험이 대조한다.
- **Kafka 시험 컨테이너 수정(`869a6b1`)**: "추정 원인에 대한 방어적 수정"으로 기록했다. 재현하지 못했고 당시 로그가 없다.
- `:services:ai-assistant-service:check` 통과(단위 91, 통합 31). 공용 코드 변경이 없어 전체 빌드는 다시 돌리지 않았다.
- 검토 자료를 `verification/2026-11-05/` 에 모았다(임시 폴더·build 에만 있던 로그·diff·평가 결과).
- RAG 문서 폴더 `docs/rag/` 를 만들었다(사용자 요청). `codex-opinion/` 을 저장소 루트에서 이 폴더로 옮겼고, RAG 작업 일지를 이 파일로 분리했다.
- 커밋·push·LAN 배포·외부 모델 연결은 보류했다.

## 2026-11-09
- 사용자 결정: 고객용으로도 확대(D1 재확인), **pgvector 를 백오피스용·고객용 따로**(C7 — 앞의 추천 "함께, 청크별 접근 범위로 거름"을 대체), 임베딩은 Qwen3-Embedding-0.6B·BGE-M3 를 우리 문서로 비교 뒤 선택(D3). `planning/2026-11-07-references-review.md` 결정 표에 반영.
- 사용자 결정: 추천대로 서비스 분리(고객용 `customer-assistant-service` 새로 만듦), RAG 전용 pgvector 컨테이너 하나에 DB 둘, LAN 배치 pc2(PC 를 한 대 더 들일 수 있음), 모델은 Ollama. → ADR-0051(승인), service-map §8·architecture-design 50개, CLAUDE.md.
- LAN 확인(읽기만): 세 PC PostgreSQL 모두 pgvector 없음, PC 넷 같은 사양(i5-11400F·32GB·RTX 2060 6GB), 컨테이너 메모리 pc2 7.8·pc3 11.7·pc4 10.2GB.
- 색인 목록(`manifest.yml`)에 빠져 있던 ADR-0037~0050 과 새 0051 추가(RealDocumentsIngestTest 가 ADR 개수 대조). 같은 시험의 낡은 기대값(주문 사가 문서 구현 날짜가 모두 10-31) 을 절별(1.4 = 10-31, 2.0.1 = 11-07)로 고침. ai-assistant check 통과(단위 91, 통합 31).
- pc2 Docker GPU 확인: 기존 이미지로 `nvidia-smi` 일회 컨테이너(`--rm`) — RTX 2060 6144MiB(사용 861MiB), CUDA 13.3. Ollama 를 컨테이너로 GPU 에서 돌릴 수 있다.
- LAN pc2 에 RAG 전용 `rag-pgvector`(pgvector/pgvector:0.8.6-pg17, 컨테이너 포트 15433, LAN 미공개)·`ollama`(ollama/ollama:0.40.10, GPU, CPU 4·메모리 6GB 상한, LAN 미공개) 배포. DB·계정 `ai_assistant_service`·`customer_assistant_service`(서로 접속 권한 없음 확인), 확장 vector 0.8.6·pg_trgm 1.6. Ollama 로그 "inference compute … RTX 2060 CUDA". 비밀 `.secrets/rag-db-{operator,customer}-password`(init-secrets.sh).
  - 첫 시도 실패: `-p 15433` 만 주면 엔트리포인트의 첫 초기화 임시 서버가 5432 로 떠 초기화 스크립트가 못 붙음 → `PGPORT=15433` 으로 고치고 새 볼륨을 지우고 다시 만듦.
  - pc2 의 log-shipper·host-disk 는 자동으로 줄 뒤에 붙는 구성요소라 고정 IP 가 두 칸 밀림(.25→.27, .26→.28) → IP 충돌을 피하려고 그 둘을 먼저 다시 만든 뒤 새 컨테이너를 띄움. pc3·pc4 생성 파일은 변화 없음. 기존 서비스 재시작 없음.
- 임베딩 비교(D3): PC1 임시 Ollama 로 `RetrievalEvalIT -PevalOllamaModel=` — 절 기준 recall@5 해시 0.52 · **Qwen3-Embedding-0.6B 0.76** · BGE-M3 0.68, 문서 기준 0.68 · 0.84 · 0.84. 추천 Qwen3(절 기준 앞섬, 639MB, 검색 빠름). 질문 25개라 2문항 차이 — 한계는 `verification/2026-11-09/embedding-compare/README.md`. 시험 전용 `OllamaEvalEmbedding`, 운영 코드 변경 없음. 해시 기준선은 색인 문서가 늘어 0.76 → 0.68(문서 기준).
- 사용자 결정: 임베딩 = Qwen3-Embedding-0.6B(ADR-0051·D3 갱신).

### 운영자 RAG LAN 배포(pc2, 2026-11-09 오후)
- 코드: provider=ollama(`OllamaModels`) — 임베딩 Qwen3-Embedding-0.6B(질문에만 지시문 `embedQuery`, `/api/embed` 직접 호출: Spring AI OllamaEmbeddingModel 2.0.2 가 options·keep_alive 를 빠뜨려 시험으로 발견), 대화 qwen3:4b(생각 끔·JSON·문맥 8192). 호출 제한 25초 < 처리 60초(기동 검사). 시험: 단위 OllamaModelsTest 3, 통합 OllamaProviderIT(WireMock — 요청 형식) — ai-assistant check 통과.
- LAN: `rag-pgvector` 서비스 계정 `ai_assistant_service`(비밀 {cipher}), 색인 75파일·1,966청크(일회 작업 `ai-assistant-ingest`, 약 3분), `lan.sh rag-pull·rag-warm·rag-ingest`.
- 겪은 것: ① 첫 모델 적재 26~30초 > 호출 제한 → `rag-warm`. ② `NUM_PARALLEL 2` 면 대화 모델 5.4GB·일부 CPU → 1. ③ 6GB GPU(화면 0.86GB 사용)에 대화+임베딩이 함께 안 올라가 서로 밀어냄 → **임베딩은 CPU**(num_gpu 0, 문맥 2048 — 994MB), 대화는 GPU 100%(3.9GB). ④ `compose run` 이 앱 고정 IP 를 써서 충돌 → 별도 작업 서비스. ⑤ config-server `ConfigCipherTool` 이 형식 오류 때 평문을 출력 → 줄 번호만 출력하게 고치고 그 비밀번호를 바꿈(LAN 전용, 사용 전). ⑥ 새 서비스 첫 배포 뒤 게이트웨이가 몇 분간 503(Unable to find instance) — 이후 정상.
- 확인(`deploy/lan/checks/rag_e2e.py`): 6/6 기대대로, 2~4초. 결품·봉투·서비스 간 인증 정답, 근거 없음·실시간 NONE. **"배정 추천 목표 순서"는 피킹 방문 순서로 잘못 답함**(인용은 했으나 주제 다름 — 인용 검사가 문장 겹침만 봐서 통과). 질문 모음으로 생성 충실도 평가 필요.
- LAN 색인은 git 이 없어 출처 커밋이 비어 기록된다(render 가 `REVISION` 파일은 두었으나 색인기가 아직 읽지 않음).

> 이 아래는 2026-11-09 밤 **다시 적은 것**이다. 위 절을 넣으면서 "## 남은 것" 제목을 실수로 지워, 그 뒤 일지 추가가 붙을 자리를 못 찾고
> 조용히 빠졌다(커밋 3f72337·4c66069·3407f83·54575c7·39aa708·20617aa·eabbb76 의 메시지는 일지를 적었다고 했으나 이 파일은 바뀌지 않음).
> 내용은 그날 대화·보고서(`verification/2026-11-09/answer-eval/`)에서 옮겼다.

### 답변 정확도 평가 1차(LAN qwen3:4b)
- 질문 22개(`eval/answers.json` — 정답 사실은 원문을 먼저 읽고 정함), 채점 `deploy/lan/checks/rag_answer_eval.py`, 결과 `answer-eval/report-1-before.md`.
- 엄격 채점 13/22, 사람 판정 15/22(A04 자동 완료 안 함·A06 토스페이먼츠 결제 인증 = PG 는 뜻이 맞음). 조건 빠짐 3(A05 '초과', A15 '칠곡', A17 '쿠폰 적용 전'), 틀림 4(A09 TMS 문서로 답, A11 정본 위치 흐림, A14 근거 못 찾음, A22 주입 질문에 엉뚱한 발췌 — 지시문 유출은 없음).

### 2차(7dbec7a — 답변 지시 7·8, 지시 무력화 질문 범위 밖)
- 검색 가중 12조합 비교 — 문서 단위로는 차이 없어 그대로 둠(이 판단은 5차에서 답 절 기준으로 정정).
- 엄격 14/22, 사람 17/22. A22·A11·A17 고쳐짐. **새 문제 A20**: 근거 없는 "직원 휴가 신청"에 권한·승인 문서를 엮어 절차를 지어냄 — 임계값 0.18(해시 기준선 값)이라 의미 임베딩에서는 거의 모든 질문이 통과.

### 3·4차(af7383f)
- 답 없는 질문 8개 추가(N01~N08 — 색인 문서에 없는 주제, grep 확인) → 30문항.
- 근거 없음 임계값 맞추기(`answer-eval/threshold-calibration.md`): 답 있는 질문 최저 1위 0.426, 답 없는 질문 최고 0.407 → LAN 0.40(pmart-config cea140a). 0.415 면 다 나뉘지만 여유 0.01 이라 쓰지 않음.
- 게이트웨이 응답 제한 10초 < 서비스 처리 60초 → assistant 라우트만 65초(pmart-config 98b0058, Bus 반영 확인). Spring AI 대화 모델 기본 재시도가 같은 입력에서 되풀이되는 Ollama 500 을 네 번 더 불러 60초를 다 씀 → 재시도 끔(시험 추가).
- 4차(`report-4-final.md`): 엄격 22/30, 사람 25/30. A20 지어낸 답 사라짐. N01(연차)은 임계값 바로 위라 관계없는 발췌.

### 8B 비교(같은 30문항)
- qwen3:8b — pc2 6.6GB, CPU 37%/GPU 63%. 사람 판정 26/30(4B 25), 응답 8~22초(4B 1.4~8초). **사용자 결정: 4B 유지**(ADR-0051 기록). `report-5-qwen3-8b.md`.

### 5~7차(4B 결정 뒤)
- 답 절 기준 평가 추가(answers.json `sections`): A09·A14 는 정답 문서는 왔지만 **답이 적힌 절이 상위 5 밖**이었다(앞의 "검색은 맞고 생성이 틀림"을 정정).
- 지위 가중 ×0.5 면 답 절 적중 15 → 18/19, 검색 평가 절 recall 그대로 → LAN(pmart-config 1266fde). 발췌 전환 하한 0.45 → N01 해결.
- 6차 A13 이 업무 원문 OMS 8.2(ADR-0049 가 바꿨다고 적은 옛 규칙)를 섞음 → manifest override `supersededBy`, OMS 8.2 결품 문단만 SUPERSEDED(시험 추가), 재색인(a0fb262).
- 배포 중: 서비스 지정 배포가 의존 컨테이너까지 다시 만들던 것 → `--no-deps`. `rag-warm` 의 `ollama run` 이 원격에서 표준입력 끝을 기다려 멈춤 → `</dev/null`(20617aa).
- **7차(`report-7-final.md`)**: 엄격 24/30, 사람 **26/30** — 근거 질문 정답 15, 조건 빠짐 2(A05·A15), 틀림 2(A09 TMS 를 고름, A11); 답 없는 질문 11/11. 1.7~4초.

### 재시작 원인 — PC1 LAN 감시(watch.sh)(eabbb76·cba170a)
- 증거: pc2 컨테이너 생성 시각 그대로(07:10·07:24), 시작 시각만 09:43:24·09:53:24(UTC) = 재시작. PostgreSQL 정상 종료 기록. pc2 sshd 에 09:43:23~25 PC1 접속(그때 내 쪽은 SSH 없는 평가 스크립트만). PC1 `~/.pmart-lan-watch/restart-pc2` 와 watch.log 의 `restart rag-pgvector ollama …`.
- 원인: watch.sh 가 목록에 없는 구성요소를 Spring 앱으로 보고 LAN `/actuator/health` 로 점검 → LAN 포트 없는 rag-pgvector·ollama 는 늘 실패 → 10분마다 재시작(모델이 내려가 평가 중 503·500). 배포 때 감시 목록에 넣지 않은 누락. 앞서 "멈춘 작업의 남은 셸"로 짐작한 것은 틀렸다.
- 고침: SSH 로 컨테이너 안에서 `pg_isready`·`ollama list`. 고친 뒤 14분(감시 2회) 재시작 없음 확인. placement.conf 에 규칙 주석.

### 그 밖
- 시험 모델 삭제(사용자 요청): pc2 `qwen3:8b`(5.2GB), PC1 평가용 Ollama 의 `bge-m3`. 남은 모델은 `qwen3:4b`·`qwen3-embedding:0.6b`.
- 카카오: 주소 ↔ 좌표(카카오 로컬, ADR-0023)는 LAN 에서 비회원 세션으로 실제 호출해 정상 확인. 고객앱 지도 흰 화면은 앱(카카오 지도 Android SDK)·카카오 콘솔 쪽 — 백엔드 변경 없음.

### 고객센터 상담 대응 문서(2026-11-09 밤)
- 사용자 요청: 상담 대응 원고를 PMart 기준으로 다시 써서 저장소에 넣고 운영자 RAG 에 색인 — `docs/business/cs-scripts/`(README + 01~10). 대본·절차는 `[설계안]`, PMart 업무 문서·ADR 에서 옮긴 절만 `[확정 정책]`·`[채택 설계]`, 근거 없는 운영 기준(보상·시간 기준·환불 기간 문구 등 15곳)은 `[결정 필요]`(사용자 11-10 확인 예정).
- manifest: domain cs, PROPOSAL, precedence 1(업무 원문과 다르면 원문이 앞섬).

### 고객용 RAG 1차 — customer-assistant-service(2026-11-09 밤, 60ecff5·709cc06)
- 사용자 결정: 고객 안내 문서 "결정 필요" 6건 추천대로 → `docs/business/customer-faq/` 1.0 승인. C3 1분 5개·하루 50개, C5 질문 원문 저장 안 함 — 둘 다 Claude 추천대로. C2 회원만, C4 고객센터 문의 연결, C6 최근 주문 고르기(앞서 결정).
- 구현: 운영자 RAG 를 복사해 출발(공용 라이브러리는 아직 — ADR-0051 기록). 계약 0.1.0, 포트 8509, DB `customer_assistant_service`(pc2 rag-pgvector). 고객 말투·지위 표시 없음·지위 가중 0, 실시간 질문은 최근 주문 고르기 → 고른 주문은 정해진 문장(모델에 주문 정보 안 넘김), 요청 제한 429, 질문 원문 컬럼 없음. 절 경로는 H1(문서 제목)부터.
- 시험: 단위 100·통합 22. 처음 통합 시험이 멈춘 원인 — 답변 ID 41자 > varchar(40), 시험 HTTP 클라이언트(Apache)가 429 Retry-After(8,447초)를 따라 기다림 → JDK 클라이언트.
- LAN pc2: 새 서비스가 log-shipper·host-disk 의 고정 IP 를 밀어 "Address already in use" → 두 보조 컨테이너를 차례로 다시 만들고 배포. 색인 작업이 웹 없는 기동에서 429 처리기 빈 때문에 실패 → 웹 앱에서만(709cc06). 색인 13 파일·63 청크. `customer_rag_e2e.py` 3/3(게이트웨이 경로·비회원 403·운영자 403 — 서비스가 낸 403). 답변 확인: 사용자 허락(2026-11-09, 이번 확인 1회)으로 LAN 서명키로 시험 고객 토큰(`CU-claude-rag-test`, 15분, 주문 없음)을 만들어 9문항 — 맞음 6(취소·봉투값·근거 없음·주문 고르기·실행 요청·주류), 6번째 질문이 1분 5개 제한에 걸려 429(제한 정상 동작, 1분 뒤 다시 → OUT_OF_SCOPE). **문제 2**: "배달팁은 얼마예요?" → "3,000원"만(한집배달 4,000원 빠짐 — 조건 빠짐), "환불은 언제 들어와요?" → 근거 없음(07 "환불은 언제 되나요?" 를 못 찾음 — 임계값 0.40 이 고객 문서엔 높을 수 있음). 확인 뒤 토큰 파일 삭제.
- 고객센터 문의 E2E(`inquiry_e2e.py`) 5/5. 그 전 백그라운드 반복이 비밀번호 초기화를 406(Accept)으로 실패한 채 로그인을 되풀이해 claude-cs 가 잠겼던 것 → Accept */* 로 고침(초기화가 잠금도 푼다).

### 고객 RAG 평가 9회(2026-11-09 밤 ~ 11-10) — `docs/rag/verification/2026-11-10/customer-eval/report.md`
- 37문항(답 있음 26·없음 8·주문 상태 1·실행/주입 2), 1회차 32/37 → 9회차 37/37(사람 판정도 37 — C11 글자 잘림 1).
- 고친 것: 표를 줄로 모델에 전달, 대화 온도 0, 코드 검사(숫자 온전 일치·표 모든 행·질문 대상이 근거에 있는가·물음표 답 탈락 → 재생성 → 원문 발췌), 앞 "네/아니요" 뗌, 근거에 절 제목, 키워드 유사도에 절 제목. 임계값 0.40 그대로(답 있음 최저 0.419, 없음 최고 0.415).
- 4B 모델은 프롬프트 규칙을 잘 따르지 않음(규칙 10 악화) → 확실히 막을 것은 코드로.
- 시험 고객 토큰: 사용자 허락으로 LAN 서명키, 회차마다 다른 시험 고객. 첫 시도는 Claude Code 자동 권한 검사가 막아 사용자가 "1번"(권한 규칙)을 골라 이 스크립트만 허용.

## 남은 것
- 운영자 RAG: 남은 오답 A09·A11, 조건 빠짐 A05·A15 — 질문 묶음을 늘려 재평가. LAN 색인 출처 커밋(`REVISION` 읽기). ADR-0036 결정 필요 3(답변 보존·질문 원문)·4(운영자 역할).
- 고객용 RAG: (11-10 평가 끝 — 아래 절) C11 "신선식:" 글자 잘림 1건. 운영자 RAG 에 같은 결함(숫자 부분 문자열 검사·표 한 행) 확인 필요. 답변 보존 기간은 운영자용과 같이 미설정(ADR-0036 결정 필요 3 — 정해지면 둘 다). 운영자·고객 공용 코드 라이브러리 검토.
- (옛 목록, 11-05) ADR-0036 결정 4건 중 1·2 는 ADR-0051 로 풀림. 해시 기준선 평가 메모: 문서는 맞혔지만 절은 못 맞힌 Q01·Q04·Q11·Q20, 놓친 Q05·Q10·Q12~Q15.
