# RAG 인수인계 (2026-11-13 작성, 2026-11-15 갱신, **2026-11-16 갱신 — PC 4 로 넘김**, 11-16 오전 마지막 갱신)

RAG 영역(`docs/operations/pc-split.md` §1-1)을 받는 PC 4·사람·Claude 가 **처음 읽는 문서**. 무엇이 어디 있고, 지금 어떤 상태이며, 무엇이 남았는지를 한곳에 모았다. 세부는 링크를 따라간다.

- **넘기는 시점(2026-11-15, 사용자 "여기서부턴 인수인계로 넘기게")**: PC 1 이 한 마지막 일 — RAG 를 PC 5 → PC 2 로 옮김(데이터째), 운영자 색인 범위를 업무 문서로 좁힘(`operator-index-scope.md`), 재색인, `rag_e2e` 6/6·`customer_rag_e2e` 3/3. 이 뒤의 RAG 개발·평가는 PC 4, **배포·LAN 색인은 계속 PC 1**(`lan.sh rag-ingest*` — PR 에 "재색인 필요"를 적는다).
- **실제 인계 2026-11-16**(사용자). 11-15 넘긴 뒤 PC 1 이 한 일 중 RAG 에 닿는 것은 §0 — **먼저 읽는다**.
- PC 4 가 처음 할 일: §0 → §6 "시작하면 먼저".

## 0. 11-15 넘긴 뒤 바뀐 것 (2026-11-16 PC 1 정리)

LAN 색인은 **커밋 `652e518d`(11-15) 문서 기준 그대로**다. 그 뒤 색인 대상 문서가 바뀌었다.

| 무엇 | 색인 영향 | 할 일 |
|---|---|---|
| **`docs/business/flows/employee-account-provisioning.md`**(운영자 색인됨) — BO-01 결정·구현: 권한 추가·변경 요청(승인), 회수 즉시(사유 필수, 관리자 권한은 운영 관리자만), 종료일 없으면 근무 기간까지, 소속 변경 시 이전 센터 권한 다음 날 00:00 종료 | **LAN 색인이 옛 내용** — "권한을 빼려면?"·"센터를 옮기면 권한은?" 에 옛 답 | **재색인은 PC 4 의 첫 PR(ADR-0062·0063·0064 넣을지 결정) 뒤 한 번에**(사용자 2026-11-16 "추천대로" — 운영자 전체 재색인 약 60분, PC 2 CPU. 지금 하면 PC 4 결정 뒤 또 해야 한다). 그때까지 권한 질문은 옛 답. PC 4 는 첫 PR 에 "재색인 필요"를 적는다 |
| **ADR-0062**(직원 개인정보 원문 열람 = 사유 + audit 기록, 기본 가림, 기록 2년, 조회 OPERATOR_ADMIN) — 11-15 새로 썼는데 색인 분류를 빠뜨려 **`RealDocumentsIngestTest` 가 실패**했다 | 11-16 PC 1 이 **애매함(뺌)** 으로 분류해 시험 복구 — `EXCLUDED_ADRS`·`operator-index-scope.md` §3(애매함 7개) | **2026-11-16 PC 4 가 넣기로 함**(사용자 "셋 다 넣고" — 0063·0064 도 같이). manifest·`EXCLUDED_ADRS`·표 고침(브랜치 `rag/index-adr-0062-0064`) → 병합 뒤 PC 1 이 이미지 → 재색인 |
| 새 업무 결정이 **색인 안 되는 문서에만** 있다: 지원센터 = RIDER_OPS, 사건함(유형·담당·처리 기한 10/30분), BO-12 처리 대기(다시 확인·처리 완료), 오배송 확인 = 상담원·RIDER_OPS 가 기록 → 회수 배차, 개인정보 열람 기록 조회 | `docs/architecture/service-requests/case-service.md`·`backoffice-bff.md`, `docs/backoffice/HANDOVER.md` §5 — 모두 색인 범위 밖(architecture·handoff) | 직원이 "사건함은 어떻게 쓰나", "오배송 확인은 누가" 를 물으면 답이 없다. 업무 문서(예 `cs-scripts/06-wrong-delivery.md`, `flows/`)에 옮길지 PC 4 가 판단 — 업무 원문·cs-scripts 를 고치면 사용자 확인 |
| 새 역할 `RIDER_OPS`(라이더 운영, 11-15) | 색인 문서에 역할 설명이 없다 | 위와 같이 판단 |
| **ADR-0063 리뷰 1차**(11-16 오전): 배송 완료 후 14일 작성·3일 수정·삭제 뒤 재작성 금지, **사진 5장·장당 10MB**, **받은 수량 전부 반품이면 새 작성 막음(쓴 리뷰는 그대로)**, 작성자 = 별명 첫 글자 + *, 운영자 숨김 없음 | 기한 규칙은 이미 색인된 Commerce 원문 15장에 있다. 사진 수·반품 뒤 규칙·작성자 표시는 **이 ADR 에만** — 11-16 PC 1 이 **애매함(뺌)** 으로 분류(시험 통과용, `EXCLUDED_ADRS`·`operator-index-scope.md` §3) | **2026-11-16 운영자 RAG 에 넣기로 함**(위 0062 와 같은 PR). 고객 RAG FAQ 에도 리뷰 질문이 없다 — 고객 FAQ 를 늘리면 업무 문구라 사용자 확인 |
| **ADR-0064 알림 1차**(11-16 오전): 고객 앱 **알림함만(휴대폰 푸시 없음)**, 알릴 일 8가지(주문 확정·상품 준비·배송 출발·완료·취소·품절 부분 취소·환불 결과·문의 답변), 모두 끌 수 없음, 환불은 결과가 나온 뒤에만 | 색인 문서에 알림 설명이 없다 — 11-16 PC 1 은 **애매함(뺌)**, **같은 날 PC 4 가 운영자 RAG 에 넣기로 함**(위 0062 와 같은 PR) | 고객이 "알림이 안 와요"(푸시가 없어 앱을 열어야 보임)·"환불 알림이 왜 안 와요"(결과 전) 를 물을 수 있다 — 고객 FAQ 에 넣을지 PC 4 가 판단(사용자 확인) |

