package com.pmart.embedding.infrastructure;

import com.pmart.embedding.application.EmbeddingProperties;
import com.pmart.embedding.domain.EsChunk;
import com.pmart.embedding.domain.Scope;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ES RAG 색인(chunking-service 소유, ADR-0096)에서 ACTIVE 청크를 <b>읽기만</b> 한다 — 계획 §1-2 단계 3 "ES 를 정제된 말뭉치로
 * 읽는다". REST(클라이언트 라이브러리 없음, ADR-0031).
 */
@Component
public class EsChunks {

  /** 한 문서의 청크 수 상한(긴 문서도 이 안 — 실제 최대 17). */
  static final int MAX_CHUNKS_PER_DOCUMENT = 1000;

  private final RestClient es;
  private final ObjectMapper mapper;

  public EsChunks(EmbeddingProperties props, ObjectMapper mapper) {
    this.mapper = mapper;
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
    rf.setReadTimeout(Duration.ofSeconds(30));
    this.es = RestClient.builder().requestFactory(rf).baseUrl(props.elasticsearchUrl()).build();
  }

  /** 그 범위에서 문서의 ACTIVE 청크(순번 차례). 색인이 아직 없으면 빈 목록. */
  public List<EsChunk> active(Scope scope, String documentId) {
    JsonNode r =
        search(
            scope,
            Map.of(
                "size",
                MAX_CHUNKS_PER_DOCUMENT,
                "sort",
                List.of(Map.of("ordinal", "asc")),
                "_source",
                List.of(
                    "chunkId",
                    "documentId",
                    "contentSha256",
                    "commit",
                    "sectionPath",
                    "ordinal",
                    "content"),
                "query",
                activeFilter(documentId)));
    List<EsChunk> out = new ArrayList<>();
    for (JsonNode h : r.path("hits").path("hits")) {
      JsonNode s = h.path("_source");
      out.add(
          new EsChunk(
              s.path("chunkId").asString(),
              s.path("documentId").asString(),
              s.path("contentSha256").asString(""),
              s.path("commit").asString(null),
              s.path("sectionPath").asString(""),
              s.path("ordinal").asInt(0),
              s.path("content").asString("")));
    }
    return out;
  }

  /** 범위의 ACTIVE 문서 → 문서 판 해시(맞춰 보기용). 색인이 없으면 빈 표. */
  public Map<String, String> activeDocuments(Scope scope) {
    JsonNode r =
        search(
            scope,
            Map.of(
                "size",
                0,
                "query",
                Map.of("term", Map.of("status", "ACTIVE")),
                "aggs",
                Map.of(
                    "docs",
                    Map.of(
                        "terms",
                        Map.of("field", "documentId", "size", 10000),
                        "aggs",
                        Map.of(
                            "hash",
                            Map.of("terms", Map.of("field", "contentSha256", "size", 1)))))));
    Map<String, String> out = new HashMap<>();
    for (JsonNode b : r.path("aggregations").path("docs").path("buckets")) {
      JsonNode h = b.path("hash").path("buckets");
      if (!h.isEmpty()) {
        out.put(b.path("key").asString(), h.get(0).path("key").asString());
      }
    }
    return out;
  }

  private static Map<String, Object> activeFilter(String documentId) {
    return Map.of(
        "bool",
        Map.of(
            "filter",
            List.of(
                Map.of("term", Map.of("documentId", documentId)),
                Map.of("term", Map.of("status", "ACTIVE")))));
  }

  private JsonNode search(Scope scope, Object body) {
    try {
      return mapper.readTree(
          es.post()
              .uri("/" + scope.alias() + "/_search")
              .contentType(MediaType.APPLICATION_JSON)
              .body(mapper.writeValueAsString(body))
              .retrieve()
              .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return mapper.createObjectNode();
    }
  }
}
