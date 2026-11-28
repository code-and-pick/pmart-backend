설정: 가중치 POLICY ×1.5 · ES 종료 규칙 `cov>=0.7&relGap>=0.1`(종료 때 상위 3 카드) · 답변 불가 기준 벡터 1위 코사인 < 0.58 (config.json 고정값 — final 에서 고르지 않음)

## final · operator
- 문항 13: GROUNDED 11, NO_GROUNDING 2 · 미확정 1(집계 제외)

| 조건 | 근거 청크 1위 | 근거 청크 3위 안 | 문서 1위 | 문서 3위 안 |
|---|---|---|---|---|
| BM25 | 5/11 (45%) | 8/11 (73%) | 9/11 (82%) | 11/11 (100%) |
| BM25+가중치 ×1.5 | 5/11 (45%) | 8/11 (73%) | 9/11 (82%) | 11/11 (100%) |
| 벡터만 | 9/11 (82%) | 11/11 (100%) | 11/11 (100%) | 11/11 (100%) |
| BM25+벡터(RRF) | 6/11 (55%) | 10/11 (91%) | 10/11 (91%) | 11/11 (100%) |

- n: 12
- uncertainExcluded: 1
- acceptance: 4/12 (33%)
- precision: 4/4 (100%)
- falseAccept_noGrounding: []
- falseAccept_wrongEvidence: []
- unnecessaryFallback: 1/7 (14%)
- ragRecovery_newEvidence: 2/3 (67%)
- ragRecovery_rankFix: 1/3 (33%)
- noAnswerAccuracy: 1/1 (100%)
- groundedDeclaredNoAnswer: []
- evidenceHit@3: 10/11 (91%)
- embeddingCalls: 8/12 (67%)
- generationCalls(est): 7/12 (58%)
- latencyMs_p50: 302.1
- latencyMs_p95: 352.3

## final · customer
- 문항 10: GROUNDED 8, NO_GROUNDING 2 · 미확정 0(집계 제외)

| 조건 | 근거 청크 1위 | 근거 청크 3위 안 | 문서 1위 | 문서 3위 안 |
|---|---|---|---|---|
| BM25 | 3/8 (38%) | 3/8 (38%) | 3/8 (38%) | 4/8 (50%) |
| BM25+가중치 ×1.5 | 3/8 (38%) | 3/8 (38%) | 3/8 (38%) | 4/8 (50%) |
| 벡터만 | 4/8 (50%) | 5/8 (62%) | 4/8 (50%) | 5/8 (62%) |
| BM25+벡터(RRF) | 3/8 (38%) | 6/8 (75%) | 3/8 (38%) | 6/8 (75%) |

- n: 10
- uncertainExcluded: 0
- acceptance: 1/10 (10%)
- precision: 1/1 (100%)
- falseAccept_noGrounding: []
- falseAccept_wrongEvidence: []
- unnecessaryFallback: 2/7 (29%)
- ragRecovery_newEvidence: 3/5 (60%)
- ragRecovery_rankFix: 0/0
- noAnswerAccuracy: 1/2 (50%)
- groundedDeclaredNoAnswer: []
- evidenceHit@3: 6/8 (75%)
- embeddingCalls: 9/10 (90%)
- generationCalls(est): 8/10 (80%)
- latencyMs_p50: 289.5
- latencyMs_p95: 371.1

