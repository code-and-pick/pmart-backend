package com.pmart.assistant.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 고객 RAG(customer-assistant-service)에서 같이 쓰는 문서(customer_app)의 지금 판을 받는다(ADR-0094 결정 3 — 같은 문서는 원본
 * 하나). 내부 API {@code GET /api/v1/internal/knowledge-documents/shared} — 토큰 없음(서비스 사이, ADR-0026).
 * 본문은 로그에 남기지 않는다.
 */
public class SharedKnowledgeClient {

  /** 받은 문서 하나. */
  public record SharedDoc(
      String documentId, int version, String title, String content, String contentSha256) {}

  private final RestClient http;

  public SharedKnowledgeClient(RestClient.Builder builder, String baseUrl) {
    this.http =
        builder
            .baseUrl(baseUrl.replaceAll("/+$", ""))
            .defaultHeader("X-Source-System", "ai-assistant-service")
            .build();
  }

  /** 지금 판 목록. 실패하면 예외(부르는 쪽이 지난 사본으로 계속한다). */
  public List<SharedDoc> fetch() {
    JsonNode body =
        http.get()
            .uri("/api/v1/internal/knowledge-documents/shared")
            .retrieve()
            .body(JsonNode.class);
    List<SharedDoc> out = new ArrayList<>();
    if (body != null) {
      for (JsonNode d : body.path("items")) {
        out.add(
            new SharedDoc(
                d.path("documentId").asString(),
                d.path("version").asInt(),
                d.path("title").asString(),
                d.path("content").asString(),
                d.path("contentSha256").asString()));
      }
    }
    return out;
  }
}