- 고객 RAG 는 바뀐 것 없음(고객 FAQ 13개 그대로) — 다만 리뷰·알림 기능이 11-16 새로 생겨(위 표) 고객 질문이 늘 수 있다.
- 위 재색인 전에는 운영자 RAG 의 권한 관련 답이 옛 내용이다(재색인은 PC 4 첫 PR 뒤 — 사용자 결정) — 평가(`answers.json`)에 BO-01 질문을 넣을 때 주의.

## 1. RAG 두 개

| | 운영자 RAG | 고객 RAG |
|---|---|---|
| 서비스 | `services/ai-assistant-service` | `services/customer-assistant-service` |
| 누가 묻나 | 직원(`typ=OPERATOR`, 백오피스) — 정책·업무 절차 질의 | 회원 고객(`typ=CUSTOMER`, 고객 앱) — 고객센터 안내. 비회원 403 |
| 결정 | ADR-0036(1차, 상태: 제안), ADR-0051(고객 확대·서비스 분리·Ollama, 승인) | ADR-0051 |
| 계약 | `contracts/openapi/ai-assistant-service.yaml` | `contracts/openapi/customer-assistant-service.yaml` |
| 게이트웨이 | `/api/v1/assistant/**`(응답 제한 65초) | `/api/v1/customer-assistant/**`(응답 제한 65초) |
| 서비스 규칙 | `services/ai-assistant-service/CLAUDE.md` | `services/customer-assistant-service/CLAUDE.md` |
| 포트 | 8508 | 8509 |
| DB | `ai_assistant_service`(rag-pgvector) | `customer_assistant_service`(rag-pgvector) |
| 질문 제한 | — | 1분 5개·하루 50개(Asia/Seoul), 질문 원문 저장 안 함(C3·C5) |

두 서비스는 운영자 RAG 를 복사해 시작했다(공용 라이브러리는 아직 — ADR-0051 기록). 고친 것이 한쪽에만 들어가지 않게 주의.

## 2. 임베딩(색인)한 문서 — 정본은 manifest

