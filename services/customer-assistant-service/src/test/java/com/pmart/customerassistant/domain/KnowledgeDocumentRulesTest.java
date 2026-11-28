package com.pmart.customerassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.customerassistant.domain.KnowledgeDocumentRules.Problem;
import org.junit.jupiter.api.Test;

/** 고객 RAG 문서 형식(ADR-0094). */
class KnowledgeDocumentRulesTest {

  static final String APP = "docs/rag/rag-docs/customer_app/08-order-cancel.md";
  static final String FAQ = "docs/business/customer-faq/02-cancel.md";

  @Test
  void documentIdsAndDomains() {
    assertThat(KnowledgeDocumentRules.domainOf(APP)).isEqualTo("customer-app");
    assertThat(KnowledgeDocumentRules.domainOf(FAQ)).isEqualTo("customer-faq");
    assertThat(KnowledgeDocumentRules.domainOf("docs/rag/rag-docs/backoffice/05-a.md")).isNull();
    assertThat(KnowledgeDocumentRules.readOnly(APP)).isFalse();
  }

  @Test
  void faqMayUseStatusTagsButAppMayNot() {
    assertThat(KnowledgeDocumentRules.check(FAQ, "UPSERT", "# 주문 취소\n\n## 취소하고 싶어요 [확정 정책]\n\n답"))
        .isEmpty();
    assertThat(KnowledgeDocumentRules.check(APP, "UPSERT", "# 안내\n[확정 정책] 문장"))
        .extracting(Problem::message)
        .anyMatch(m -> m.contains("확정 정책"));
    assertThat(
            KnowledgeDocumentRules.check("docs/rag/rag-docs/backoffice/05-a.md", "UPSERT", "# 제목"))
        .extracting(Problem::field)
        .containsExactly("documentId");
  }
}
