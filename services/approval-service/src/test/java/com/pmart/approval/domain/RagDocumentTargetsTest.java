package com.pmart.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.approval.domain.RagDocumentTargets.Line;
import com.pmart.common.web.PmartException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RAG 문서 승인 형식(ADR-0092). */
class RagDocumentTargetsTest {

  static final String SVC = "ai-assistant-service";
  static final String DOC = "docs/rag/rag-docs/backoffice/05-customer-center-cases.md";
  static final String SHA = "a".repeat(64);

  @Test
  void acceptsUpsertAndRetire() {
    assertThatCode(
            () ->
                RagDocumentTargets.validate(
                    SVC, "KDR-1a2b", 3, List.of(new Line(DOC, "UPSERT", "KDR-1a2b", SHA))))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                RagDocumentTargets.validate(
                    SVC, "KDR-1a2b", 3, List.of(new Line(DOC, "RETIRE", "KDR-1a2b", null))))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsWrongTargetVersionAndLineCount() {
    assertThatThrownBy(
            () ->
                RagDocumentTargets.validate(
                    "search-service",
                    "x",
                    null,
                    List.of(
                        new Line(DOC, "UPSERT", "KDR-1", SHA),
                        new Line(DOC, "UPSERT", "KDR-1", SHA))))
        .isInstanceOfSatisfying(
            PmartException.class,
            e ->
                assertThat(e.details())
                    .extracting(PmartException.Detail::field)
                    .containsExactly(
                        "targetService", "targetRef", "targetExpectedVersion", "lines"));
  }

  @Test
  void rejectsBadLine() {
    assertThatThrownBy(
            () ->
                RagDocumentTargets.validate(
                    SVC,
                    "KDR-1",
                    0,
                    List.of(new Line("docs/business/x.md", "RENAME", "KDR-2", "ABC"))))
        .isInstanceOfSatisfying(
            PmartException.class,
            e ->
                assertThat(e.details())
                    .extracting(PmartException.Detail::field)
                    .containsExactly(
                        "lines[0].lineKey", "lines[0].attributes.op", "lines[0].attributes.draftId"));
    assertThatThrownBy(
            () ->
                RagDocumentTargets.validate(
                    SVC, "KDR-1", 0, List.of(new Line(DOC, "UPSERT", "KDR-1", null))))
        .isInstanceOfSatisfying(
            PmartException.class,
            e ->
                assertThat(e.details())
                    .extracting(PmartException.Detail::field)
                    .containsExactly("lines[0].attributes.contentSha256"));
  }
}
