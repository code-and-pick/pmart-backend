package com.pmart.ragquery.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 질문 검색 설정({@code pmart.rag-query}, 정본 pmart-config). 기준값은 ES-First 2차 검증에서 dev+회귀로 고정한 값
 * (docs/rag/verification/2026-11-28/es-first-v2/config.json) — 바꾸면 새 dev·final 질문군으로 다시 잰다.
 *
 * @param elasticsearchUrl ES(rag-operator·rag-customer)
 * @param embeddingUrl embedding-service 주소(벡터 검색 {@code /api/v1/internal/vector-searches})
 * @param policyBoost 운영자 검색에서 POLICY 문서 점수 곱(정렬에만 — 판정은 원래 점수)
 * @param candidates BM25 후보 수(가중치로 다시 정렬하는 창)
 * @param minCoverage ES 종료: 핵심어 비율 하한
 * @param minRelGap ES 종료: 1·2위 상대 점수 차 하한
 * @param cards ES 에서 끝낼 때 돌려줄 카드 수
 * @param noAnswerCosine 넘긴 질문: 벡터 1위 코사인이 이보다 낮으면 답변 불가
 * @param rrfK RRF 상수
 * @param timeout ES·embedding-service 호출 시간 제한
 */
@ConfigurationProperties("pmart.rag-query")
public record RagQueryProperties(
    String elasticsearchUrl,
    String embeddingUrl,
    double policyBoost,
    int candidates,
    double minCoverage,
    double minRelGap,
    int cards,
    double noAnswerCosine,
    int rrfK,
    Duration timeout) {

  public RagQueryProperties {
    if (elasticsearchUrl == null || elasticsearchUrl.isBlank()) {
      elasticsearchUrl = "http://localhost:9200";
    }
    if (embeddingUrl == null || embeddingUrl.isBlank()) {
      embeddingUrl = "http://localhost:8516";
    }
    if (policyBoost <= 0) {
      policyBoost = 1.0;
    }
    if (candidates <= 0) {
      candidates = 20;
    }
    if (cards <= 0) {
      cards = 3;
    }
    if (rrfK <= 0) {
      rrfK = 60;
    }
    if (timeout == null) {
      timeout = Duration.ofSeconds(30);
    }
  }
}
