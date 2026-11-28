package com.pmart.chunking.infrastructure;

import com.pmart.chunking.application.ChunkingProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 문서 하나를 ES 에 반영한 뒤 embedding-service 에 알린다({@code POST pmart.chunking.embedding-url}, ADR-0098) —
 * 그쪽이 ES 청크를 읽어 임베딩한다. 알림 실패는 기록만 한다: ES 반영은 끝났고, embedding-service 의 맞춰 보기가 놓친 문서를 채운다. 주소가 없으면
 * 알리지 않는다.
 */
@Component
public class EmbeddingNotifier {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingNotifier.class);

  private final ChunkingProperties props;
  private final RestClient client;

  public EmbeddingNotifier(ChunkingProperties props) {
    this.props = props;
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
    rf.setReadTimeout(Duration.ofSeconds(5));
    this.client = RestClient.builder().requestFactory(rf).build();
  }

  public void documentChanged(String documentId, String contentVersion, List<String> aliases) {
    String url = props.embeddingUrl();
    if (url == null || url.isBlank() || aliases.isEmpty()) {
      return;
    }
    try {
      client
          .post()
          .uri(url)
          .contentType(MediaType.APPLICATION_JSON)
          .header("X-Source-System", "chunking-service")
          .header(
              "Idempotency-Key",
              UUID.nameUUIDFromBytes(
                      (documentId + "|" + contentVersion + "|" + aliases)
                          .getBytes(StandardCharsets.UTF_8))
                  .toString())
          .body(Map.of("documentId", documentId, "scopes", aliases))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException e) {
      log.warn("embedding-service 알림 실패(맞춰 보기가 채움) path={} cause={}", documentId, e.toString());
    }
  }
}
