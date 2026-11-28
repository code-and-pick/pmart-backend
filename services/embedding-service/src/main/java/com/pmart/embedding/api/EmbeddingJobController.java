package com.pmart.embedding.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.embedding.application.EmbeddingQueue;
import com.pmart.embedding.domain.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/internal/embedding-jobs} — chunking-service 가 문서 하나를 ES 에 반영한 뒤 알린다(계약
 * embedding-service.yaml). 바로 202 — 임베딩은 일 줄에서(느리다). 서비스 간 경로(게이트웨이에 없음, 사용자 토큰 없음).
 */
@RestController
public class EmbeddingJobController {

  /** 알림 본문: 문서 ID 와 바뀐 범위(별칭 rag-operator·rag-customer 또는 OPERATOR·CUSTOMER). */
  public record JobRequest(@Nullable String documentId, @Nullable List<String> scopes) {}

  private final EmbeddingQueue queue;

  public EmbeddingJobController(EmbeddingQueue queue) {
    this.queue = queue;
  }

  @PostMapping("/api/v1/internal/embedding-jobs")
  public ResponseEntity<Map<String, Object>> enqueue(@RequestBody JobRequest req) {
    String doc = req.documentId();
    if (doc == null || doc.isBlank() || req.scopes() == null || req.scopes().isEmpty()) {
      throw invalid();
    }
    List<String> queued = new ArrayList<>();
    for (String raw : req.scopes()) {
      Scope s = scope(raw);
      if (s == null) {
        throw invalid();
      }
      queue.enqueue(s, doc);
      queued.add(s.alias());
    }
    return ResponseEntity.accepted().body(Map.of("documentId", doc, "scopes", queued));
  }

  static @Nullable Scope scope(String raw) {
    for (Scope s : Scope.values()) {
      if (s.alias().equals(raw) || s.name().equals(raw)) {
        return s;
      }
    }
    return null;
  }

  private static PmartException invalid() {
    return new PmartException(
        ErrorCode.VALIDATION_FAILED.name(),
        HttpStatus.BAD_REQUEST,
        "documentId 와 scopes(rag-operator·rag-customer)가 필요합니다.",
        false,
        List.of());
  }
}
