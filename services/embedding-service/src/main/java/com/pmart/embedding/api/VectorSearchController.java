package com.pmart.embedding.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.embedding.application.VectorSearch;
import com.pmart.embedding.domain.Scope;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/internal/vector-searches} — rag-query-service 가 질문 벡터 검색을 부른다(ADR-0099, 서비스 간
 * 경로).
 */
@RestController
public class VectorSearchController {

  /** 요청: 범위(rag-operator·rag-customer 또는 OPERATOR·CUSTOMER), 질문, 개수(기본 10, 최대 50). */
  public record SearchRequest(
      @Nullable String scope, @Nullable String question, @Nullable Integer size) {}

  private final VectorSearch search;

  public VectorSearchController(VectorSearch search) {
    this.search = search;
  }

  @PostMapping("/api/v1/internal/vector-searches")
  public VectorSearch.Result search(@RequestBody SearchRequest req) {
    Scope s = req.scope() == null ? null : EmbeddingJobController.scope(req.scope());
    if (s == null || req.question() == null || req.question().isBlank()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED.name(),
          HttpStatus.BAD_REQUEST,
          "scope(rag-operator·rag-customer)와 question 이 필요합니다.",
          false,
          List.of());
    }
    return search.search(s, req.question().strip(), req.size() == null ? 10 : req.size());
  }
}
