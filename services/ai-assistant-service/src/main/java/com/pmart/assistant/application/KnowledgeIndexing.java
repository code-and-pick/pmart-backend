package com.pmart.assistant.application;

import com.pmart.assistant.domain.Authority;
import com.pmart.assistant.domain.KnowledgeDocumentRules;
import com.pmart.assistant.domain.RequestHashing;
import com.pmart.assistant.infrastructure.KnowledgeStore;
import com.pmart.assistant.infrastructure.KnowledgeStore.ActiveDoc;
import com.pmart.assistant.infrastructure.ingest.KnowledgeIngestor;
import com.pmart.assistant.infrastructure.ingest.KnowledgeIngestor.LoadedDoc;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Doc;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Kind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 문서 정본 DB 와 색인 사이(ADR-0092 결정 1·5·8).
 *
 * <ul>
 *   <li>{@link #importFromRepo}: 저장소 manifest 문서 중 DB 에 없는 것만 판 1 로 가져온다(이미 있는 것은 건드리지 않는다 — 가져온 뒤의 정본은
 *       DB).
 *   <li>{@link #indexFromDb}: DB 의 지금 판(내린 문서 제외) 전체로 새 스냅숏. 바뀌지 않은 조각은 임베딩 캐시를 그대로 쓴다.
 *   <li>{@link #reindexIfRequested}: 승인 반영이 남긴 재색인 요청을 1분 주기로 처리({@code source=db} 일 때만).
 * </ul>
 *
 * 지위·우선순위는 문서마다 고르지 않는다 — 모두 CONFIRMED·4(manifest 2026-11-20 "결정된 내용만"과 같다).
 */
@Service
public class KnowledgeIndexing {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexing.class);
  static final int PRECEDENCE = 4;

  private final KnowledgeStore store;
  private final KnowledgeIngestor ingestor;
  private final KnowledgeProperties props;
  private final Clock clock;
  private final @Nullable MeterRegistry meters;

  public KnowledgeIndexing(
      KnowledgeStore store,
      KnowledgeIngestor ingestor,
      KnowledgeProperties props,
      Clock clock,
      ObjectProvider<MeterRegistry> meters) {
    this.store = store;
    this.ingestor = ingestor;
    this.props = props;
    this.clock = clock;
    this.meters = meters.getIfAvailable();
  }

  /** 없는 문서만 가져온다. 가져온 수. */
  public int importFromRepo(Path repo, KnowledgeManifest manifest) {
    int n = 0;
    for (Doc doc : manifest.documents()) {
      Path file = repo.resolve(doc.path());
      if (!Files.isRegularFile(file)) {
        throw new IllegalStateException("manifest 경로 없음: " + doc.path());
      }
      String content;
      try {
        content = Files.readString(file, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      String domain = doc.domain() != null ? doc.domain() : KnowledgeDocumentRules.domainOf(doc.path());
      String title = KnowledgeDocumentRules.title(content);
      if (store.importIfMissing(
          doc.path(),
          domain == null ? "common" : domain,
          title == null ? Path.of(doc.path()).getFileName().toString() : title,
          content,
          RequestHashing.sha256(content.getBytes(StandardCharsets.UTF_8)),
          KnowledgeDocumentRules.readOnly(doc.path()),
          clock.instant())) {
        n++;
      }
    }
    log.info("knowledge import done imported={} manifest={}", n, manifest.documents().size());
    return n;
  }

  /** DB 의 지금 판으로 새 스냅숏(색인 잠금 — 다른 색인 중이면 실패). */
  public KnowledgeIngestor.Result indexFromDb() {
    List<ActiveDoc> docs = store.activeDocuments();
    if (docs.isEmpty()) {
      throw new KnowledgeIngestor.IngestFailedException("색인할 문서가 DB 에 없습니다 — 먼저 가져오기", null);
    }
    List<LoadedDoc> loaded = new ArrayList<>();
    StringBuilder input = new StringBuilder();
    for (ActiveDoc d : docs) {
      loaded.add(
          new LoadedDoc(
              new Doc(
                  d.documentId(),
                  d.domain(),
                  Kind.REFERENCE,
                  Authority.CONFIRMED,
                  PRECEDENCE,
                  null,
                  "operator"),
              d.content().getBytes(StandardCharsets.UTF_8)));
      input.append(d.documentId()).append(':').append(d.sha256()).append('\n');
    }
    String last =
        store.recentApplications(20).stream()
            .filter(a -> "APPLIED".equals(a.result()))
            .map(KnowledgeStore.ApplicationRow::approvalId)
            .findFirst()
            .orElse("import");
    return ingestor.ingestDocuments(
        loaded, RequestHashing.sha256(input.toString().getBytes(StandardCharsets.UTF_8)), "db:" + last);
  }

  /** 승인 반영 뒤 재색인(1분 주기). 실패하면 다음 주기에 다시 한다. */
  @Scheduled(
      fixedDelayString = "${pmart.assistant.knowledge.reindex-interval:PT1M}",
      initialDelayString = "${pmart.assistant.knowledge.reindex-initial-delay:PT1M}")
  public void reindexIfRequested() {
    if (!props.getKnowledge().dbSource()) {
      return;
    }
    runRequestedReindex();
  }

  /** 요청이 있으면 한 번 색인(시험·주기 작업 공용). 했으면 true. */
  public boolean runRequestedReindex() {
    if (!store.claimReindex(clock.instant())) {
      return false;
    }
    try {
      var r = indexFromDb();
      store.finishReindex("OK", r.snapshotId(), null, clock.instant());
      count("OK");
      log.info("knowledge reindex done snapshot={} chunks={}", r.snapshotId(), r.chunkCount());
    } catch (RuntimeException e) {
      store.finishReindex("FAILED", null, e.getMessage(), clock.instant());
      store.releaseReindex();
      count("FAILED");
      log.warn("knowledge reindex failed: {}", e.getMessage());
    }
    return true;
  }

  private void count(String result) {
    if (meters != null) {
      Counter.builder("pmart.assistant.knowledge.reindex")
          .description("승인 반영 뒤 재색인 결과(ADR-0092)")
          .tag("result", result)
          .register(meters)
          .increment();
    }
  }
}
