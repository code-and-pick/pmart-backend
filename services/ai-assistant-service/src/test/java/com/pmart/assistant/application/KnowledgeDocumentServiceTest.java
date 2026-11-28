package com.pmart.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.application.KnowledgeDocumentService.IndexState;
import com.pmart.assistant.infrastructure.KnowledgeStore.DocumentSummary;
import com.pmart.assistant.infrastructure.KnowledgeStore.DraftRow;
import com.pmart.assistant.infrastructure.KnowledgeStore.ReindexRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 승인 요청 본문·색인 상태 판정(ADR-0092). */
class KnowledgeDocumentServiceTest {

  static final String DOC = "docs/rag/rag-docs/backoffice/05-customer-center-cases.md";
  static final Instant T0 = Instant.parse("2026-11-28T00:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void approvalRequestMatchesApprovalServiceFormat() {
    var d =
        new DraftRow(
            "KDR-1",
            DOC,
            "UPSERT",
            3,
            "고객센터",
            "# 고객센터",
            "a".repeat(64),
            "admin-1",
            "rk",
            "OPEN",
            null,
            null,
            T0,
            T0);
    Map<String, Object> body = KnowledgeDocumentService.approvalRequest(d).body();
    assertThat(body)
        .containsEntry("type", "RAG_DOCUMENT")
        .containsEntry("targetService", "ai-assistant-service")
        .containsEntry("targetRef", "KDR-1")
        .containsEntry("targetExpectedVersion", 3)
        .containsEntry("allowedActions", List.of("APPLY_KNOWLEDGE_DOCUMENT"));
    assertThat((String) body.get("summary")).contains("고치기").contains("고객센터");
    var line = ((List<Map<String, Object>>) body.get("lines")).get(0);
    assertThat(line).containsEntry("lineKey", DOC);
    assertThat((Map<String, Object>) line.get("attributes"))
        .containsEntry("op", "UPSERT")
        .containsEntry("draftId", "KDR-1")
        .containsEntry("contentSha256", "a".repeat(64));

    var retire =
        new DraftRow(
            "KDR-2", DOC, "RETIRE", 3, "고객센터", null, null, "admin-1", "rk2", "OPEN", null, null, T0,
            T0);
    var attrs =
        (Map<String, Object>)
            ((List<Map<String, Object>>)
                    KnowledgeDocumentService.approvalRequest(retire).body().get("lines"))
                .get(0)
                .get("attributes");
    assertThat(attrs).doesNotContainKey("contentSha256").containsEntry("op", "RETIRE");
  }

  @Test
  void indexState() {
    var doc = new DocumentSummary(DOC, "backoffice", "t", 2, "h2", false, false, T0, "admin-1");
    var none = new ReindexRow(null, null, null, null, null, null);
    var requested = new ReindexRow(T0, null, null, null, null, null);
    var failedAfter = new ReindexRow(T0, T0, T0.plusSeconds(60), "FAILED", null, "down");
    assertThat(KnowledgeDocumentService.indexState(doc, Map.of(DOC, "h2"), none))
        .isEqualTo(IndexState.INDEXED);
    assertThat(KnowledgeDocumentService.indexState(doc, Map.of(), none))
        .isEqualTo(IndexState.NOT_INDEXED);
    assertThat(KnowledgeDocumentService.indexState(doc, Map.of(DOC, "h1"), requested))
        .isEqualTo(IndexState.PENDING);
    assertThat(KnowledgeDocumentService.indexState(doc, Map.of(DOC, "h1"), failedAfter))
        .isEqualTo(IndexState.FAILED);
    var retired = new DocumentSummary(DOC, "backoffice", "t", 3, "h2", true, false, T0, "admin-1");
    assertThat(KnowledgeDocumentService.indexState(retired, Map.of(), requested))
        .isEqualTo(IndexState.INDEXED);
    assertThat(KnowledgeDocumentService.indexState(retired, Map.of(DOC, "h2"), requested))
        .isEqualTo(IndexState.PENDING);
  }
}
