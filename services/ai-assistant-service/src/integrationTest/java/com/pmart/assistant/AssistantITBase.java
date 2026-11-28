package com.pmart.assistant;

import com.pmart.assistant.infrastructure.embedding.DescribedEmbeddingModel;
import com.pmart.assistant.infrastructure.embedding.EmbeddingSpec;
import com.pmart.assistant.infrastructure.embedding.LocalHashEmbeddingModel;
import com.pmart.assistant.infrastructure.ingest.KnowledgeIngestor;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 흐름 통합 시험 공통: pgvector + 실제 앱(HTTP) + 시험 JWT. 외부 호출 없음(provider=local). 픽스처 문서(fixture/)를 임시 저장소로
 * 복사해 색인한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.assistant.product-search.base-url=${search.url}"
    })
@Import({TestJwtConfiguration.class, AssistantITBase.TestBeans.class})
@org.wiremock.spring.EnableWireMock({
  @org.wiremock.spring.ConfigureWireMock(name = "search", baseUrlProperties = "search.url")
})
abstract class AssistantITBase extends PgvectorContainerSupport {

  /** search-service 상품 검색(ADR-0083). 스텁이 없으면 404 → 상품 답 없이 문서 검색으로 답한다. */
  @org.wiremock.spring.InjectWireMock("search")
  com.github.tomakehurst.wiremock.WireMockServer search;

  static final List<String> FIXTURE_FILES =
      List.of(
          "docs/business/policy.md",
          "docs/adr/0001-service-token.md",
          "docs/adr/0002-no-service-token.md",
          "docs/adr/0003-category.md",
          "docs/secret/hq.md");

  /** 시험이 켜고 끄는 임베딩: 장애 흉내(503), 첫 호출 때 부수효과(색인 중 파일 변경), 차원 위반. */
  static class ControllableEmbedding implements DescribedEmbeddingModel {
    /** 임베딩 비교 평가(ADR-0051 D3): -PevalOllamaModel=<모델> 일 때만 Ollama, 아니면 해시 기준선. */
    final @Nullable OllamaEvalEmbedding eval = OllamaEvalEmbedding.fromSystemProperties();

    final DescribedEmbeddingModel delegate = eval != null ? eval : new LocalHashEmbeddingModel(384);
    final AtomicBoolean failing = new AtomicBoolean();
    final AtomicBoolean wrongDimension = new AtomicBoolean();
    final AtomicReference<@Nullable Runnable> onNextEmbed = new AtomicReference<>();

    /** 검색에 쓴 질문(ADR-0084 이어 묻기 시험). */
    final java.util.List<String> queries = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public EmbeddingSpec spec() {
      return delegate.spec();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      return delegate.call(request);
    }

    @Override
    public float[] embed(Document document) {
      return embed(document.getText());
    }

    @Override
    public float[] embedQuery(String question) {
      queries.add(question);
      if (failing.get()) {
        throw new IllegalStateException("embedding down (test)");
      }
      return delegate.embedQuery(question);
    }

