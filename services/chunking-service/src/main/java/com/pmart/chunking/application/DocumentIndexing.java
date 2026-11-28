package com.pmart.chunking.application;

import com.pmart.chunking.domain.AccessScope;
import com.pmart.chunking.domain.Chunk;
import com.pmart.chunking.domain.IncomingDocument;
import com.pmart.chunking.infrastructure.RagIndex;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 추출 내용 하나 → ES. 경로로 접근 범위(색인)를 정하고, 추가·수정이면 청킹해 그 문서를 새 판으로 바꾸고(같은 해시면 건너뜀 — 멱등), 삭제면 RETIRED 로
 * 내린다. 다음 단계(ES 청크 → 임베딩 → pgvector, 계획 §1-2 단계 3·4)는 아직 없다.
 */
@Service
public class DocumentIndexing {

  private static final Logger log = LoggerFactory.getLogger(DocumentIndexing.class);

  /** 처리 결과(응답). */
  public record Result(String result, String documentId, int chunks, List<String> indexes) {}

  private final MarkdownChunker chunker;
  private final RagIndex index;
  private final Clock clock;

  public DocumentIndexing(MarkdownChunker chunker, RagIndex index, Clock clock) {
    this.chunker = chunker;
    this.index = index;
    this.clock = clock;
  }

  public Result handle(IncomingDocument doc) {
    List<AccessScope> scopes = AccessScope.of(doc.path());
    if (scopes.isEmpty()) {
      log.info("색인 대상 경로 아님 — 건너뜀 path={}", doc.path());
      return new Result("IGNORED", doc.path(), 0, List.of());
    }
    Instant at = clock.instant();
    List<String> aliases = scopes.stream().map(AccessScope::alias).toList();
    if (doc.removed()) {
      long n = 0;
      for (AccessScope s : scopes) {
        index.ensure(s);
        n += index.retire(s, doc.path(), at);
      }
      log.info("문서 내림 path={} retiredChunks={} indexes={}", doc.path(), n, aliases);
      return new Result("RETIRED", doc.path(), (int) n, aliases);
    }
    String content = doc.content();
    String hash = doc.contentSha256();
    if (content == null || content.isBlank() || hash == null || hash.isBlank()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED.name(),
          HttpStatus.BAD_REQUEST,
          "추가·수정 문서는 content·contentSha256 이 필요합니다.",
          false,
          List.of());
    }
    List<Chunk> chunks = null;
    List<String> changed = new ArrayList<>();
    for (AccessScope s : scopes) {
      index.ensure(s);
      if (hash.equals(index.activeHash(s, doc.path()))) {
        continue;
      }
      if (chunks == null) {
        chunks = chunker.chunk(doc.path(), hash, content, doc.title());
      }
      long removed = index.replace(s, doc, chunks, at);
      changed.add(s.alias());
      log.info(
          "문서 색인 path={} commit={} chunks={} index={} replacedOld={}",
          doc.path(),
          doc.commit().length() > 7 ? doc.commit().substring(0, 7) : doc.commit(),
          chunks.size(),
          s.alias(),
          removed);
    }
    if (changed.isEmpty()) {
      return new Result("UNCHANGED", doc.path(), 0, aliases);
    }
    return new Result("INDEXED", doc.path(), chunks == null ? 0 : chunks.size(), changed);
  }
}
