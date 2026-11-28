package com.pmart.chunking.domain;

/**
 * 청크 하나 = ES 문서 하나(계획 §1-2 단계 2). {@code chunkId} 는 (문서 ID, 문서 해시, 순번)에서 정해져 같은 판을 다시 넣어도 같다 — 다음
 * 단계(임베딩 → pgvector)가 같은 ID 를 쓴다.
 *
 * @param chunkId 청크 ID
 * @param ordinal 문서 안 순번(0부터)
 * @param heading 이 청크의 제목(마크다운 제목 줄)
 * @param headingLevel 제목 단계(1~6, 제목 없는 앞부분은 0)
 * @param sectionPath 문서 제목 > 절 제목
 * @param content 본문(Spring AI 리더가 낸 글)
 * @param chunkSha256 본문 해시
 */
public record Chunk(
    String chunkId,
    int ordinal,
    String heading,
    int headingLevel,
    String sectionPath,
    String content,
    String chunkSha256) {}
