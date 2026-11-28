package com.pmart.chunking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.chunking.domain.AccessScope;
import com.pmart.chunking.domain.Chunk;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

  static final String DOC =
      """
      # PMart 오배송 처리 정책

      시행일: 2026년 11월 20일

      ## 제1조 (목적)
      이 정책은 오배송 처리 기준을 정합니다.

      ## 제2조 (접수)
      고객이 다른 상품을 받았다고 알리면 고객센터 상담원이 접수합니다.

      | 경우 | 처리 |
      |---|---|
      | 일부만 다름 | 다른 품목만 회수 |
      """;

  final MarkdownChunker chunker =
      new MarkdownChunker(new ChunkingProperties(null, 0, 0, Duration.ofSeconds(1), null));

  @Test
  void oneChunkPerHeadingWithSectionPath() {
    List<Chunk> chunks = chunker.chunk("operator/order_claim/07-misdelivery.md", "h1", DOC, null);
    assertThat(chunks)
        .extracting(Chunk::heading)
        .containsExactly("PMart 오배송 처리 정책", "제1조 (목적)", "제2조 (접수)");
    assertThat(chunks)
        .extracting(Chunk::sectionPath)
        .containsExactly(
            "PMart 오배송 처리 정책", "PMart 오배송 처리 정책 > 제1조 (목적)", "PMart 오배송 처리 정책 > 제2조 (접수)");
    assertThat(chunks).extracting(Chunk::headingLevel).containsExactly(1, 2, 2);
    assertThat(chunks.get(2).content()).contains("고객센터 상담원이 접수").contains("일부만 다름");
    assertThat(chunks).extracting(Chunk::ordinal).containsExactly(0, 1, 2);
  }

  @Test
  void chunkIdIsStablePerVersionAndChangesWithContent() {
    var a = chunker.chunk("d.md", "hash-1", DOC, null);
    var b = chunker.chunk("d.md", "hash-1", DOC, null);
    var c = chunker.chunk("d.md", "hash-2", DOC, null);
    assertThat(a).extracting(Chunk::chunkId).isEqualTo(b.stream().map(Chunk::chunkId).toList());
    assertThat(c.get(0).chunkId()).isNotEqualTo(a.get(0).chunkId());
    assertThat(a).extracting(Chunk::chunkId).doesNotHaveDuplicates();
  }

  @Test
  void longSectionIsSplitByTokenSplitter() {
    String longSection = "## 제3조 (긴 절)\n" + "회수는 지원센터가 맡고 결과를 접수 건에 남깁니다. ".repeat(400);
    var chunks = chunker.chunk("d.md", "h", "# 제목\n\n" + longSection, "제목");
    assertThat(chunks.size()).isGreaterThan(2);
    // 나눈 조각도 절의 제목·경로를 그대로 갖는다(실제 문서 backoffice/05 제15조에서 빠졌던 것)
    assertThat(chunks.subList(1, chunks.size()))
        .allSatisfy(
            c -> {
              assertThat(c.heading()).isEqualTo("제3조 (긴 절)");
              assertThat(c.headingLevel()).isEqualTo(2);
              assertThat(c.sectionPath()).isEqualTo("제목 > 제3조 (긴 절)");
            });
  }

  @Test
  void sectionWithInlineCodeKeepsItsLevelAndPath() {
    // 리더는 인라인 코드가 있는 절의 category 를 code_inline 으로 바꾼다(실제 backoffice/05 제15조)
    String doc =
        """
        # 정책

        ## 제1조 (가)
        본문

        ## 제2조 (나)
        설정 `x.y` 를 씁니다.
        """;
    var chunks = chunker.chunk("d.md", "h", doc, "정책");
    assertThat(chunks).extracting(Chunk::headingLevel).containsExactly(2, 2);
    assertThat(chunks.get(1).sectionPath()).isEqualTo("정책 > 제2조 (나)");
  }

  @Test
  void accessScopeByPath() {
    assertThat(AccessScope.of("operator/mfc/01-a.md")).containsExactly(AccessScope.OPERATOR);
    assertThat(AccessScope.of("customer/customer_app/08-order-cancel.md"))
        .containsExactly(AccessScope.CUSTOMER, AccessScope.OPERATOR);
    assertThat(AccessScope.of("customer/faq/02-cancel.md")).containsExactly(AccessScope.CUSTOMER);
    assertThat(AccessScope.of("README.md")).isEmpty();
  }
}
