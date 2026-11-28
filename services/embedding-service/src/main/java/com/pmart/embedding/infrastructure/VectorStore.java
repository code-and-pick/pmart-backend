package com.pmart.embedding.infrastructure;

import com.pmart.embedding.domain.EsChunk;
import com.pmart.embedding.domain.Scope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * pgvector 저장(이 서비스 DB embedding_service) — 임베딩 캐시와 청크 벡터. 벡터는 문자열 {@code [a,b,…]::vector} 로 주고받는다.
 */
@Component
public class VectorStore {

  private final JdbcTemplate jdbc;

  public VectorStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<float[]> cached(String textSha256, String model, String version) {
    List<String> v =
        jdbc.queryForList(
            "SELECT embedding::text FROM embedding_cache WHERE text_sha256 = ? AND model = ? AND model_version = ?",
            String.class,
            textSha256,
            model,
            version);
    return v.isEmpty() ? Optional.empty() : Optional.of(parse(v.get(0)));
  }

  public void putCache(String textSha256, String model, String version, float[] vector) {
    jdbc.update(
        "INSERT INTO embedding_cache (text_sha256, model, model_version, embedding) VALUES (?, ?, ?, ?::vector)"
            + " ON CONFLICT DO NOTHING",
        textSha256,
        model,
        version,
        literal(vector));
  }

  /** 청크 벡터 넣기·바꾸기(ACTIVE 로). */
  public void upsert(
      Scope scope,
      EsChunk c,
      String textSha256,
      String model,
      String version,
      float[] vector,
      Instant at) {
    jdbc.update(
        """
        INSERT INTO chunk_vector (access_scope, chunk_id, document_id, content_sha256, text_sha256, commit_sha,
            section_path, ordinal, content, model, model_version, embedding, is_active, embedded_at, retired_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, true, ?, NULL)
        ON CONFLICT (access_scope, chunk_id) DO UPDATE SET
            document_id = EXCLUDED.document_id, content_sha256 = EXCLUDED.content_sha256,
            text_sha256 = EXCLUDED.text_sha256, commit_sha = EXCLUDED.commit_sha,
            section_path = EXCLUDED.section_path, ordinal = EXCLUDED.ordinal, content = EXCLUDED.content,
            model = EXCLUDED.model, model_version = EXCLUDED.model_version, embedding = EXCLUDED.embedding,
            is_active = true, embedded_at = EXCLUDED.embedded_at, retired_at = NULL
        """,
        scope.name(),
        c.chunkId(),
        c.documentId(),
        c.contentSha256(),
        textSha256,
        c.commit(),
        c.sectionPath(),
        c.ordinal(),
        c.content(),
        model,
        version,
        literal(vector),
        Timestamp.from(at));
  }

  /** 그 문서에서 남길 청크가 아닌 ACTIVE 행을 내린다(임베딩 없이 — 계획 §1-3). 내린 수. */
  public int deactivateOthers(Scope scope, String documentId, Collection<String> keep, Instant at) {
    return jdbc.update(
        "UPDATE chunk_vector SET is_active = false, retired_at = ? WHERE access_scope = ? AND document_id = ?"
            + " AND is_active AND NOT (chunk_id = ANY (?))",
        Timestamp.from(at),
        scope.name(),
        documentId,
        keep.toArray(String[]::new));
  }

  /** 지금 ACTIVE 청크가 이미 이 모델 판으로 들어 있는가(chunkId 집합이 같고 모두 이 판). */
  public boolean upToDate(
      Scope scope, String documentId, Collection<String> chunkIds, String model, String version) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM chunk_vector WHERE access_scope = ? AND document_id = ? AND is_active"
                + " AND model = ? AND model_version = ? AND chunk_id = ANY (?)",
            Integer.class,
            scope.name(),
            documentId,
            model,
            version,
            chunkIds.toArray(String[]::new));
    Integer all =
        jdbc.queryForObject(
            "SELECT count(*) FROM chunk_vector WHERE access_scope = ? AND document_id = ? AND is_active",
            Integer.class,
            scope.name(),
            documentId);
    return n != null && all != null && n == chunkIds.size() && all == chunkIds.size();
  }

  /** 벡터 검색 결과 한 줄. */
  public record VectorHit(String chunkId, String documentId, String sectionPath, double cosine) {}

  /** 범위의 ACTIVE 청크 중 질문 벡터와 코사인이 가까운 순(HNSW). */
  public java.util.List<VectorHit> nearest(Scope scope, float[] query, int size) {
    String q = literal(query);
    return jdbc.query(
        "SELECT chunk_id, document_id, section_path, 1 - (embedding <=> ?::vector) FROM chunk_vector"
            + " WHERE access_scope = ? AND is_active ORDER BY embedding <=> ?::vector LIMIT ?",
        (rs, i) ->
            new VectorHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4)),
        q,
        scope.name(),
        q,
        size);
  }

  /** 범위의 ACTIVE 문서 → 문서 판 해시(맞춰 보기용). */
  public Map<String, String> activeDocuments(Scope scope) {
    Map<String, String> out = new HashMap<>();
    jdbc.query(
        "SELECT document_id, min(content_sha256) FROM chunk_vector WHERE access_scope = ? AND is_active GROUP BY document_id",
        rs -> {
          out.put(rs.getString(1), rs.getString(2));
        },
        scope.name());
    return out;
  }

  static String literal(float[] v) {
    StringBuilder b = new StringBuilder(v.length * 10).append('[');
    for (int i = 0; i < v.length; i++) {
      if (i > 0) {
        b.append(',');
      }
      b.append(v[i]);
    }
    return b.append(']').toString();
  }

  static float[] parse(String s) {
    String body = s.strip();
    body = body.substring(1, body.length() - 1);
    if (body.isBlank()) {
      return new float[0];
    }
    String[] parts = body.split(",");
    float[] v = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      v[i] = Float.parseFloat(parts[i]);
    }
    return v;
  }
}
