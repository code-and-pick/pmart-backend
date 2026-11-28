package com.pmart.customerassistant;

import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.customerassistant.infrastructure.embedding.DescribedEmbeddingModel;
import com.pmart.customerassistant.infrastructure.embedding.EmbeddingSpec;
import com.pmart.customerassistant.infrastructure.embedding.LocalHashEmbeddingModel;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeIngestor;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeManifest;
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
 * 흐름 통합 시험 공통: pgvector + 실제 앱(HTTP) + 시험 JWT. 모델 없음(provider=local). order-service 는 WireMock. 픽스처
 * 문서(fixture/)를 임시 저장소로 복사해 색인한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.assistant.customer.order-service-base-url=${order.url}",
      "pmart.assistant.product-search.base-url=${search.url}",
      // CA-2: 목록 정본은 설정 저장소(PC 1) — 시험 사본을 정본과 같게 두려고 시험 단어는 여기서만 준다
      "pmart.assistant.customer.blocked-words=시험막기말"
    })
@Import({TestJwtConfiguration.class, AssistantITBase.TestBeans.class})
@org.wiremock.spring.EnableWireMock({
  @org.wiremock.spring.ConfigureWireMock(name = "order", baseUrlProperties = "order.url"),
  @org.wiremock.spring.ConfigureWireMock(name = "search", baseUrlProperties = "search.url")
})
abstract class AssistantITBase extends PgvectorContainerSupport {

  static final List<String> FIXTURE_FILES =
      List.of(
          "docs/business/policy.md",
          "docs/adr/0001-service-token.md",
          "docs/adr/0002-no-service-token.md",
          "docs/adr/0003-category.md",
          "docs/secret/hq.md");

  /** 시험이 켜고 끄는 임베딩: 장애 흉내(503), 첫 호출 때 부수효과(색인 중 파일 변경), 차원 위반. */
  static class ControllableEmbedding implements DescribedEmbeddingModel {
    final DescribedEmbeddingModel delegate = new LocalHashEmbeddingModel(384);
    final AtomicBoolean failing = new AtomicBoolean();
    final AtomicBoolean wrongDimension = new AtomicBoolean();
    final AtomicReference<@Nullable Runnable> onNextEmbed = new AtomicReference<>();

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

    /** 승인 회신(ADR-0094) — Kafka 대신 기록만. */
    @Bean
    @Primary
    RecordingReceipts recordingReceipts() {
      return new RecordingReceipts();
    }
  }

  /** 보낸 승인 회신(본문). */
  static class RecordingReceipts
      implements com.pmart.customerassistant.application.KnowledgeApprovals.Receipts {
    final java.util.List<java.util.Map<String, Object>> sent =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public String sourceSystem() {
      return "customer-assistant-service";
    }

    @Override
    public void send(String approvalId, java.util.Map<String, Object> payload) {
      sent.add(payload);
    }
  }

  @org.wiremock.spring.InjectWireMock("order")
  com.github.tomakehurst.wiremock.WireMockServer order;

  /** search-service 상품 검색(ADR-0083). 스텁이 없으면 404 → 상품 답 없이 안내 문서로 답한다. */
  @org.wiremock.spring.InjectWireMock("search")
  com.github.tomakehurst.wiremock.WireMockServer search;

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired KnowledgeIngestor ingestor;
  @Autowired ControllableEmbedding embedding;

  final JsonMapper json = JsonMapper.builder().build();
  Path repo;

  @BeforeEach
  void resetState() throws IOException {
    jdbc.update("DELETE FROM customer_answer");
    jdbc.update("DELETE FROM index_snapshot");
    embedding.failing.set(false);
    embedding.wrongDimension.set(false);
    embedding.onNextEmbed.set(null);
    order.resetAll();
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

  static String customer(String id) {
    return TestJwt.customer(id);
  }

  Http ask(String token, String key, String question) {
    return ask(token, key, question, null, "customer-app");
  }

  Http ask(String token, String key, String question, @Nullable String orderId, String source) {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("question", question);
    if (orderId != null) {
      body.put("orderId", orderId);
    }
    return RestClient.builder()
        .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
        .build()
        .post()
        .uri("http://localhost:" + port + "/api/v1/customer-assistant/questions")
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
    return get(token, "/api/v1/customer-assistant/questions?requestId=" + key, source);
  }

  Http getAnswer(String token, String answerId) {
    return get(token, "/api/v1/customer-assistant/questions/" + answerId, "customer-app");
  }

  Http recentOrders(String token) {
    return get(token, "/api/v1/customer-assistant/recent-orders", "customer-app");
  }

  Http get(String token, String path, String source) {
    return RestClient.builder()
        .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
        .build()
        .get()
        .uri("http://localhost:" + port + path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("X-Source-System", source)
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
