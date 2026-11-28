package com.pmart.ragquery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ES 결과를 믿기 어려울 때 하이브리드로 넘어가는가(ADR-0099). 실제 ES(8.18+nori, chunking-service 색인 정의) +
 * embedding-service 흉내.
 *
 * <ul>
 *   <li>ES 가 확실한 질문 → ES 에서 끝, 벡터 API 호출 0
 *   <li>ES 가 불확실한 질문(핵심어 비율 낮음) → 벡터 API 1번 → BM25 가 못 찾은 청크가 근거에 들어옴(HYBRID)
 *   <li>벡터 1위 코사인이 낮은 질문 → 답변 불가
 * </ul>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off", "pmart.rag-query.embedding-url=${vec.url}"})
@EnableWireMock(@ConfigureWireMock(name = "vec", baseUrlProperties = "vec.url"))
class HybridFallbackIT {

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

  static final String DOC = "operator/backoffice/01-staff-account-login.md";

  static {
    ES.start();
    try {
      byte[] def =
          Files.readAllBytes(
              Path.of("../chunking-service/src/main/resources/elasticsearch/rag-chunks-v2.json"));
      RestClient es = RestClient.create(esUrl());
      es.put()
          .uri("/rag-operator-v2")
          .contentType(MediaType.APPLICATION_JSON)
          .body(def)
          .retrieve()
          .toBodilessEntity();
      es.post()
          .uri("/_aliases")
          .contentType(MediaType.APPLICATION_JSON)
          .body(
              Map.of(
                  "actions",
                  List.of(
                      Map.of("add", Map.of("index", "rag-operator-v2", "alias", "rag-operator")))))
          .retrieve()
          .toBodilessEntity();
      put(
          "c-login",
          "PMart 직원 계정·로그인 정책 > 제6조 (로그인 유지)",
          "제6조 (로그인 유지)",
          "한 번 로그인하면 최대 12시간까지 이어서 쓸 수 있으며, 12시간이 지나면 다시 로그인해야 합니다. 업무 단말은 30분 동안 쓰지 않으면 다시 로그인해야 합니다.");
      put(
          "c-mail",
          "PMart 직원 계정·로그인 정책 > 제4조 (회사 메일)",
          "제4조 (회사 메일)",
          "회사 메일은 회사 안에서만 주고받으며 회사 밖 주소로는 보낼 수 없습니다. 메일함 비밀번호를 잊었으면 인사팀에 요청합니다.");
      put(
          "c-issue",
          "PMart 직원 계정·로그인 정책 > 제3조 (계정 발급)",
          "제3조 (계정 발급)",
          "직원 계정은 인사팀이 인사팀 백오피스에서 직원을 등록할 때 발급됩니다. 안내 메일의 링크는 3일 동안 쓸 수 있습니다.");
      put(
          "c-quit",
          "PMart 직원 계정·로그인 정책 > 제8조 (정지·퇴사)",
          "제8조 (정지·퇴사)",
          "직원이 정지되거나 퇴사하면 그 직원의 로그인은 바로 끊기고 새로 로그인할 수 없습니다.");
      es.post().uri("/rag-operator/_refresh").retrieve().toBodilessEntity();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static String esUrl() {
    return "http://" + ES.getHost() + ":" + ES.getMappedPort(9200);
  }

  static void put(String id, String section, String heading, String content) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("chunkId", id);
    s.put("documentId", DOC);
    s.put("repository", "code-and-pick/pmart-all-docs");
    s.put("commit", "c1");
    s.put("accessScope", "OPERATOR");
    s.put("documentType", "POLICY");
    s.put("title", "PMart 직원 계정·로그인 정책");
    s.put("heading", heading);
    s.put("headingLevel", 2);
    s.put("sectionPath", section);
    s.put("ordinal", 0);
    s.put("content", content);
    s.put("chunkSha256", id);
    s.put("contentSha256", "h");
    s.put("status", "ACTIVE");
    s.put("indexedAt", "2026-11-28T00:00:00Z");
    RestClient.create(esUrl())
        .put()
        .uri("/rag-operator/_doc/" + id)
        .contentType(MediaType.APPLICATION_JSON)
        .body(s)
        .retrieve()
        .toBodilessEntity();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("pmart.rag-query.elasticsearch-url", HybridFallbackIT::esUrl);
  }

  @LocalServerPort int port;

  @InjectWireMock("vec")
  WireMockServer vec;

  final JsonMapper json = JsonMapper.builder().build();

  static String vectorBody(String chunkId, String section, double cos) {
    return """
        {"model":"ollama:qwen3-embedding:0.6b","version":"t","embedMillis":12,
         "hits":[{"chunkId":"%s","documentId":"%s","sectionPath":"%s","cosine":%s},
                 {"chunkId":"c-mail","documentId":"%s","sectionPath":"메일","cosine":%s}]}
        """
        .formatted(chunkId, DOC, section, cos, DOC, cos - 0.1);
  }

  @BeforeEach
  void stubs() {
    vec.resetAll();
    // 바꿔 쓴 질문(퇴사자) — 벡터는 제8조를 1위로
    vec.stubFor(
        post(urlPathEqualTo("/api/v1/internal/vector-searches"))
            .withRequestBody(containing("그만둔"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(vectorBody("c-quit", "PMart 직원 계정·로그인 정책 > 제8조 (정지·퇴사)", 0.74))));
    // 근거 없는 질문 — 벡터도 멀다
    vec.stubFor(
        post(urlPathEqualTo("/api/v1/internal/vector-searches"))
            .withRequestBody(containing("야간"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(vectorBody("c-login", "PMart 직원 계정·로그인 정책 > 제6조 (로그인 유지)", 0.41))));
  }

  JsonNode ask(String q) {
    String r =
        RestClient.create()
            .post()
            .uri("http://localhost:" + port + "/api/v1/internal/rag-searches")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("X-Source-System", "it")
            .body(Map.of("scope", "OPERATOR", "question", q))
            .retrieve()
            .body(String.class);
    return json.readTree(r);
  }

  int vectorCalls() {
    return vec.findAll(postRequestedFor(urlPathEqualTo("/api/v1/internal/vector-searches"))).size();
  }

  @Test
  void confidentEsResultEndsAtEsWithoutVectorCall() {
    JsonNode r = ask("로그인 유지 시간은 최대 몇 시간이에요?");
    assertThat(r.path("exit").asString()).isEqualTo("ES");
    assertThat(r.path("decision").path("acceptAtEs").asBoolean()).isTrue();
    assertThat(r.path("decision").path("features").path("coverage").asDouble())
        .isGreaterThanOrEqualTo(0.7);
    assertThat(r.path("embeddingCalls").asInt()).isZero();
    assertThat(vectorCalls()).isZero();
    assertThat(r.path("evidence").get(0).path("chunkId").asString()).isEqualTo("c-login");
    assertThat(r.path("evidence").size()).isEqualTo(3);
  }

  @Test
  void unreliableEsResultFallsBackToHybridAndVectorRecoversEvidence() {
    // 문서 낱말과 다르게 바꿔 쓴 질문: BM25 는 퇴사 조항(제8조)을 못 찾고 핵심어 비율이 낮다
    JsonNode r = ask("회사를 그만둔 사람도 접속이 되나요?");
    assertThat(r.path("decision").path("acceptAtEs").asBoolean()).isFalse();
    assertThat(r.path("decision").path("reason").asString()).startsWith("coverage");
    assertThat(r.path("exit").asString()).isEqualTo("HYBRID");
    assertThat(vectorCalls()).isEqualTo(1);
    assertThat(r.path("embeddingCalls").asInt()).isEqualTo(1);
    assertThat(r.path("vectorTop1Cosine").asDouble()).isEqualTo(0.74);
    assertThat(r.path("evidence").toString()).contains("c-quit"); // 벡터가 되찾은 근거
  }

  @Test
  void lowVectorSimilarityEndsAsNoAnswer() {
    JsonNode r = ask("센터 야간 근무 수당은 얼마예요?");
    assertThat(r.path("decision").path("acceptAtEs").asBoolean()).isFalse();
    assertThat(vectorCalls()).isEqualTo(1);
    assertThat(r.path("exit").asString()).isEqualTo("NO_ANSWER");
    assertThat(r.path("evidence").size()).isZero();
  }
}
