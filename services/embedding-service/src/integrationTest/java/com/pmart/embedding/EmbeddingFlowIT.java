package com.pmart.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.embedding.application.Reconciler;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * ES 청크 → 임베딩(fake) → pgvector(ADR-0098): 알림 → ES 의 ACTIVE 청크만 1024차원으로 같은 chunkId 로 저장, 두 번째는
 * 캐시·이미 맞음, 새 판은 옛 청크 내림, ES 에서 RETIRED 면 is_active=false, 알림 없이 들어온 문서는 맞춰 보기가 채움, 범위마다 따로.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off", "pmart.embedding.reconcile-interval=PT1H"})
class EmbeddingFlowIT {

  @ServiceConnection
  static final PostgreSQLContainer PG =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

  static final GenericContainer<?> ES =
      new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.18.0")
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "false")
          .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withCommand(
              "sh",
              "-c",
              "bin/elasticsearch-plugin list | grep -q analysis-nori"
                  + " || bin/elasticsearch-plugin install --batch analysis-nori;"
                  + " exec /usr/local/bin/docker-entrypoint.sh eswrapper")
          .withExposedPorts(9200)
          .waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(5)));

  static {
    PG.start();
    ES.start();
    // 색인은 chunking-service 의 실제 정의 파일로(ADR-0096 — 두 서비스 사이의 형식을 함께 확인)
    try {
      byte[] def =
          java.nio.file.Files.readAllBytes(
              java.nio.file.Path.of(
                  "../chunking-service/src/main/resources/elasticsearch/rag-chunks-v1.json"));
      for (String alias : List.of("rag-operator", "rag-customer")) {
        RestClient.create()
            .put()
            .uri(esUrl() + "/" + alias + "-v1")
            .contentType(MediaType.APPLICATION_JSON)
            .body(def)
            .retrieve()
            .toBodilessEntity();
        RestClient.create()
            .post()
            .uri(esUrl() + "/_aliases")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "actions",
                    List.of(Map.of("add", Map.of("index", alias + "-v1", "alias", alias)))))
            .retrieve()
            .toBodilessEntity();
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("pmart.embedding.elasticsearch-url", EmbeddingFlowIT::esUrl);
  }

  static String esUrl() {
    return "http://" + ES.getHost() + ":" + ES.getMappedPort(9200);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired Reconciler reconciler;

  static void putChunk(
      String index, String chunkId, String doc, String hash, int ordinal, String status) {
    Map<String, Object> src = new LinkedHashMap<>();
    src.put("chunkId", chunkId);
    src.put("documentId", doc);
    src.put("contentSha256", hash);
    src.put("commit", "c-" + hash);
    src.put("sectionPath", "정책 > 제" + (ordinal + 1) + "조");
    src.put("ordinal", ordinal);
    src.put("content", "본문 " + doc + " " + hash + " " + ordinal);
    src.put("status", status);
    RestClient.create()
        .put()
        .uri(esUrl() + "/" + index + "/_doc/" + chunkId + "?refresh=true")
        .contentType(MediaType.APPLICATION_JSON)
        .body(src)
        .retrieve()
        .toBodilessEntity();
  }

  static void retireAll(String index, String doc) {
    RestClient.create()
        .post()
        .uri(esUrl() + "/" + index + "/_update_by_query?refresh=true")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "script",
                Map.of("source", "ctx._source.status = 'RETIRED'"),
                "query",
                Map.of("term", Map.of("documentId", doc))))
        .retrieve()
        .toBodilessEntity();
  }

  void notifyJob(String doc, List<String> scopes) {
    var r =
        RestClient.create()
            .post()
            .uri("http://localhost:" + port + "/api/v1/internal/embedding-jobs")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("X-Source-System", "chunking-service")
            .body(Map.of("documentId", doc, "scopes", scopes))
            .retrieve()
            .toEntity(String.class);
    assertThat(r.getStatusCode().value()).isEqualTo(202);
  }

  int active(String scope, String doc) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM chunk_vector WHERE access_scope = ? AND document_id = ? AND is_active",
            Integer.class,
            scope,
            doc);
    return n == null ? 0 : n;
  }

  static void await(BooleanSupplier ok) throws InterruptedException {
    Instant until = Instant.now().plusSeconds(20);
    while (!ok.getAsBoolean()) {
      if (Instant.now().isAfter(until)) {
        throw new AssertionError("시간 안에 맞춰지지 않음");
      }
      Thread.sleep(100);
    }
  }

  @Test
  void esChunksBecomeVectorsAndFollowEsChanges() throws Exception {
    String doc = "operator/order_claim/07-misdelivery.md";
    putChunk("rag-operator", "a0", doc, "h1", 0, "ACTIVE");
    putChunk("rag-operator", "a1", doc, "h1", 1, "ACTIVE");
    putChunk("rag-operator", "x9", "operator/other.md", "z", 0, "RETIRED"); // RETIRED 는 말뭉치가 아니다

    notifyJob(doc, List.of("rag-operator"));
    await(() -> active("OPERATOR", doc) == 2);
    assertThat(
            jdbc.queryForObject(
                "SELECT vector_dims(embedding) FROM chunk_vector WHERE chunk_id = 'a0'",
                Integer.class))
        .isEqualTo(1024);
    assertThat(
            jdbc.queryForObject(
                "SELECT section_path || '|' || model FROM chunk_vector WHERE chunk_id = 'a1'",
                String.class))
        .isEqualTo("정책 > 제2조|fake");
    Integer cache =
        jdbc.queryForObject(
            "SELECT count(*) FROM embedding_cache WHERE text_sha256 IN"
                + " (SELECT text_sha256 FROM chunk_vector WHERE document_id = ?)",
            Integer.class,
            doc);
    assertThat(cache).isEqualTo(2);

    // 새 판: 청크 둘 중 하나는 같은 글(캐시), ES 에서 옛 판은 지워짐
    RestClient.create()
        .post()
        .uri(esUrl() + "/rag-operator/_delete_by_query?refresh=true")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("query", Map.of("term", Map.of("documentId", doc))))
        .retrieve()
        .toBodilessEntity();
    putChunk("rag-operator", "b0", doc, "h2", 0, "ACTIVE");
    notifyJob(doc, List.of("rag-operator"));
    await(() -> active("OPERATOR", doc) == 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chunk_vector WHERE document_id = ? AND NOT is_active",
                Integer.class,
                doc))
        .isEqualTo(2);

    // ES 에서 내림 → is_active=false(임베딩 없이)
    retireAll("rag-operator", doc);
    notifyJob(doc, List.of("OPERATOR"));
    await(() -> active("OPERATOR", doc) == 0);
  }

  @Test
  void reconcileFillsMissedDocumentsPerScope() throws Exception {
    String app = "customer/customer_app/08-order-cancel.md";
    putChunk("rag-customer", "c0", app, "k1", 0, "ACTIVE");
    putChunk("rag-operator", "c0", app, "k1", 0, "ACTIVE"); // 같은 chunkId, 범위만 다름
    assertThat(reconciler.reconcile()).isGreaterThanOrEqualTo(2);
    await(() -> active("CUSTOMER", app) == 1 && active("OPERATOR", app) == 1);
    // 같은 글이라 두 번째 범위는 캐시에서
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT text_sha256) FROM chunk_vector WHERE document_id = ?",
                Integer.class,
                app))
        .isEqualTo(1);
    await(() -> reconciler.reconcile() == 0);
  }
}
