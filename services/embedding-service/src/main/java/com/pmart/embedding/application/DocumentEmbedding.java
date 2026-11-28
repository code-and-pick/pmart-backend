package com.pmart.embedding.application;

import com.pmart.embedding.domain.EsChunk;
import com.pmart.embedding.domain.Scope;
import com.pmart.embedding.infrastructure.DescribedEmbedding;
import com.pmart.embedding.infrastructure.EsChunks;
import com.pmart.embedding.infrastructure.VectorStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 문서 하나(범위 하나)를 ES 와 맞춘다 — 계획 §1-2 단계 3·4, §1-3:
 *
 * <ol>
 *   <li>ES 에서 그 문서의 ACTIVE 청크를 읽는다(말뭉치).
 *   <li>이미 같은 chunkId·같은 모델 판으로 다 들어 있으면 끝.
 *   <li>임베딩할 글(제목 경로 + 본문)의 해시가 캐시에 있으면 그 벡터, 없을 때만 Ollama 로(묶어서).
 *   <li>같은 chunkId 로 pgvector 에 넣고, ES 에 없는(내린·옛 판) 청크는 is_active = false(임베딩 없이).
 * </ol>
 *
 * 멱등 — 같은 문서를 몇 번 맞춰도 결과가 같다.
 */
@Service
public class DocumentEmbedding {

  private static final Logger log = LoggerFactory.getLogger(DocumentEmbedding.class);

  /** 결과(기록·시험용). */
  public record Result(int chunks, int embedded, int cacheHits, int deactivated, boolean skipped) {}

  private final EsChunks es;
  private final VectorStore store;
  private final DescribedEmbedding embedding;
  private final EmbeddingProperties props;
  private final Clock clock;

  public DocumentEmbedding(
      EsChunks es,
      VectorStore store,
      DescribedEmbedding embedding,
      EmbeddingProperties props,
      Clock clock) {
    this.es = es;
    this.store = store;
    this.embedding = embedding;
    this.props = props;
    this.clock = clock;
  }

  public Result sync(Scope scope, String documentId) {
    Instant at = clock.instant();
    List<EsChunk> chunks = es.active(scope, documentId);
    List<String> ids = chunks.stream().map(EsChunk::chunkId).toList();
    if (!chunks.isEmpty()
        && store.upToDate(scope, documentId, ids, embedding.name(), embedding.version())) {
      return new Result(chunks.size(), 0, 0, 0, true);
    }
    Map<EsChunk, float[]> vectors = new HashMap<>();
    List<EsChunk> misses = new ArrayList<>();
    for (EsChunk c : chunks) {
      var hit = store.cached(c.embeddingTextSha256(), embedding.name(), embedding.version());
      if (hit.isPresent()) {
        vectors.put(c, hit.get());
      } else {
        misses.add(c);
      }
    }
    int cacheHits = vectors.size();
    for (int i = 0; i < misses.size(); i += props.batchSize()) {
      List<EsChunk> batch = misses.subList(i, Math.min(misses.size(), i + props.batchSize()));
      List<float[]> out = embedding.embedAll(batch.stream().map(EsChunk::embeddingText).toList());
      for (int k = 0; k < batch.size(); k++) {
        float[] v = out.get(k);
        if (v.length != props.dim()) {
          throw new IllegalStateException(
              "임베딩 차원이 설정과 다름: " + v.length + " ≠ " + props.dim() + " (모델·스키마 확인)");
        }
        EsChunk c = batch.get(k);
        store.putCache(c.embeddingTextSha256(), embedding.name(), embedding.version(), v);
        vectors.put(c, v);
      }
    }
    for (EsChunk c : chunks) {
      store.upsert(
          scope,
          c,
          c.embeddingTextSha256(),
          embedding.name(),
          embedding.version(),
          vectors.get(c),
          at);
    }
    int deactivated = store.deactivateOthers(scope, documentId, ids, at);
    log.info(
        "문서 벡터 맞춤 scope={} path={} chunks={} embedded={} cacheHits={} deactivated={}",
        scope,
        documentId,
        chunks.size(),
        misses.size(),
        cacheHits,
        deactivated);
    return new Result(chunks.size(), misses.size(), cacheHits, deactivated, false);
  }
}
