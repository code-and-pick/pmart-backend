package com.pmart.ragquery.infrastructure;

import com.pmart.ragquery.application.RagQueryProperties;
import com.pmart.ragquery.domain.SearchHit;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ES RAG 색인 읽기(REST, ADR-0031) — chunking-service 가 넣은 rag-operator·rag-customer. 검색·분석기 토큰·청크
 * 본문만(쓰지 않는다). 질의는 2차 검증과 같다: multi_match(content, sectionPath^2, title) + status=ACTIVE.
 */
@Component
public class EsRag {

  private final RestClient es;
  private final ObjectMapper mapper;

  public EsRag(RagQueryProperties props, ObjectMapper mapper) {
    this.mapper = mapper;
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(props.timeout())
            .build();
    JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
    rf.setReadTimeout(props.timeout());
    this.es = RestClient.builder().requestFactory(rf).baseUrl(props.elasticsearchUrl()).build();
  }

  /** BM25 상위 size(원래 점수 순, score = raw). */
  public List<SearchHit> bm25(String alias, String question, int size) {
    JsonNode r =
        post(
            "/" + alias + "/_search",
            Map.of(
                "size",
                size,
                "_source",
                List.of("chunkId", "documentId", "sectionPath", "heading", "documentType"),
                "query",
                Map.of(
                    "bool",
                    Map.of(
                        "must",
                        Map.of(
                            "multi_match",
                            Map.of(
                                "query",
                                question,
                                "fields",
                                List.of("content", "sectionPath^2", "title"))),
                        "filter",
                        Map.of("term", Map.of("status", "ACTIVE"))))));
    List<SearchHit> out = new ArrayList<>();
    for (JsonNode h : r.path("hits").path("hits")) {
      JsonNode s = h.path("_source");
      double score = h.path("_score").asDouble();
      out.add(
          new SearchHit(
              s.path("chunkId").asString(),
              s.path("documentId").asString(),
              s.path("sectionPath").asString(""),
              s.path("heading").asString(""),
              s.path("documentType").asString(null),
              score,
              score));
    }
    return out;
  }

  /** 색인 분석기(rag_nori) 토큰 — 순서대로. */
  public List<String> analyze(String alias, String text) {
    JsonNode r = post("/" + alias + "/_analyze", Map.of("analyzer", "rag_nori", "text", text));
    List<String> out = new ArrayList<>();
    for (JsonNode t : r.path("tokens")) {
      out.add(t.path("token").asString());
    }
    return out;
  }

  /** 청크의 제목 경로 + 줄바꿈 + 본문(없으면 빈 문자열). */
  public String chunkText(String alias, String chunkId) {
    JsonNode r =
        post(
            "/" + alias + "/_search",
            Map.of(
                "size",
                1,
                "_source",
                List.of("content", "sectionPath"),
                "query",
                Map.of("term", Map.of("chunkId", chunkId))));
    JsonNode h = r.path("hits").path("hits");
    if (h.isEmpty()) {
      return "";
    }
    JsonNode s = h.get(0).path("_source");
    return s.path("sectionPath").asString("") + "\n" + s.path("content").asString("");
  }

  private JsonNode post(String uri, Object body) {
    return mapper.readTree(
        es.post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(body))
            .retrieve()
            .body(String.class));
  }
}
