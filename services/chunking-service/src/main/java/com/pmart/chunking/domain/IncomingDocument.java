package com.pmart.chunking.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * integration-service 가 보내는 추출 내용(계약 integration-service.yaml {@code ExtractedDocument}) — 파일 하나.
 *
 * @param repository 저장소(owner/name)
 * @param commit 내용을 읽은 커밋
 * @param path 저장소 안 경로 = 문서 ID
 * @param status ADDED·MODIFIED·REMOVED
 * @param title 첫 줄 제목
 * @param content 본문(삭제면 null)
 * @param contentSha256 본문 해시(삭제면 null)
 * @param deliveryId 웹훅 전달 ID
 * @param extractedAt 추출 시각
 */
public record IncomingDocument(
    String repository,
    String commit,
    String path,
    String status,
    @Nullable String title,
    @Nullable String content,
    @Nullable String contentSha256,
    @Nullable String deliveryId,
    @Nullable Instant extractedAt) {

  public boolean removed() {
    return "REMOVED".equals(status);
  }
}
