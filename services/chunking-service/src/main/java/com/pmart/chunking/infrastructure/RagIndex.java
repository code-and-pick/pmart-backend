package com.pmart.chunking.infrastructure;

import com.pmart.chunking.application.ChunkingProperties;
import com.pmart.chunking.domain.AccessScope;
import com.pmart.chunking.domain.Chunk;
import com.pmart.chunking.domain.DocumentType;
import com.pmart.chunking.domain.IncomingDocument;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Elasticsearch RAG 색인 어댑터(REST, 클라이언트 라이브러리 없음 — ADR-0031). 접근 범위마다 별칭 {@code rag-operator}·{@code
 * rag-customer} → 실제 색인 {@code <별칭>-v2}(정의 {@code elasticsearch/rag-chunks-v2.json}, nori — v2 에
 * documentType). 이 서비스만 쓴다.
 *
 * <p>문서 하나 바꾸기 = 새 판 청크 bulk 넣기 → 같은 문서의 다른 판 청크 지우기(빈 틈 없이). 삭제 = 그 문서 청크 {@code
 * status=RETIRED}(계획 §1-3 — 지우지 않고 상태로 내린다, 검색은 ACTIVE 만).
 */
@Component
public class RagIndex {

  public static final String VERSION_SUFFIX =
      "-v2"; // v2(2026-11-28): documentType — v1 은 ES-First 1차 기준선
  static final String DEFINITION = "elasticsearch/rag-chunks-v2.json";

  private static final Logger log = LoggerFactory.getLogger(RagIndex.class);

  private final RestClient es;
  private final ObjectMapper mapper;

  public RagIndex(ChunkingProperties props, ObjectMapper mapper) {
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

  /** 별칭이 없으면 색인을 만들고 별칭(쓰기 색인)을 건다. 이미 있으면 그대로. */
  public void ensure(AccessScope scope) {
    String alias = scope.alias();
    if (exists("/_alias/" + alias)) {
      return;
    }
    String index = alias + VERSION_SUFFIX;
    if (!exists("/" + index)) {
      try (InputStream in = new ClassPathResource(DEFINITION).getInputStream()) {
        es.put()
            .uri("/" + index)
            .contentType(MediaType.APPLICATION_JSON)
            .body(in.readAllBytes())
            .retrieve()
            .toBodilessEntity();
      } catch (IOException e) {
        throw new IllegalStateException("색인 정의를 읽지 못함: " + DEFINITION, e);
      } catch (HttpClientErrorException e) {
        if (!e.getResponseBodyAsString().contains("resource_already_exists_exception")) {
          throw e;
        }
      }
    }
    es.post()
        .uri("/_aliases")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "actions",
                List.of(
                    Map.of("add", Map.of("index", index, "alias", alias, "is_write_index", true)))))
        .retrieve()
        .toBodilessEntity();
    log.info("RAG 색인 만듦 {} → {}", alias, index);
  }

  /** 그 문서의 ACTIVE 청크가 가진 문서 해시(없으면 null). */
  public @Nullable String activeHash(AccessScope scope, String documentId) {
    JsonNode r =
        post(
            "/" + scope.alias() + "/_search",
            Map.of(
                "size",
                1,
                "_source",
                List.of("contentSha256"),
                "query",
                Map.of(
                    "bool",
                    Map.of(
                        "filter",
                        List.of(
                            Map.of("term", Map.of("documentId", documentId)),
                            Map.of("term", Map.of("status", "ACTIVE")))))));
    JsonNode hits = r.path("hits").path("hits");
    if (hits.isEmpty()) {
      return null;
    }
    return hits.get(0).path("_source").path("contentSha256").asString(null);
  }

  /** 새 판 청크를 넣고, 같은 문서의 다른 판 청크를 지운다. 지운 수. */
  public long replace(AccessScope scope, IncomingDocument doc, List<Chunk> chunks, Instant at) {
    String alias = scope.alias();
    StringBuilder nd = new StringBuilder();
    for (Chunk c : chunks) {
      nd.append(json(Map.of("index", Map.of("_index", alias, "_id", c.chunkId())))).append('\n');
      Map<String, Object> src = new LinkedHashMap<>();
      src.put("chunkId", c.chunkId());
      src.put("documentId", doc.path());
      src.put("repository", doc.repository());
      src.put("commit", doc.commit());
      src.put("accessScope", scope.name());
      src.put("documentType", DocumentType.of(doc.path()).name());
      src.put("title", doc.title());
      src.put("heading", c.heading());
      src.put("headingLevel", c.headingLevel());
      src.put("sectionPath", c.sectionPath());
      src.put("ordinal", c.ordinal());
      src.put("content", c.content());
      src.put("chunkSha256", c.chunkSha256());
      src.put("contentSha256", doc.contentSha256());
      src.put("status", "ACTIVE");
      src.put("deliveryId", doc.deliveryId());
      src.put("indexedAt", at.toString());
      nd.append(json(src)).append('\n');
    }
    if (!chunks.isEmpty()) {
      JsonNode r =
          mapper.readTree(
              es.post()
                  .uri("/_bulk?refresh=wait_for")
                  .contentType(MediaType.parseMediaType("application/x-ndjson"))
                  .body(nd.toString().getBytes(StandardCharsets.UTF_8))
                  .retrieve()
                  .body(String.class));
      if (r.path("errors").asBoolean(false)) {
        throw new IllegalStateException("ES bulk 실패(" + alias + "): " + firstError(r));
      }
    }
    JsonNode del =
        post(
            "/" + alias + "/_delete_by_query?refresh=true&conflicts=proceed",
            Map.of(
                "query",
                Map.of(
                    "bool",
                    Map.of(
                        "filter",
                        List.of(Map.of("term", Map.of("documentId", doc.path()))),
                        "must_not",
                        List.of(
                            Map.of(
                                "term",
                                Map.of("contentSha256", String.valueOf(doc.contentSha256()))))))));
    return del.path("deleted").asLong(0);
  }

  /** 그 문서의 ACTIVE 청크를 RETIRED 로. 바꾼 수. */
  public long retire(AccessScope scope, String documentId, Instant at) {
    JsonNode r =
        post(
            "/" + scope.alias() + "/_update_by_query?refresh=true&conflicts=proceed",
            Map.of(
                "script",
                Map.of(
                    "lang",
                    "painless",
                    "source",
                    "ctx._source.status = 'RETIRED'; ctx._source.retiredAt = params.at",
                    "params",
                    Map.of("at", at.toString())),
                "query",
                Map.of(
                    "bool",
                    Map.of(
                        "filter",
                        List.of(
                            Map.of("term", Map.of("documentId", documentId)),
                            Map.of("term", Map.of("status", "ACTIVE")))))));
    return r.path("updated").asLong(0);
  }

  private boolean exists(String uri) {
    try {
      es.head().uri(uri).retrieve().toBodilessEntity();
      return true;
    } catch (HttpClientErrorException.NotFound e) {
      return false;
    }
  }

  private JsonNode post(String uri, Object body) {
    return mapper.readTree(
        es.post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .body(json(body))
            .retrieve()
            .body(String.class));
  }

  private String json(Object o) {
    return mapper.writeValueAsString(o);
  }

  private static String firstError(JsonNode bulk) {
    for (JsonNode item : bulk.path("items")) {
      JsonNode err = item.path("index").path("error");
      if (!err.isMissingNode()) {
        return err.path("type").asString("") + " " + err.path("reason").asString("");
      }
    }
    return "?";
  }
}
