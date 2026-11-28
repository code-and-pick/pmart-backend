package com.pmart.ragquery.domain;

import org.jspecify.annotations.Nullable;

/**
 * 검색 결과 청크 하나.
 *
 * @param chunkId 청크 ID(ES·pgvector 같은 값)
 * @param documentId 문서 ID(저장소 경로)
 * @param sectionPath 문서 제목 > 절 제목
 * @param heading 절 제목(FAQ 면 질문 문장)
 * @param documentType POLICY·REFERENCE·APP_GUIDE·FAQ(ES 색인 v2), 벡터 결과는 null
 * @param raw 원래 점수(BM25 _score 또는 벡터 코사인) — 판정은 이것으로
 * @param score 정렬에 쓴 점수(가중치 반영 또는 RRF) — 판정에 쓰지 않는다
 */
public record SearchHit(
    String chunkId,
    String documentId,
    String sectionPath,
    String heading,
    @Nullable String documentType,
    double raw,
    double score) {

  public SearchHit withScore(double s) {
    return new SearchHit(chunkId, documentId, sectionPath, heading, documentType, raw, s);
  }
}
