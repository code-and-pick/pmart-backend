package com.pmart.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.domain.KnowledgeDocumentRules.Problem;
import org.junit.jupiter.api.Test;

/** 운영자 RAG 문서 형식(ADR-0092 결정 3). */
class KnowledgeDocumentRulesTest {

  static final String DOC = "docs/rag/rag-docs/backoffice/05-customer-center-cases.md";

  @Test
  void documentIdAndDomain() {
    assertThat(KnowledgeDocumentRules.domainOf(DOC)).isEqualTo("backoffice");
    assertThat(KnowledgeDocumentRules.domainOf("docs/rag/rag-docs/unknown/a.md")).isNull();
    assertThat(KnowledgeDocumentRules.domainOf("docs/business/a.md")).isNull();
    assertThat(KnowledgeDocumentRules.domainOf("docs/rag/rag-docs/backoffice/../x.md")).isNull();
    assertThat(KnowledgeDocumentRules.readOnly("docs/rag/rag-docs/customer_app/01-a.md")).isTrue();
    assertThat(KnowledgeDocumentRules.readOnly(DOC)).isFalse();
  }

  @Test
  void titleIsFirstHeading() {
    assertThat(KnowledgeDocumentRules.title("# 고객센터 정책\n\n본문")).isEqualTo("고객센터 정책");
    assertThat(KnowledgeDocumentRules.title("\n\n# 앞 빈 줄 허용\n")).isEqualTo("앞 빈 줄 허용");
    assertThat(KnowledgeDocumentRules.title("## 두 단계\n")).isNull();
    assertThat(KnowledgeDocumentRules.title("본문만")).isNull();
  }

  @Test
  void acceptsValidUpsertAndRetire() {
    assertThat(KnowledgeDocumentRules.check(DOC, "UPSERT", "# 제목\n\n## 제1조\n1. 내용")).isEmpty();
    assertThat(KnowledgeDocumentRules.check(DOC, "RETIRE", null)).isEmpty();
  }

  @Test
  void rejectsBadDrafts() {
    assertThat(KnowledgeDocumentRules.check("docs/business/x.md", "UPSERT", "# 제목"))
        .extracting(Problem::field)
        .containsExactly("documentId");
    assertThat(KnowledgeDocumentRules.check("docs/rag/rag-docs/customer_app/01-a.md", "UPSERT", "# 제목"))
        .extracting(Problem::field)
        .containsExactly("documentId");
    assertThat(KnowledgeDocumentRules.check(DOC, "RENAME", "# 제목"))
        .extracting(Problem::field)
        .containsExactly("op");
    assertThat(KnowledgeDocumentRules.check(DOC, "UPSERT", null))
        .extracting(Problem::field)
        .containsExactly("content");
    assertThat(KnowledgeDocumentRules.check(DOC, "RETIRE", "# 제목"))
        .extracting(Problem::field)
        .containsExactly("content");
    assertThat(KnowledgeDocumentRules.check(DOC, "UPSERT", "제목 없음"))
        .extracting(Problem::message)
        .anyMatch(m -> m.contains("# 제목"));
    // 정해지지 않은 내용 표시(RealDocumentsIngestTest 와 같은 기준)
    assertThat(KnowledgeDocumentRules.check(DOC, "UPSERT", "# 제목\n[설계안] 아직"))
        .extracting(Problem::message)
        .anyMatch(m -> m.contains("설계안"));
    assertThat(
            KnowledgeDocumentRules.check(
                DOC, "UPSERT", "# 제목\n" + "가".repeat(KnowledgeDocumentRules.MAX_BYTES / 3 + 1)))
        .extracting(Problem::message)
        .anyMatch(m -> m.contains("KB"));
  }
}