    @Override
    public float[] embed(String text) {
      Runnable hook = onNextEmbed.getAndSet(null);
      if (hook != null) {
        hook.run();
      }
      if (failing.get()) {
        throw new IllegalStateException("embedding down (test)");
      }
      return wrongDimension.get() ? new float[10] : delegate.embed(text);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestBeans {
    @Bean
    @Primary
    ControllableEmbedding controllableEmbedding() {
      return new ControllableEmbedding();
    }

    /** 승인 회신(ADR-0092) — Kafka 대신 기록만. */
    @Bean
    @Primary
    RecordingReceipts recordingReceipts() {
      return new RecordingReceipts();
    }
  }

  /** 보낸 승인 회신(승인 ID, 본문). */
  static class RecordingReceipts
      implements com.pmart.assistant.application.KnowledgeApprovals.Receipts {
    final java.util.List<Map<String, Object>> sent =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public String sourceSystem() {
      return "ai-assistant-service";
    }

    @Override
    public void send(String approvalId, Map<String, Object> payload) {
      sent.add(payload);
    }
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired KnowledgeIngestor ingestor;
  @Autowired ControllableEmbedding embedding;

  final JsonMapper json = JsonMapper.builder().build();
  Path repo;

  @BeforeEach
  void resetState() throws IOException {
    jdbc.update("DELETE FROM chat_session"); // 답변도 함께(ON DELETE CASCADE, ADR-0084)
    jdbc.update("DELETE FROM policy_answer");
    embedding.queries.clear();
    jdbc.update("DELETE FROM index_snapshot");
    embedding.failing.set(false);
    embedding.wrongDimension.set(false);
    embedding.onNextEmbed.set(null);
    repo = Files.createTempDirectory("assistant-fixture");
    for (String f : FIXTURE_FILES) {
      Path target = repo.resolve(f);
      Files.createDirectories(target.getParent());
      try (InputStream in = new ClassPathResource("fixture/" + f).getInputStream()) {
        Files.copy(in, target);
      }
    }
  }

  static KnowledgeManifest fixtureManifest() {
    try (InputStream in = new ClassPathResource("fixture/manifest.yml").getInputStream()) {
      return KnowledgeManifest.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  KnowledgeIngestor.Result ingest() {
    return ingestor.ingest(repo, fixtureManifest());
  }

  @Nullable String activeSnapshot() {
    return jdbc.query(
        "SELECT snapshot_id FROM index_snapshot WHERE state='ACTIVE'",
        rs -> rs.next() ? rs.getString(1) : null);
  }

  // ---- HTTP ----

  record Http(int status, JsonNode body, HttpHeaders headers) {
    String text(String field) {
      return body.path(field).asString();
    }
  }

  static String operator(String id) {
    return TestJwt.operator(id, List.of("OPS"), List.of());
  }

  static String operatorWithoutSrc(String id) {
    return TestJwt.token(id, "OPERATOR", List.of("OPS"), List.of(), null);
  }

  Http ask(String token, String key, String question) {
    return ask(token, key, question, null, "backoffice-web");
  }

  Http ask(
      String token,
      String key,
      String question,
      @Nullable Map<String, String> context,
      String source) {
    var body =
        context == null
            ? Map.of("question", question)
            : Map.of("question", question, "context", context);
    return RestClient.create()
        .post()
        .uri("http://localhost:" + port + "/api/v1/assistant/policy-questions")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key)
        .header("X-Source-System", source)
        .contentType(MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(body))
        .exchange(
            (req, res) ->
                toHttp(
                    res.getStatusCode().value(), res.getBody().readAllBytes(), res.getHeaders()));
  }

  Http findByRequest(String token, String key, String source) {
    return RestClient.create()
        .get()
        .uri("http://localhost:" + port + "/api/v1/assistant/policy-questions?requestId={k}", key)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("X-Source-System", source)
        .exchange(
            (req, res) ->
                toHttp(
                    res.getStatusCode().value(), res.getBody().readAllBytes(), res.getHeaders()));
  }

  Http history(String token, String source, String query) {
    return RestClient.create()
        .get()
        .uri("http://localhost:" + port + "/api/v1/assistant/question-history" + query)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("X-Source-System", source)
        .exchange(
            (req, res) ->
                toHttp(
                    res.getStatusCode().value(), res.getBody().readAllBytes(), res.getHeaders()));
  }

  Http getAnswer(String token, String answerId) {
    return RestClient.create()
        .get()
        .uri("http://localhost:" + port + "/api/v1/assistant/policy-questions/{id}", answerId)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange(
            (req, res) ->
                toHttp(
                    res.getStatusCode().value(), res.getBody().readAllBytes(), res.getHeaders()));
  }

  private Http toHttp(int status, byte[] bytes, HttpHeaders headers) {
    JsonNode node = bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes);
    return new Http(status, node, headers);
  }
}
