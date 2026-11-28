package com.pmart.customerassistant.api;

import com.pmart.customerassistant.api.generated.InternalApi;
import com.pmart.customerassistant.api.generated.model.SharedKnowledgeDocument;
import com.pmart.customerassistant.api.generated.model.SharedKnowledgeDocumentList;
import com.pmart.customerassistant.application.KnowledgeDocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi 구현(ADR-0094 결정 3) — 운영자 RAG 가 같이 쓰는 문서(customer_app)의 지금 판을 가져간다. 토큰 없음(서비스 사이).
 */
@RestController
public class InternalKnowledgeController implements InternalApi {

  private final KnowledgeDocumentService service;

  public InternalKnowledgeController(KnowledgeDocumentService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<SharedKnowledgeDocumentList> listSharedKnowledgeDocuments(
      String xSourceSystem) {
    return ResponseEntity.ok(
        new SharedKnowledgeDocumentList()
            .items(
                service.shared().stream()
                    .map(
                        d ->
                            new SharedKnowledgeDocument()
                                .documentId(d.summary().documentId())
                                .version(d.summary().version())
                                .title(d.summary().title())
                                .content(d.content())
                                .contentSha256(d.summary().contentSha256()))
                    .toList()));
  }
}
