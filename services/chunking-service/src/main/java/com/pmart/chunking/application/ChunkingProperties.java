package com.pmart.chunking.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 청킹·ES 설정({@code pmart.chunking}, 정본 pmart-config).
 *
 * @param elasticsearchUrl ES 주소(LAN es-search, PC 3)
 * @param chunkTokens 한 청크 최대 토큰(TokenTextSplitter) — 넘는 절만 더 나눈다
 * @param minChunkChars 나눌 때 한 조각 최소 글자
 * @param timeout ES 호출 시간 제한
 */
@ConfigurationProperties("pmart.chunking")
public record ChunkingProperties(
    String elasticsearchUrl, int chunkTokens, int minChunkChars, Duration timeout) {

  public ChunkingProperties {
    if (elasticsearchUrl == null || elasticsearchUrl.isBlank()) {
      elasticsearchUrl = "http://localhost:9200";
    }
    if (chunkTokens <= 0) {
      chunkTokens = 800;
    }
    if (minChunkChars <= 0) {
      minChunkChars = 200;
    }
    if (timeout == null) {
      timeout = Duration.ofSeconds(20);
    }
  }
}
