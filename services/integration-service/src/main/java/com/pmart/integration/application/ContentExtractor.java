package com.pmart.integration.application;

import com.pmart.integration.domain.ContentExtraction;
import com.pmart.integration.domain.ExtractedDocument;
import com.pmart.integration.domain.FileChange;
import com.pmart.integration.domain.FileChange.ChangeStatus;
import com.pmart.integration.infrastructure.GitHubContents;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** 바뀐 파일 하나 → 추출 내용. 추가·수정이면 그 커밋의 판을 GitHub 에서 읽고, 삭제(또는 그 판에 파일이 없음)면 본문 없이. */
@Component
public class ContentExtractor {

  private final GitHubContents github;
  private final Clock clock;

  public ContentExtractor(GitHubContents github, Clock clock) {
    this.github = github;
    this.clock = clock;
  }

  public ExtractedDocument extract(FileChange c) {
    if (c.status() != ChangeStatus.REMOVED) {
      var raw = github.read(c.path(), c.commit());
      if (raw.isPresent()) {
        String content = ContentExtraction.normalize(raw.get());
        return new ExtractedDocument(
            c.repository(),
            c.commit(),
            c.path(),
            c.status(),
            ContentExtraction.title(content),
            content,
            ContentExtraction.sha256(content),
            c.deliveryId(),
            clock.instant());
      }
    }
    return new ExtractedDocument(
        c.repository(),
        c.commit(),
        c.path(),
        ChangeStatus.REMOVED,
        null,
        null,
        null,
        c.deliveryId(),
        clock.instant());
  }
}