**여기 적힌 파일만 색인한다.** 문서를 더하거나 빼면 manifest 를 고치고 재색인한다(§4).

### 운영자 RAG — `services/ai-assistant-service/src/main/resources/knowledge/manifest.yml` (49개, 1,642 청크 — 2026-11-15 LAN. 2026-11-16 manifest 52개 — ADR-0062·0063·0064 더함, LAN 재색인 전)

**범위 = 업무 직원이 고객 응대·업무 판단에 쓰는 문서만**(사용자 2026-11-15). ADR 61개를 남김 14·뺌 41·애매함 6(뺌)으로 나눈 표와 이유: [`operator-index-scope.md`](operator-index-scope.md). 새 ADR·문서를 넣을 때 이 기준을 먼저 본다.

| 묶음 | 경로 | 개수 | 우선순위(precedence) |
|---|---|---|---|
| ~~서비스 목록~~ | ~~`docs/architecture/service-map.md`~~ — 2026-11-15 뺌(서비스 책임 경계는 업무 직원 대상이 아님, "직원 휴가 신청"에 없는 API 를 지어 답한 근거) | 0 | — |
| ADR(**업무 정책만**, 2026-11-15) | `docs/adr/` 0014·0015·0040~0049·0052·0060·0062~0064(2026-11-16) | 17 | 5(지위는 각 ADR 상태 줄 — 승인=확정, 그 밖=제안). 서비스 경계·인프라·인증 구현·배포·관측·RAG 설계 ADR 은 뺌(사용자 "그건 일반 운영자쪽에서 안봐도되는내용인데") — 뺀 목록·이유는 `RealDocumentsIngestTest.EXCLUDED_ADRS`, 새 ADR 은 둘 중 하나로 정해야 시험 통과 |
| 저장소 설계 | `docs/business/overview.md`·`domain-map.md`·`glossary.md`, `docs/business/flows/`(cancel-vs-handover·order-checkout-saga·replenishment-transfer·return-exchange-refund·employee-account-provisioning), `contracts/states/*.md`(8개) | | 4 |
| 업무 원문 | `docs/business/{WMS,SCM,TMS,MFC,BackOffice}-business-logic.md`(4), `OMS-business-logic.md`(3), `Commerce-business-logic.md`(2), `Delivery-business-logic.md`(1) | | 4·3·2·1 |
| 고객센터 상담 대응 | `docs/business/cs-scripts/README.md`·`01~10`(2026-11-09) | 11 | 1(업무 원문이 앞섬) |

- 넣지 않는 것: worklog·handoff·그 밖 architecture 문서·코드·시험·배포·비밀.
- `overrides`: OMS 8.2 결품 문단 SUPERSEDED(ADR-0049), Commerce 카테고리 1·2단계 대체 제안(ADR-0015) — 이유는 manifest 안 `reason`.
- 직원 등록·권한 요청·승인은 ADR-0056 대신 업무 흐름 `docs/business/flows/employee-account-provisioning.md` 로 넣었다(2026-11-15).
- 평가 묶음도 같이 맞춤(2026-11-15): 검색 평가 `questions.yml` 은 기술 질문 7개를 빼고 R3 를 업무 질문으로, 업무 질문 Q21~Q23 추가(21개). LAN `rag_e2e.py` 의 "서비스 간 호출" → "상품준비 뒤 고객센터 취소". 답변 평가 `answers.json` 은 §6.

### 고객 RAG — `services/customer-assistant-service/src/main/resources/knowledge/manifest.yml` (13개, 63 청크)

- `docs/business/customer-faq/01-customer-center.md` ~ `13-alcohol.md` — 고객 공개 안내만(Claude 초안·사용자 승인 2026-11-09 C1). 업무 문서·cs-scripts·ADR 은 내부 문서라 넣지 않는다.
- **고치거나 더하면 사용자 승인 뒤** manifest 에 넣고 재색인(`rag-ingest-customer`). 지위·우선순위 가중은 0(승인된 안내는 모두 같은 지위).

## 3. LAN 배치·모델 (PC 2 — 2026-11-11 PC 2 → PC 5, **2026-11-15 PC 5 → PC 2 다시**: PC 5 메모리. 데이터·모델 볼륨을 그대로 옮김, 색인은 그대로)

