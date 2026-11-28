package com.pmart.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 추출 내용 → 청킹 → ES(8.18 + nori, LAN es-search 와 같은 판): 색인·별칭 생성, 운영자·고객 색인 나눔(customer_app 은 둘 다),
 * 같은 판 다시 = UNCHANGED, 새 판 = 옛 청크 지움, 삭제 = RETIRED, nori 검색.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off"})
class DocumentIndexingIT {

  @Container
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

  @DynamicPropertySource
  static void es(DynamicPropertyRegistry r) {
    r.add("pmart.chunking.elasticsearch-url", DocumentIndexingIT::esUrl);
  }

  static String esUrl() {
    return "http://" + ES.getHost() + ":" + ES.getMappedPort(9200);
  }

  @LocalServerPort int port;
  final JsonMapper json = JsonMapper.builder().build();

  static final String V1 =
      """
      # PMart 오배송 처리 정책

      ## 제1조 (목적)
      이 정책은 오배송 처리 기준을 정합니다.

      ## 제2조 (접수)
      고객이 다른 상품을 받았다고 알리면 고객센터 상담원이 접수합니다.
      """;

  JsonNode send(String path, String status, String content, String hash) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("repository", "code-and-pick/pmart-all-docs");
    body.put("commit", "c" + hash);
    body.put("path", path);
    body.put("status", status);
    body.put("title", content == null ? null : "PMart 오배송 처리 정책");
    body.put("content", content);
    body.put("contentSha256", hash);
    body.put("deliveryId", "d-1");
    body.put("extractedAt", "2026-11-28T00:00:00Z");
    String r =
        RestClient.create()
            .post()
            .uri("http://localhost:" + port + "/api/v1/internal/documents")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("X-Source-System", "integration-service")
            .body(body)
            .retrieve()
            .body(String.class);
    return json.readTree(r);
  }

  JsonNode search(String index, Map<String, Object> query) {
    return json.readTree(
        RestClient.create()
            .post()
            .uri(esUrl() + "/" + index + "/_search")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("size", 50, "query", query))
            .retrieve()
            .body(String.class));
  }

  long count(String index, String documentId, String status) {
    return search(
            index,
            Map.of(
                "bool",
                Map.of(
                    "filter",
                    java.util.List.of(
                        Map.of("term", Map.of("documentId", documentId)),
                        Map.of("term", Map.of("status", status))))))
        .path("hits")
        .path("total")
        .path("value")
        .asLong();
  }

  @Test
  void documentLifecycleInEs() {
    String op = "operator/order_claim/07-misdelivery.md";
    JsonNode r1 = send(op, "ADDED", V1, "h1");
    assertThat(r1.path("result").asString()).isEqualTo("INDEXED");
    // 첫 제목 아래 본문이 없으면 그 제목은 청크가 아니다(리더) — 제1조·제2조 둘
    assertThat(r1.path("chunks").asInt()).isEqualTo(2);
    assertThat(count("rag-operator", op, "ACTIVE")).isEqualTo(2);
    assertThat(count("rag-customer", op, "ACTIVE")).isZero(); // 직원 문서는 고객 색인에 없다

    // 별칭 → -v1, nori 로 "접수합니다" 를 "접수" 로 찾음
    JsonNode hit =
        search("rag-operator", Map.of("match", Map.of("content", "고객센터 상담원")))
            .path("hits")
            .path("hits");
    assertThat(hit.get(0).path("_index").asString()).isEqualTo("rag-operator-v1");
    assertThat(hit.get(0).path("_source").path("sectionPath").asString())
        .isEqualTo("PMart 오배송 처리 정책 > 제2조 (접수)");

    // 같은 판 다시 → UNCHANGED
    assertThat(send(op, "MODIFIED", V1, "h1").path("result").asString()).isEqualTo("UNCHANGED");

    // 새 판 → 옛 판 청크는 지워짐
    String v2 = V1 + "\n## 제3조 (회수)\n다른 품목만 회수합니다.\n";
    assertThat(send(op, "MODIFIED", v2, "h2").path("chunks").asInt()).isEqualTo(3);
    assertThat(count("rag-operator", op, "ACTIVE")).isEqualTo(3);
    assertThat(
            search("rag-operator", Map.of("term", Map.of("contentSha256", "h1")))
                .path("hits")
                .path("total")
                .path("value")
                .asLong())
        .isZero();

    // 삭제 → RETIRED(지우지 않고 내림)
    JsonNode del = send(op, "REMOVED", null, null);
    assertThat(del.path("result").asString()).isEqualTo("RETIRED");
    assertThat(count("rag-operator", op, "ACTIVE")).isZero();
    assertThat(count("rag-operator", op, "RETIRED")).isEqualTo(3);
  }

  @Test
  void customerAppGoesToBothIndexesAndFaqOnlyToCustomer() {
    String app = "customer/customer_app/10-not-received-misdelivery.md";
    JsonNode r = send(app, "ADDED", V1, "app-1");
    assertThat(r.path("indexes").toString()).contains("rag-customer").contains("rag-operator");
    assertThat(count("rag-customer", app, "ACTIVE")).isEqualTo(2);
    assertThat(count("rag-operator", app, "ACTIVE")).isEqualTo(2);

    String faq = "customer/faq/04-not-received.md";
    send(faq, "ADDED", V1, "faq-1");
    assertThat(count("rag-customer", faq, "ACTIVE")).isEqualTo(2);
    assertThat(count("rag-operator", faq, "ACTIVE")).isZero();

    assertThat(send("README.md", "ADDED", V1, "x").path("result").asString()).isEqualTo("IGNORED");
  }
}
