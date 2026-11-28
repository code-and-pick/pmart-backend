package com.pmart.integration.application;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 문서 저장소 웹훅 흐름 설정({@code pmart.integration.docs}, 정본은 pmart-config). 비밀(웹훅 서명·GitHub 토큰)은 {@code
 * {cipher}}.
 *
 * @param repository 저장소 owner/name — 다른 저장소의 웹훅은 버린다
 * @param branch 따라갈 브랜치(이 브랜치 push 만)
 * @param webhookSecret 웹훅 서명 비밀(GitHub 웹훅 Secret 과 같은 값). 없으면 모든 웹훅을 거절한다
 * @param githubToken private 저장소 읽기 토큰(Contents 읽기 전용)
 * @param githubApiUrl GitHub API 주소(시험은 흉내 서버)
 * @param includeExtensions 내용을 추출할 파일 확장자(그 밖 파일은 건너뜀)
 * @param excludeFileNames 건너뛸 파일 이름(폴더마다 있는 작성 규칙 README 등)
 * @param maxBytes 파일 하나 최대 크기
 * @param chunkingUrl 청킹 서비스 받는 주소(POST). 없으면 추출만 하고 기록을 남긴다(받는 서비스가 생기기 전)
 * @param timeout GitHub·청킹 서비스 호출 시간 제한
 * @param retries 일시 실패 다시 시도 횟수
 */
@ConfigurationProperties("pmart.integration.docs")
public record DocsProperties(
    String repository,
    String branch,
    @Nullable String webhookSecret,
    @Nullable String githubToken,
    String githubApiUrl,
    List<String> includeExtensions,
    List<String> excludeFileNames,
    int maxBytes,
    @Nullable String chunkingUrl,
    Duration timeout,
    int retries) {

  public DocsProperties {
    if (branch == null || branch.isBlank()) {
      branch = "main";
    }
    if (githubApiUrl == null || githubApiUrl.isBlank()) {
      githubApiUrl = "https://api.github.com";
    }
    includeExtensions = includeExtensions == null ? List.of(".md") : List.copyOf(includeExtensions);
    excludeFileNames =
        excludeFileNames == null ? List.of("README.md") : List.copyOf(excludeFileNames);
    if (maxBytes <= 0) {
      maxBytes = 512 * 1024;
    }
    if (timeout == null) {
      timeout = Duration.ofSeconds(15);
    }
    if (retries < 0) {
      retries = 0;
    }
  }

  /** 이 경로의 내용을 추출하는가(확장자·제외 이름). */
  public boolean included(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1);
    return !excludeFileNames.contains(name) && includeExtensions.stream().anyMatch(path::endsWith);
  }

  public boolean chunkingConfigured() {
    return chunkingUrl != null && !chunkingUrl.isBlank();
  }
}
