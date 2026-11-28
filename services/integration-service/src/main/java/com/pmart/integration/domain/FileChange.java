package com.pmart.integration.domain;

/**
 * push 하나에서 파일 하나의 최종 변경(여러 커밋에 걸친 변경을 합친 결과).
 *
 * @param repository 저장소 이름(owner/name)
 * @param commit 그 push 의 마지막 커밋(after) — 내용은 이 판에서 읽는다
 * @param path 저장소 안 경로
 * @param status 추가·수정·삭제
 * @param deliveryId GitHub 웹훅 전달 ID(추적용)
 */
public record FileChange(
    String repository, String commit, String path, ChangeStatus status, String deliveryId) {

  public enum ChangeStatus {
    ADDED,
    MODIFIED,
    REMOVED
  }
}
