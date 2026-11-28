package com.pmart.embedding.application;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 임베딩 설정({@code pmart.embedding}, 정본 pmart-config).
 *
 * @param elasticsearchUrl ES 주소(청크를 읽는 곳 — LAN es-search)
 * @param provider {@code ollama}(LAN) 또는 {@code fake}(시험·로컬 — 글 해시로 만든 벡터, 뜻 없음)
 * @param dim 벡터 차원(스키마 vector(1024) 와 같아야 한다)
 * @param batchSize Ollama 한 번에 보낼 글 수
 * @param reconcileInterval ES 와 pgvector 를 맞춰 보는 주기(놓친 알림 보충)
 * @param ollama Ollama 설정
 * @param queryInstructions 질문 임베딩 앞에 붙이는 지시문(범위별 OPERATOR·CUSTOMER — Qwen3-Embedding 권장, 문서 쪽에는 붙이지
 *     않음)
 */
@ConfigurationProperties("pmart.embedding")
public record EmbeddingProperties(
    String elasticsearchUrl,
    String provider,
    int dim,
    int batchSize,
    @Nullable Duration reconcileInterval,
    @Nullable Ollama ollama,
    java.util.@Nullable Map<String, String> queryInstructions) {

  /**
   * @param baseUrl Ollama 주소
   * @param model 임베딩 모델 이름(qwen3-embedding:0.6b)
   * @param version 모델 판 — ollama list 의 ID. 바꾸면 캐시가 다시 채워진다
   * @param numGpu GPU 층 수(0 = CPU — GPU 는 대화 모델 몫)
   * @param numCtx 문맥 길이
   * @param keepAlive 모델을 메모리에 둘 시간
   * @param connectTimeout 연결 시간 제한
   * @param requestTimeout 요청 시간 제한
   */
  public record Ollama(
      String baseUrl,
      String model,
      String version,
      @Nullable Integer numGpu,
      @Nullable Integer numCtx,
      @Nullable String keepAlive,
      @Nullable Duration connectTimeout,
      @Nullable Duration requestTimeout) {}

  public EmbeddingProperties {
    if (elasticsearchUrl == null || elasticsearchUrl.isBlank()) {
      elasticsearchUrl = "http://localhost:9200";
    }
    if (provider == null || provider.isBlank()) {
      provider = "fake";
    }
    if (dim <= 0) {
      dim = 1024;
    }
    if (batchSize <= 0) {
      batchSize = 8;
    }
  }

  /** 범위의 질문 지시문(없으면 빈 문자열). */
  public String queryInstruction(String scope) {
    if (queryInstructions == null) {
      return "";
    }
    String v = queryInstructions.get(scope);
    return v == null ? "" : v;
  }

  public Duration reconcileEvery() {
    return reconcileInterval == null ? Duration.ofMinutes(10) : reconcileInterval;
  }
}