| 구성요소 | 내용 |
|---|---|
| `ollama` | PC 2 GPU(RTX 2060, 쓸 수 있는 메모리 약 5GB). 컨테이너 CPU 4·메모리 6GB 상한(같은 PC 의 주문·결제 몫을 남김 — 재색인은 주문 시험이 없을 때). 같은 PC 서비스는 `http://ollama:11434`. **2026-11-16 부터 LAN `http://192.168.0.34:11434` 열림**(사용자 "포트도 지금 바로" — RAG 개발 PC 4 용, 인증 없음, LAN 안에서만). 동시 1건(`OLLAMA_NUM_PARALLEL=1`)이라 PC 4 요청이 LAN RAG 답을 기다리게 한다 — **대량 임베딩·전체 평가 전에는 PC 1·사용자에게 알린다**. 모델을 내려받거나 지우지 않는다(`ollama pull`·`rm` 은 PC 1 `rag-pull`) |
| 대화 모델 | `qwen3:4b`(GPU 100%, 3.9GB, 문맥 8192) — 사용자 결정 2026-11-09(8B 와 비교 25 vs 26/30, 8B 는 8~22초) |
| 임베딩 모델 | `qwen3-embedding:0.6b`(ID `ac6da0dfba84`, 1024차원, **CPU** — `embedding-num-gpu: 0`, GPU 는 대화 모델에). 2026-11-09 비교 평가로 결정(`verification/2026-11-09/embedding-compare/`) |
| `rag-pgvector` | PostgreSQL + pgvector, 포트 15433(LAN 노출 없음), 서비스마다 DB·계정(`deploy/lan/rag/rag-init-db.sh`) |
| 설정 | 설정 저장소 `ai-assistant-service{,-lan,-local}.yml`·`customer-assistant-service{,-lan,-local}.yml` — 임계값(운영자 LAN 0.40, 발췌 전환 하한 0.45, 지위 가중 ×0.5), 대화 온도(운영자 0.2·고객 0.0), 요청 제한 25초 |
| 로컬 | `deploy/local/ai-assistant/docker-compose.yml`(pgvector 15433). local 프로필은 해시 임베딩 기준선(`provider: local`, 384차원) — 외부·Ollama 없음 |

- 배치 정본 `deploy/lan/placement.conf`(pc2 줄 `rag-pgvector ollama ai-assistant-service customer-assistant-service`), compose 는 `deploy/lan/render.sh`.
- 감시: `deploy/lan/watch.sh` 가 rag-pgvector·ollama 를 SSH 로 컨테이너 안에서 점검(2026-11-09 LAN 포트 없는 둘을 HTTP 로 보다 10분마다 재시작시킨 사고 뒤).
- 아이디어(결정 아님): 임베딩을 GPU 로(5대 모두 GPU) — 지금은 CPU 라 운영자 전체 재색인 약 60분.

## 4. 명령 (배포·색인은 PC 1 에서)

```bash
deploy/lan/lan.sh rag-pull pc2 qwen3-embedding:0.6b qwen3:4b   # 모델 받기
deploy/lan/lan.sh rag-warm pc2                                  # 대화 모델 미리 올림(첫 적재 ~30초 > 요청 제한 25초) — ollama 재시작 뒤 한 번
deploy/lan/lan.sh rag-ingest pc2                                # 운영자 RAG 재색인(일회 컨테이너, manifest 파일들을 rag/knowledge-operator 로 복사해 /repo)
deploy/lan/lan.sh rag-ingest-customer pc2                       # 고객 RAG 재색인
```

