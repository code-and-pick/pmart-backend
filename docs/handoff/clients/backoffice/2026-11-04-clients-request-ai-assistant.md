# [pmart-backend → pmart-clients] 안내: 백오피스 정책 질문(ai-assistant 1차) (2026-11-04)

- 성격: **새 API 안내(백오피스, 선택 구현).** 백엔드는 클라이언트를 수정하지 않는다. 화면을 만들지와 시기는 클라이언트가 정한다.
- 계약: `contracts/openapi/ai-assistant-service.yaml`(1.0.0), 결정 ADR-0036. 게이트웨이 경로 `/api/v1/assistant/**`(pmart-config 미반영·**LAN 배포 전**). — 2026-11-13 갱신: 게이트웨이 경로 반영·LAN 배포됨(운영자 RAG, PC 5).
- **지금은 기준선 모드**다. `answerMode=BASELINE_EXTRACTIVE`·`retrievalMode=BASELINE_HASH` 이면 생성 답변이 아니라 근거 문서 원문 발췌다. 의미 검색·생성 품질은 검증되지 않았다 — 화면에 "기준선(문서 발췌)"이라고 표시해 주면 좋다.

## 1. 인증·헤더
- 운영자 토큰(typ=OPERATOR)만 쓸 수 있다. 고객·라이더 토큰은 403 이다.
  - 2026-11-13 갱신: 직원(운영자) 토큰은 이제 employee-auth-service 가 발급한다(ADR-0056, 모양은 같음 — auth-service 운영자 로그인은 없어짐).
- `X-Source-System: backoffice-web` 는 필수다. 토큰에 `src` 가 있으면 그 값과 같아야 하고, 없으면 `backoffice-web` 만 받는다. 다르면 403 이다.
- 질문에는 `Idempotency-Key`(UUID 권장)가 필수다. 같은 키로 다시 보내면 같은 답이 온다.

## 2. 질문 → 응답
`POST /api/v1/assistant/policy-questions` 본문 `{question(1~2000자), context?{screen?, centerId?}}`. context 는 화면 힌트일 뿐이며 권한·검색 범위를 넓히지 않는다.

| HTTP | 뜻 | 화면 처리 |
|---|---|---|
| 200 `ANSWERED` | 근거 있는 답 | `answer` + `citations[]` 표시 |
| 200 `NO_GROUNDING` | 허용 문서에 근거 없음(정상 결과) | "확인할 수 없음" 안내 |
| 200 `REALTIME_DATA_REQUIRED` | 특정 주문·재고·결제의 현재 상태 질문 | 해당 업무 화면으로 안내 |
| 200 `OUT_OF_SCOPE` | 환불·승인·재고 변경 같은 실행 요청 | 해당 업무 화면으로 안내 |
| 202 `PROCESSING` + `Retry-After` | 같은 키가 처리 중 | 몇 초 뒤 `GET ?requestId=` |
| 409 `IDEMPOTENCY_CONFLICT` | 같은 키에 다른 질문·context | 새 키로 보낸다 |
| 422 `CONFIG_REQUIRED` | 문서 색인이 아직 없음 | 운영 문의 안내, 같은 키로 나중에 재시도 |
| 503 `DEPENDENCY_UNAVAILABLE` | 검색·모델 장애·시간 초과(결과 저장 안 됨) | **같은 키**로 재시도 |

## 3. 응답을 잃었을 때
- `GET /api/v1/assistant/policy-questions?requestId=<보낸 Idempotency-Key>` (헤더 `X-Source-System`)
  - 200: 완료된 답.
  - 202: 처리 중 — `Retry-After` 뒤 다시 조회한다.
  - 409 `INVALID_STATE`(`retryable=true`): 직전 시도가 실패했거나 처리가 끊겼다 → **같은 키·같은 본문으로 POST** 를 다시 보낸다(GET 만 반복하지 않는다).
  - 404: 기록 없음(다른 운영자·다른 출처 포함).
- `GET /api/v1/assistant/policy-questions/{answerId}`: 다시 보기(본인만). 매번 현재 권한을 다시 확인하므로, 권한이 없어진 운영자에게는 404 다.

## 4. 인용 표시 제안
- `citations[]` 한 건 = 근거 청크 하나. 열 제안: 경로(`path`)·절(`section`)·지위 배지·생애 배지·기준일·발췌(`excerpt`, 접기).
- 지위 `authority`: CONFIRMED [확정] · ADOPTED_DESIGN [채택 설계] · PROPOSAL [설계안] · ASSUMPTION [가정] · UNDECIDED [미결정] · EXTERNAL_REFERENCE [외부 참고].
- 생애 `lifecycleStatus`: SUPERSEDED [대체됨 → `supersededBy`], SUPERSESSION_PROPOSED [대체 제안 있음(미승인): `proposedSupersededBy[]`]. ACTIVE 는 배지를 달지 않는다.
- `implementationEvidence.recordDate` 가 있으면 "YYYY-MM-DD 기준 구현 기록 — 현재 상태는 이 기록만으로 확인할 수 없음"으로 표시한다.
- `documentVersion`·`effectiveDate` 는 원문에 있을 때만 온다(없으면 null — 지어내지 않는다).
- `asOf{snapshotId, sourceRevision, sourceDirty, indexedAt}`: 어느 색인 기준인지 보여 준다. `sourceDirty=true` 면 커밋되지 않은 문서로 색인한 것이다.

## 5. mock 사례(계약 examples)
`Answered`(기준선, 확정 근거 없음 머리말 포함) · `NoGrounding` · `RealtimeRequired` · `OutOfScope` · `Processing`.
