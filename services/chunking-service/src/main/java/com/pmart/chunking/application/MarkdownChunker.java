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
            // 기본값(5자 미만 조각 버림)은 짧은 절을 조용히 빼 버린다 — 빈 것만 버린다
            .withMinChunkLengthToEmbed(1)
            .build();
    String docTitle = title == null || title.isBlank() ? null : title.strip();
    java.util.Map<String, Integer> headingLevels = headingLevels(content);
    List<Chunk> out = new ArrayList<>();
    for (Document section : sections) {
      // 제목·단계는 절(리더 결과)의 메타에서 — 나눈 조각에는 남지 않는다(2026-11-28 실제 문서 확인: backoffice/05 제15조)
      String heading = str(section.getMetadata().get("title"));
      // 단계: 리더는 절에 인라인 코드가 있으면 category 를 header_N 대신 code_inline 으로 덮어쓴다(2026-11-28 backoffice/05
      // 제15조)
      // — header_N 이 아니면 원문 제목 줄의 # 개수로
      int level = level(str(section.getMetadata().get("category")));
      if (level == 0) {
        level = headingLevels.getOrDefault(heading, 0);
      }
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

  /** 원문 제목 줄(# … ######) → 단계. 같은 제목이 여럿이면 처음 것. */
  static java.util.Map<String, Integer> headingLevels(String content) {
    java.util.Map<String, Integer> m = new java.util.HashMap<>();
    for (String line : content.lines().toList()) {
      int n = 0;
      while (n < line.length() && n < 6 && line.charAt(n) == '#') {
        n++;
      }
      if (n > 0 && n < line.length() && line.charAt(n) == ' ') {
        m.putIfAbsent(line.substring(n + 1).strip(), n);
      }
    }
    return m;
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
