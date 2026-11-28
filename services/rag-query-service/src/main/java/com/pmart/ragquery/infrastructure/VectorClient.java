package com.pmart.ragquery.infrastructure;

import com.pmart.ragquery.application.RagQueryProperties;
import com.pmart.ragquery.domain.SearchHit;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * embedding-service 벡터 검색({@code POST /api/v1/internal/vector-searches}, ADR-0099) — 질문
 * 임베딩·pgvector 는 그쪽 소유.
 */
@Component
public class VectorClient {

  /** 결과와 그쪽의 임베딩 시간. */
  public record Result(List<SearchHit> hits, long embedMillis) {}

  private final RestClient client;
  private final ObjectMapper mapper;

  public VectorClient(RagQueryProperties props, ObjectMapper mapper) {
    this.mapper = mapper;
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(props.timeout())
            .build();
    JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
    rf.setReadTimeout(props.timeout());
    this.client = RestClient.builder().requestFactory(rf).baseUrl(props.embeddingUrl()).build();
  }

  public Result search(String scope, String question, int size) {
    String body =
        client
            .post()
            .uri("/api/v1/internal/vector-searches")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Source-System", "rag-query-service")
            .header(
                "Idempotency-Key",
                UUID.nameUUIDFromBytes((scope + "|" + question).getBytes(StandardCharsets.UTF_8))
                    .toString())
            .body(Map.of("scope", scope, "question", question, "size", size))
            .retrieve()
            .body(String.class);
    JsonNode r = mapper.readTree(body);
    List<SearchHit> out = new ArrayList<>();
    for (JsonNode h : r.path("hits")) {
      double cos = h.path("cosine").asDouble();
      out.add(
          new SearchHit(
              h.path("chunkId").asString(),
              h.path("documentId").asString(),
              h.path("sectionPath").asString(""),
              "",
              null,
              cos,
              cos));
    }
    return new Result(out, r.path("embedMillis").asLong(0));
  }
}
