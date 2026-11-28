package com.pmart.assistant.infrastructure.ingest;

import com.pmart.assistant.domain.Authority;
import com.pmart.assistant.domain.Lifecycle;
import com.pmart.assistant.domain.RequestHashing;
import com.pmart.assistant.infrastructure.AdvisoryLocks;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.assistant.infrastructure.SnapshotStore.ChunkRow;
import com.pmart.assistant.infrastructure.SnapshotStore.SourceFile;
import com.pmart.assistant.infrastructure.embedding.DescribedEmbeddingModel;
import com.pmart.assistant.infrastructure.embedding.EmbeddingSpec;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Doc;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Kind;
import com.pmart.assistant.infrastructure.ingest.MarkdownChunker.DocHeader;
import com.pmart.assistant.infrastructure.ingest.MarkdownChunker.ParsedChunk;
import com.pmart.assistant.infrastructure.ingest.SupersessionResolver.Applied;
import com.pmart.assistant.infrastructure.ingest.SupersessionResolver.Relation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 통제된 색인 작업(기동 때 하지 않는다). 절차:
 *
 * <ol>
 *   <li>색인 잠금(세션 advisory lock {@link AdvisoryLocks#INGEST}, 작업 전체 동안) — 못 잡으면 즉시 실패. 질의는 이 잠금과 무관.
 *   <li>새 스냅숏 BUILDING.
 *   <li>파일마다 바이트를 한 번만 읽고 같은 바이트로 sha256·청크를 만든다(index_source_file).
 *   <li>임베딩(캐시 키 = 모델·버전·차원·전처리·본문 해시), 차원 검증(선언 = 실제, 아니면 FAILED).
 *   <li>끝에서 모든 파일을 다시 해시 — 하나라도 다르면 FAILED(색인 도중 수정·삭제).
 *   <li>청크 > 0 확인 뒤 한 TX(스냅숏 전환 잠금 배타)에서 ACTIVE 전환, 이전 것은 RETIRED(지우지 않음).
 * </ol>
 *
 * 실패하면 그 스냅숏만 FAILED 이고 기존 ACTIVE 는 그대로다. 로그에는 문서 본문을 남기지 않는다(경로·개수만).
 */
public class KnowledgeIngestor {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestor.class);

  /** 비정상 종료로 남은 BUILDING 을 다음 색인이 FAILED 로 바꿀 때의 사유. */
  public static final String ABANDONED_REASON = "이전 색인이 끝나지 않고 중단됨(다음 색인 시작 때 정리)";

  /** git revision·작업 트리 상태. 없으면 revision=null, dirty=true(확인 불가 = 재현 보장 없음). */
  public interface SourceRevision {
    @Nullable String revision(Path repo);

    boolean dirty(Path repo);
  }

  public record Result(String snapshotId, int chunkCount, int fileCount) {}

  public static class IngestFailedException extends RuntimeException {
    public IngestFailedException(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  private final DataSource dataSource;
  private final SnapshotStore snapshots;
  private final DescribedEmbeddingModel embeddings;
  private final SourceRevision sourceRevision;
  private final Clock clock;
  private final MarkdownChunker chunker;

  public KnowledgeIngestor(
      DataSource dataSource,
      SnapshotStore snapshots,
      DescribedEmbeddingModel embeddings,
      SourceRevision sourceRevision,
      Clock clock,
      int maxChunkChars) {
    this.dataSource = dataSource;
    this.snapshots = snapshots;
    this.embeddings = embeddings;
    this.sourceRevision = sourceRevision;
    this.clock = clock;
    this.chunker = new MarkdownChunker(maxChunkChars);
  }

  /** 파일 대신 이미 읽은 문서(ADR-0092 — DB 의 지금 판). 경로 = 문서 ID. */
  public record LoadedDoc(Doc doc, byte[] bytes) {}

  /** 저장소 파일 색인(manifest 의 경로를 repo 에서 읽는다). */
  public Result ingest(Path repo, KnowledgeManifest manifest) {
    return locked(
        () ->
            build(
                manifest,
                doc -> {
                  Path file = repo.resolve(doc.path());
                  if (!Files.isRegularFile(file)) {
                    throw new IngestFailedException("manifest 경로 없음: " + doc.path(), null);
                  }
                  return Files.readAllBytes(file);
                },
                files -> {
                  // 5. 색인 중 변경 감지
                  for (SourceFile f : files) {
                    Path file = repo.resolve(f.path());
                    String now =
                        Files.isRegularFile(file)
                            ? RequestHashing.sha256(Files.readAllBytes(file))
                            : "(삭제됨)";
                    if (!now.equals(f.sha256())) {
                      throw new IngestFailedException("색인 도중 파일이 바뀌었습니다: " + f.path(), null);
                    }
                  }
                },
                sourceRevision.revision(repo),
                sourceRevision.dirty(repo)));
  }

  /**
   * 이미 읽은 문서 색인(ADR-0092 — DB 의 문서 판). 입력이 메모리에 고정돼 있어 색인 중 변경 검사가 없다.
   *
   * @param inputHash 입력 식별(스냅숏 manifest_hash) — 문서 ID·본문 해시로 만든다
   * @param revision 스냅숏 source_revision(예: {@code db:<승인 ID>})
   */
  public Result ingestDocuments(List<LoadedDoc> docs, String inputHash, @Nullable String revision) {
    Map<String, byte[]> bytes = new HashMap<>();
    List<Doc> metas = new ArrayList<>();
    for (LoadedDoc d : docs) {
      bytes.put(d.doc().path(), d.bytes());
      metas.add(d.doc());
    }
    KnowledgeManifest m =
        new KnowledgeManifest(1, "operator", List.copyOf(metas), List.of(), inputHash);
    return locked(() -> build(m, doc -> bytes.get(doc.path()), files -> {}, revision, false));
  }

  @FunctionalInterface
  private interface Reader {
    byte[] read(Doc doc) throws IOException;
  }

  @FunctionalInterface
  private interface Recheck {
    void check(List<SourceFile> files) throws IOException;
  }

  private Result locked(java.util.function.Supplier<Result> work) {
    try (Connection lockConn = dataSource.getConnection()) {
      if (!tryLock(lockConn)) {
        throw new IngestFailedException("다른 색인 작업이 진행 중입니다(INGEST 잠금).", null);
      }
      try {
        int abandoned = snapshots.failAbandonedBuilding(ABANDONED_REASON);
        if (abandoned > 0) {
          log.warn("이전 색인이 중단돼 남은 BUILDING 스냅숏 {}개를 FAILED 로 바꿈", abandoned);
        }
        return work.get();
      } finally {
        unlock(lockConn);
      }
    } catch (SQLException e) {
      throw new IngestFailedException("색인 잠금 연결 실패", e);
    }
  }

  private Result build(
      KnowledgeManifest manifest,
      Reader reader,
      Recheck recheck,
      @Nullable String revision,
      boolean dirty) {
    String snapshotId =
        "IDX-"
            + DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.UTC)
                .format(clock.instant())
            + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    EmbeddingSpec spec = embeddings.spec();
    snapshots.createBuilding(snapshotId, spec, manifest.hash(), revision, dirty, clock.instant());
    try {
      // 3. 바이트 한 번 읽기 → 해시·본문
      Map<String, byte[]> bytesByPath = new LinkedHashMap<>();
      List<SourceFile> files = new ArrayList<>();
      for (Doc doc : manifest.documents()) {
        byte[] bytes = reader.read(doc);
        bytesByPath.put(doc.path(), bytes);
        files.add(new SourceFile(doc.path(), RequestHashing.sha256(bytes), bytes.length));
      }
      snapshots.addSourceFiles(snapshotId, files);

      List<ChunkRow> rows = buildChunks(manifest, bytesByPath, spec);
      recheck.check(files);
      if (rows.isEmpty()) {
        throw new IngestFailedException("청크가 없습니다", null);
      }
      snapshots.addChunks(snapshotId, rows);
      snapshots.activate(snapshotId, rows.size(), clock.instant());
      log.info(
          "knowledge ingest done snapshot={} files={} chunks={}",
          snapshotId,
          files.size(),
          rows.size());
      return new Result(snapshotId, rows.size(), files.size());
    } catch (IngestFailedException e) {
      snapshots.fail(snapshotId, e.getMessage());
      log.warn("knowledge ingest failed snapshot={} reason={}", snapshotId, e.getMessage());
      throw e;
    } catch (IOException | RuntimeException e) {
      snapshots.fail(snapshotId, e.getClass().getSimpleName() + ": " + e.getMessage());
      log.warn(
          "knowledge ingest failed snapshot={} reason={}",
          snapshotId,
          e.getClass().getSimpleName());
      throw new IngestFailedException("색인 실패: " + e.getClass().getSimpleName(), e);
    }
  }

  List<ChunkRow> buildChunks(
      KnowledgeManifest manifest, Map<String, byte[]> bytesByPath, EmbeddingSpec spec) {
    Map<String, String> adrPathByNumber = new HashMap<>();
    for (Doc doc : manifest.documents()) {
      if (doc.kind() == Kind.ADR) {
        String name = Path.of(doc.path()).getFileName().toString();
        if (name.matches("^\\d{4}-.*")) {
          adrPathByNumber.put(name.substring(0, 4), doc.path());
        }
      }
    }
    Map<String, String> textByPath = new HashMap<>();
    Map<String, DocHeader> headers = new HashMap<>();
    Map<String, List<Relation>> relationsByTarget = new HashMap<>();
    for (Doc doc : manifest.documents()) {
      String text = new String(bytesByPath.get(doc.path()), StandardCharsets.UTF_8);
      textByPath.put(doc.path(), text);
      DocHeader h = MarkdownChunker.header(text);
      headers.put(doc.path(), h);
      if (doc.kind() == Kind.ADR && !h.supersessionLines().isEmpty()) {
        Authority a = adrAuthority(doc, h);
        for (Relation r :
            SupersessionResolver.parse(doc.path(), a, h.supersessionLines(), adrPathByNumber)) {
          relationsByTarget.computeIfAbsent(r.targetPath(), k -> new ArrayList<>()).add(r);
        }
      }
    }
    List<ChunkRow> rows = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Doc doc : manifest.documents()) {
      DocHeader h = headers.get(doc.path());
      Authority docDefault = doc.kind() == Kind.ADR ? adrAuthority(doc, h) : defaultAuthority(doc);
      List<Relation> relations = relationsByTarget.getOrDefault(doc.path(), List.of());
      for (ParsedChunk pc :
          chunker.chunk(textByPath.get(doc.path()), docDefault, doc.kind() == Kind.ADR)) {
        Applied applied = SupersessionResolver.apply(pc.lifecycle(), pc.decisionNo(), relations);
        Lifecycle lifecycle = applied.lifecycle();
        Authority authority = pc.authority();
        List<String> proposed = applied.proposedSupersededBy();
        String supersededBy = applied.supersededBy();
        for (KnowledgeManifest.Override o : manifest.overrides()) {
          if (o.path().equals(doc.path()) && pc.content().contains(o.contains())) {
            if (o.lifecycle() != null) {
              lifecycle = o.lifecycle();
            }
            if (o.supersededBy() != null) {
              supersededBy = o.supersededBy();
            }
            if (o.authority() != null) {
              authority = o.authority();
            }
            if (!o.proposedSupersededBy().isEmpty()) {
              List<String> merged = new ArrayList<>(proposed);
              o.proposedSupersededBy().stream()
                  .filter(p -> !merged.contains(p))
                  .forEach(merged::add);
              proposed = List.copyOf(merged);
            }
          }
        }
        String normalized = RequestHashing.normalize(pc.content());
        String chunkId =
            RequestHashing.sha256(
                doc.path() + "\n" + (pc.section() == null ? "" : pc.section()) + "\n" + normalized);
        if (!seen.add(chunkId)) {
          continue;
        }
        String embedInput = (pc.section() == null ? "" : pc.section() + "\n") + pc.content();
        String contentHash = RequestHashing.sha256(embedInput);
        float[] vector = embed(spec, contentHash, embedInput);
        rows.add(
            new ChunkRow(
                chunkId,
                doc.path(),
                doc.path(),
                pc.section(),
                doc.domain(),
                authority.name(),
                lifecycle.name(),
                supersededBy,
                proposed,
                doc.precedence(),
                pc.implRecordDate(),
                pc.implNote(),
                h.version(),
                h.effectiveDate(),
                doc.stage(),
                doc.accessScope(),
                pc.content(),
                contentHash,
                vector));
      }
    }
    return rows;
  }

  private float[] embed(EmbeddingSpec spec, String contentHash, String input) {
    var cached = snapshots.cachedEmbedding(spec, contentHash);
    if (cached.isPresent() && cached.get().length == spec.dim()) {
      return cached.get();
    }
    float[] v = embeddings.embed(input);
    if (v.length != spec.dim()) {
      throw new IngestFailedException("임베딩 차원 불일치: 선언 " + spec.dim() + ", 실제 " + v.length, null);
    }
    snapshots.cacheEmbedding(spec, contentHash, v);
    return v;
  }

  private static Authority adrAuthority(Doc doc, DocHeader h) {
    if (doc.authority() != null) {
      return doc.authority();
    }
    return h.adrStatusLine() == null
        ? Authority.PROPOSAL
        : Authority.fromAdrStatus(h.adrStatusLine());
  }

  private static Authority defaultAuthority(Doc doc) {
    return doc.authority() == null ? Authority.PROPOSAL : doc.authority();
  }

  private static boolean tryLock(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      ps.setLong(1, AdvisoryLocks.INGEST);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() && rs.getBoolean(1);
      }
    }
  }

  private static void unlock(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      ps.setLong(1, AdvisoryLocks.INGEST);
      ps.executeQuery().close();
    }
  }
}
