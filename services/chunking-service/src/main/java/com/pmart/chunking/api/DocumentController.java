package com.pmart.chunking.api;

import com.pmart.chunking.application.DocumentIndexing;
import com.pmart.chunking.domain.IncomingDocument;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/internal/documents} — integration-service 가 추출 내용을 보낸다(계약
 * chunking-service.yaml). 서비스 간 경로(게이트웨이에 없음, 사용자 토큰 없음). 같은 판을 다시 보내면 UNCHANGED(멱등).
 */
@RestController
public class DocumentController {

  private static final Set<String> STATUSES = Set.of("ADDED", "MODIFIED", "REMOVED");

  private final DocumentIndexing indexing;

  public DocumentController(DocumentIndexing indexing) {
    this.indexing = indexing;
  }

  @PostMapping("/api/v1/internal/documents")
  public DocumentIndexing.Result receive(@RequestBody IncomingDocument doc) {
    if (doc.path() == null
        || doc.path().isBlank()
        || doc.status() == null
        || !STATUSES.contains(doc.status())
        || doc.commit() == null
        || doc.repository() == null) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED.name(),
          HttpStatus.BAD_REQUEST,
          "repository·commit·path·status(ADDED·MODIFIED·REMOVED)가 필요합니다.",
          false,
          List.of());
    }
    return indexing.handle(doc);
  }
}