- 임베딩 모델 파일을 바꾸면 설정 `embedding-version` 을 바꾸고 재색인.
- `rag-ingest*` 는 render 를 먼저 해 문서 사본(`build/pc2/rag/knowledge-*`)을 새로 만든다(2026-11-15 전에는 마지막 render 때 문서로 색인했다).
- 색인 목록(manifest)은 **이미지 안**에 들어간다 — manifest 를 바꾸면 이미지(Jib) → `lan.sh deploy pc2 ai-assistant-service` → `rag-ingest pc2` 순서.
- 출처 커밋: 색인 컨테이너에는 `.git` 이 없어 render 가 문서 사본에 둔 `REVISION`(1줄 커밋, 2줄 `clean`·`dirty` — 복사한 문서·manifest 에 커밋 안 한 수정이 있으면 dirty)을 색인기(`GitSourceRevision`, 두 서비스)가 읽어 `index_snapshot.source_revision`·`source_dirty` 에 남긴다(2026-11-15 PC 1 — 그 전 LAN 색인은 빈 값·dirty). 커밋하지 않은 문서로 재색인하지 않는다.

## 5. 시험·평가

| 무엇 | 경로·명령 |
|---|---|
| 단위·통합 | `./gradlew :services:ai-assistant-service:check`, `:services:customer-assistant-service:check` |
| 검색 평가(질문·정답 문서/절) | `services/ai-assistant-service/src/integrationTest/resources/eval/questions.yml`(`RetrievalEvalIT`) |
| 운영자 답변 평가(30문항, 정답 사실) | 질문 `…/integrationTest/resources/eval/answers.json`, 채점 `deploy/lan/checks/rag_answer_eval.py` → 보고서 `docs/rag/verification/<날짜>/answer-eval/` |
| 고객 답변 평가(37문항) | 질문 `deploy/lan/checks/eval/customer-answers.json`, 채점 `deploy/lan/checks/customer_rag_eval.py`(고객 토큰 필요 — `.secrets/customer-token`, 15분) |
| LAN 연결 확인 | `deploy/lan/checks/rag_e2e.py`(운영자 6문항), `customer_rag_e2e.py`(경로·403·헬스) |
| 직원 토큰 | `deploy/lan/checks/staff_auth.py`(2026-11-13 — auth-service 운영자 로그인을 없앤 뒤) — `docs/operations/dev-on-other-pcs.md` |

**최근 결과**: 운영자 7차 엄격 24/30·사람 26/30(답 없는 질문 11/11, 1.7~4초 — `verification/2026-11-09/answer-eval/report-7-final.md`), 고객 9차 37/37(`verification/2026-11-10/customer-eval/report.md`). 11-11 PC 5 이동 뒤 `rag_e2e` 6/6·`customer_rag_e2e` 3/3. **2026-11-15(PC 2, 업무 범위 재색인 뒤) `rag_e2e` 6/6·`customer_rag_e2e` 3/3**, qwen3:4b 100% GPU. **고객 답변 평가 2026-11-15 37/37**(`verification/2026-11-15/customer-eval/report.md` — 시험용 고객 토큰, ADR-0060 취소 안내 반영 확인). 운영자 답변 평가 30문항은 범위를 바꾼 뒤 아직 다시 돌리지 않았다(기술 질문 7개 교체 뒤 — §6).

## 6. 남은 일·결정 대기

### 시작하면 먼저 (PC 4)
1. 공통 준비(`docs/operations/dev-on-other-pcs.md`·`other-pcs-todo.md` PC 4 절) — 포크·`<rag>/<주제>` 브랜치, 비밀번호 파일, Gradle 제한.
2. 개발 환경: **평소는 local 프로필**(해시 임베딩 384차원, 모델 없음 — `deploy/local/ai-assistant/docker-compose.yml` pgvector 15433). 실제 모델로 답변을 볼 때만 PC 4 에 로컬 ollama(같은 모델 qwen3:4b·qwen3-embedding:0.6b — PC 1 이 11-09 평가 때 쓴 방식). 실제 모델 임베딩(1024)과 해시(384)는 DB 를 섞지 않는다. 실제 모델은 **PC 2 LAN ollama `http://192.168.0.34:11434` 를 써도 된다**(2026-11-16 포트 엶 — 사용자 "포트도 지금 바로"; 로컬 ollama 도 그대로 가능). 개발 DB 는 PC 4 로컬 pgvector — **LAN rag-pgvector(포트 닫힘)에는 붙지 않는다**, LAN 색인은 PC 1 `rag-ingest` 로만. 같은 ollama 를 LAN RAG 가 쓰므로 대량 임베딩·전체 평가는 미리 알리고, 모델을 받거나 지우지 않는다. PC 4 에는 게이트웨이·설정 서버가 있어 무거운 시험 중 LAN 이 느려지면 PC 1 에 알린다(2026-11-15 감시 묶음을 PC 1 로 옮겨 PC 4 메모리 여유가 조금 늘었다).
3. `./gradlew :services:ai-assistant-service:check`·`:services:customer-assistant-service:check` 가 PC 4 에서 통과하는지.

