package com.pmart.integration.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 청킹 서비스로 넘기는 것 하나(ADR-0095) — 파일 하나의 추출 내용과 원본 정보. ES-First 적재(계획 §1-2 "원본 확정")가 쓰는 커밋·경로·해시를 함께
 * 싣는다. 삭제면 본문·해시가 없다.
 *
 * @param repository 저장소(owner/name)
 * @param commit 내용을 읽은 커밋
 * @param path 저장소 안 경로(문서 식별자)
 * @param status ADDED·MODIFIED·REMOVED
 * @param title 첫 줄 {@code # 제목}(마크다운일 때, 없으면 null)
 * @param content 추출한 본문(UTF-8 글, 줄바꿈 LF)
 * @param contentSha256 본문 SHA-256 16진수 — 같은 내용이면 받는 쪽이 건너뛸 수 있다
 * @param deliveryId 웹훅 전달 ID
 * @param extractedAt 추출 시각
 */
public record ExtractedDocument(
    String repository,
    String commit,
    String path,
    FileChange.ChangeStatus status,
    @Nullable String title,
    @Nullable String content,
    @Nullable String contentSha256,
    String deliveryId,
    Instant extractedAt) {}
