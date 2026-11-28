package com.pmart.assistant.application;

import com.pmart.assistant.infrastructure.AnswerStore;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.assistant.infrastructure.ingest.KnowledgeIngestor;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 통제된 작업. 평소 기동에서는 아무것도 하지 않는다.
 *
 * <ul>
 *   <li>{@code pmart.assistant.ingest.run=true}(Gradle {@code ingestKnowledge -Prepo=…}): 한 번 색인하고
 *       종료. {@code pmart.assistant.knowledge.source=db}(ADR-0092)면 없는 문서만 저장소에서 가져온 뒤 DB 의 지금 판으로
 *       색인.
 *   <li>{@code pmart.assistant.ingest.prune=true}(Gradle {@code pruneSnapshots}): RETIRED·FAILED
 *       스냅숏 정리 한 번 하고 종료.
 *   <li>답변 보존 정리: {@code pmart.assistant.answers.retention} 이 있을 때만(없으면 지우지 않는다).
 * </ul>
 */
@Component
public class KnowledgeJobs implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeJobs.class);
  public static final String MANIFEST = "knowledge/manifest.yml";

  private final KnowledgeIngestor ingestor;
  private final SnapshotStore snapshots;
  private final AnswerStore answers;
  private final AssistantProperties props;
  private final Clock clock;
  private final ConfigurableApplicationContext context;
  private final KnowledgeIndexing knowledge;
  private final KnowledgeProperties knowledgeProps;

  public KnowledgeJobs(
      KnowledgeIngestor ingestor,
      SnapshotStore snapshots,
      AnswerStore answers,
      AssistantProperties props,
      Clock clock,
      ConfigurableApplicationContext context,
      KnowledgeIndexing knowledge,
      KnowledgeProperties knowledgeProps) {
    this.knowledge = knowledge;
    this.knowledgeProps = knowledgeProps;
    this.ingestor = ingestor;
    this.snapshots = snapshots;
    this.answers = answers;
    this.props = props;
    this.clock = clock;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) {
    var ingest = props.getIngest();
    if (!ingest.isRun() && !ingest.isPrune()) {
      return;
    }
    int code = 0;
    try {
      if (ingest.isRun()) {
        if (ingest.getRepo() == null || ingest.getRepo().isBlank()) {
          throw new IllegalArgumentException("pmart.assistant.ingest.repo(-Prepo) 가 필요합니다.");
        }
        Path repo = Path.of(ingest.getRepo()).toAbsolutePath().normalize();
        var result =
            knowledgeProps.getKnowledge().dbSource()
                // ADR-0092: 없는 문서만 저장소에서 가져오고 DB 의 지금 판으로 색인(가져온 문서는 정본이 DB)
                ? importThenIndex(repo)
                : ingestor.ingest(repo, loadManifest());
        log.info(
            "ingestKnowledge 완료 snapshot={} chunks={} files={}",
            result.snapshotId(),
            result.chunkCount(),
            result.fileCount());
      }
      if (ingest.isPrune()) {
        int n = pruneSnapshots();
        log.info("pruneSnapshots 완료 deleted={}", n);
      }
    } catch (RuntimeException e) {
      log.error("knowledge job 실패: {}", e.getMessage());
      code = 1;
    }
    int exit = code;
    System.exit(SpringApplication.exit(context, () -> exit));
  }

  private KnowledgeIngestor.Result importThenIndex(Path repo) {
    knowledge.importFromRepo(repo, loadManifest());
    return knowledge.indexFromDb();
  }

  public static KnowledgeManifest loadManifest() {
    try (InputStream in = new ClassPathResource(MANIFEST).getInputStream()) {
      return KnowledgeManifest.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("manifest 를 읽지 못했습니다: " + MANIFEST, e);
    }
  }

  public int pruneSnapshots() {
    return snapshots.prune(clock.instant().minus(props.getIngest().getPruneGrace()));
  }

  /**
   * 보존 정리(ADR-0084, 사용자 2026-11-20 "14일 — 마지막 질문에서"): 마지막 질문 뒤 기간이 지난 채팅 세션을 답변째 지운다. 기간이 설정되지 않았으면
   * 아무것도 지우지 않는다. 처리 중 답변이 있는 세션은 지우지 않는다.
   */
  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
  public void purgeExpiredAnswers() {
    Duration retention = props.getAnswers().getRetention();
    if (retention == null) {
      return;
    }
    int n = answers.deleteSessionsLastAskedBefore(clock.instant().minus(retention));
    if (n > 0) {
      log.info("assistant chat sessions purged={}", n);
    }
  }
}