### 운영자 RAG
- **답변 평가 `answers.json` 30문항 중 A07~A12·A19 7개는 뺀 ADR 이 정답인 기술 질문** — 업무 질문으로 바꾸고 범위를 바꾼 뒤 다시 평가(`rag_answer_eval.py`). 7차(11-09) 결과는 옛 범위 기준이다.
- 옛 오답: A09(TMS 문서를 고름)·A11, 조건 빠짐 A05·A15 — 새 범위에서 다시 보고 질문 묶음을 늘려 재평가.
- 생성 답변이 근거에 충실한지: 11-15 "직원 휴가 신청" 에 없는 API 를 지어 답한 일(근거 문서를 빼서 사라졌지만, 근거가 약할 때 지어내는 성향 자체는 남음). 절 우선순위: ADR 에서 "결정" 대신 "배경" 절을 답한 일(ADR-0059 — 지금은 범위 밖).
- 애매함 ADR(`operator-index-scope.md` §3 — 0062·0063·0064 는 2026-11-16 넣음, 남은 6개)을 다시 넣을지 — **RAG 영역(PC 4)이 정한다**(사용자 2026-11-15 "Rag쪽에 인계할거임"). 넣으면 표·`EXCLUDED_ADRS`·manifest 를 같이 고치고 PR 에 "재색인 필요".

### 고객 RAG
- C11 "신선식:" 글자 잘림 1건. 운영자 RAG 에도 같은 결함(숫자 부분 문자열 검사·표 한 행) 있는지 확인.
- ~~고객 답변 평가 재실행~~ — 2026-11-15 37/37. 고객 토큰은 실제 앱 로그인 대신 PC 1 이 auth-service LAN 서명키로 가상 계정 토큰을 만들 수 있다(사용자 허락 필요 — 2026-11-15 받음, 20분).
- 고객 앱 채팅 상담 화면은 아직 없다(클라이언트 요청 `docs/handoff/clients/customer-app/2026-11-09-clients-request-customer-chat-assistant.md`) — 화면이 생기면 실제 앱 토큰으로 확인.

### 둘 다
- 결정 대기(ADR-0036·0051): 답변 보존 기간·질문 원문 저장(운영자), 운영자 역할 세분, ADR-0051 "결정 대기" 절.
- 공용 라이브러리(두 서비스 공통 코드) — 아직 없음.
- 아이디어(결정 아님): 임베딩 GPU(§3).

## 7. 이 폴더(`docs/rag/`) 지도

| 경로 | 내용 |
|---|---|
| `README.md` | 폴더 안내(11-05 기준 상태 — **최신 상태는 이 문서**) |
| `operator-index-scope.md` | 운영자 RAG 색인 범위 — ADR 61개 남김·뺌·애매함 표와 기준(2026-11-15) |
| `worklog.md` | RAG 작업 일지(11-04~11-10). 영역을 나눈 뒤(2026-11-15~)는 **`docs/worklog/rag/YYYY-MM-DD.md`**(폴더 있음 — README) |
| `codex-opinion/` | 설계·검토 원문 1~6차(수정 금지)와 1차 계획 |
| `references/2026-11-07/` | 사용자가 넣은 외부 자료 원본(무료 모델 추천·Spring AI 구조 검토) |
| `planning/` | 원본 대조·운영자/고객 RAG 보고서·구조도(2026-11-07) |
| `verification/2026-11-05/` | 1차 검토(빌드·시험 로그·diff·검색 기준선) |
| `verification/2026-11-09/` | 임베딩 비교(`embedding-compare/`), 운영자 답변 평가 1~7차(`answer-eval/`) |
| `verification/2026-11-10/` | 고객 답변 평가(`customer-eval/`) |
