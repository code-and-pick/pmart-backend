package com.pmart.embedding.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * ES RAG 색인의 청크 하나(chunking-service 가 넣은 것, ADR-0096) — 임베딩의 말뭉치.
 *
 * @param chunkId 청크 ID(pgvector 에도 같은 ID)
 * @param documentId 문서 ID(저장소 경로)
 * @param contentSha256 문서 판 해시
 * @param commit 문서 커밋
 * @param sectionPath 문서 제목 > 절 제목
 * @param ordinal 문서 안 순번
 * @param content 본문
 */
public record EsChunk(
    String chunkId,
    String documentId,
    String contentSha256,
    @Nullable String commit,
    String sectionPath,
    int ordinal,
    String content) {

  /**
   * 임베딩에 넣는 글 = 제목 경로 + 빈 줄 + 본문. 조 제목("제15조 (상담 본인 확인과 주문 확인)")이 본문에 없는 뜻을 담아서 함께 넣는다. 문서 쪽에는 지시문을
   * 붙이지 않는다(질문 쪽만 — Qwen3-Embedding 권장).
   */
  public String embeddingText() {
    return sectionPath.isBlank() ? content : sectionPath + "\n\n" + content;
  }

  public String embeddingTextSha256() {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(embeddingText().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
