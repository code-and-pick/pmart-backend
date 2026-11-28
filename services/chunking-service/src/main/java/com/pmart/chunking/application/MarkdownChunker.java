package com.pmart.chunking.application;

import com.pmart.chunking.domain.Chunk;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

/**
 * 청킹 — 직접 나누지 않고 Spring AI 부품만 쓴다(사용자 2026-11-28 "자바코드는 폐기 … 기성 도구"):
 *
 * <ol>
 *   <li>{@link MarkdownDocumentReader}: 마크다운 제목(#·##…)마다 Document 하나(메타 {@code title}=제목, {@code
 *       category}= header_N).
 *   <li>{@link TokenTextSplitter}: 한 절이 길면(토큰 {@code chunk-tokens} 넘음) 문장 경계로 더 나눈다. 짧은 절은 그대로.
 * </ol>
 *
 * 제목 경로는 문서 제목(첫 # 제목) > 절 제목. 빈 청크는 버린다.
 */
@Component
public class MarkdownChunker {

  private final ChunkingProperties props;

  public MarkdownChunker(ChunkingProperties props) {
    this.props = props;
  }

  /**
   * @param title 문서 제목(첫 줄 {@code # 제목}, integration-service 가 보낸 값). 제목 아래 본문이 없으면 리더가 그 제목을 내지
   *     않으므로 경로용으로 따로 받는다
   */
  public List<Chunk> chunk(
      String documentId, String contentSha256, String content, @Nullable String title) {
    MarkdownDocumentReaderConfig cfg =
        MarkdownDocumentReaderConfig.builder()
            .withHorizontalRuleCreateDocument(true)
            .withIncludeCodeBlock(true)
            .withIncludeBlockquote(true)
            .build();
    List<Document> sections =
        new MarkdownDocumentReader(
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)), cfg)
            .get();
    TokenTextSplitter splitter =
        TokenTextSplitter.builder()
            .withChunkSize(props.chunkTokens())
            .withMinChunkSizeChars(props.minChunkChars())
            .withKeepSeparator(true)
            .build();
    String docTitle = title == null || title.isBlank() ? null : title.strip();
    List<Chunk> out = new ArrayList<>();
    for (Document section : sections) {
      // 제목·단계는 절(리더 결과)의 메타에서 — 나눈 조각에는 남지 않는다(2026-11-28 실제 문서 확인: backoffice/05 제15조)
      String heading = str(section.getMetadata().get("title"));
      int level = level(str(section.getMetadata().get("category")));
      if (level == 1 && docTitle == null) {
        docTitle = heading;
      }
      String path =
          level <= 1 || docTitle == null || docTitle.equals(heading)
              ? (heading.isEmpty() ? (docTitle == null ? "" : docTitle) : heading)
              : docTitle + " > " + heading;
      for (Document piece : splitter.apply(List.of(section))) {
        String text = piece.getText();
        if (text == null || text.isBlank()) {
          continue;
        }
        int ordinal = out.size();
        String body = text.strip();
        out.add(
            new Chunk(
                chunkId(documentId, contentSha256, ordinal),
                ordinal,
                heading,
                level,
                path,
                body,
                sha256(body)));
      }
    }
    return out;
  }

  private static String str(@Nullable Object o) {
    return o == null ? "" : o.toString();
  }

  /** {@code header_2} → 2, 그 밖 0. */
  static int level(String category) {
    if (category.startsWith("header_")) {
      try {
        return Integer.parseInt(category.substring("header_".length()));
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }

  static String chunkId(String documentId, String contentSha256, int ordinal) {
    return sha256(documentId + "\n" + contentSha256 + "\n" + ordinal).substring(0, 32);
  }

  static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
