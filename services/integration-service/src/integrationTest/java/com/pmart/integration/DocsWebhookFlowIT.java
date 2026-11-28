package com.pmart.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.pmart.integration.domain.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문서 저장소 웹훅 흐름(ADR-0095): 서명한 push 웹훅 → 바뀐 .md 파일을 GitHub(흉내)에서 그 커밋 판으로 읽어 추출 → 청킹 서비스(흉내)로 보냄.
 * README·다른 확장자는 건너뜀, 삭제는 본문 없이, 서명이 틀리면 401, ping·delete 이벤트는 파일 처리 없음.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.integration.docs.webhook-secret=it-secret",
      "pmart.integration.docs.github-token=it-token",
      "pmart.integration.docs.github-api-url=${github.url}",
      "pmart.integration.docs.chunking-url=${chunking.url}/api/v1/internal/documents",
      "pmart.integration.docs.retries=1"
    })
@EnableWireMock({
  @ConfigureWireMock(name = "github", baseUrlProperties = "github.url"),
  @ConfigureWireMock(name = "chunking", baseUrlProperties = "chunking.url")
})
class DocsWebhookFlowIT {

  static final String SECRET = "it-secret";
  static final String REPO = "code-and-pick/pmart-all-docs";

  @LocalServerPort int port;

  @InjectWireMock("github")
  WireMockServer github;

  @InjectWireMock("chunking")
  WireMockServer chunking;

  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void stubs() {
    chunking.stubFor(
        post(urlPathEqualTo("/api/v1/internal/documents")).willReturn(aResponse().withStatus(202)));
  }

  ResponseEntity<String> send(String event, String body, String signature) {
    return RestClient.create()
        .post()
        .uri("http://localhost:" + port + "/api/v1/internal/docs-webhook")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-GitHub-Event", event)
        .header("X-GitHub-Delivery", UUID.randomUUID().toString())
        .header("X-Hub-Signature-256", signature)
        .body(body.getBytes(StandardCharsets.UTF_8))
        .retrieve()
        .onStatus(s -> true, (rq, rs) -> {})
        .toEntity(String.class);
  }

  ResponseEntity<String> signed(String event, String body) {
    return send(event, body, WebhookSignature.sign(SECRET, body.getBytes(StandardCharsets.UTF_8)));
  }

  List<JsonNode> received(int atLeast) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(15));
    List<LoggedRequest> got = List.of();
    while (Instant.now().isBefore(until)) {
      got = chunking.findAll(postRequestedFor(urlPathEqualTo("/api/v1/internal/documents")));
      if (got.size() >= atLeast) {
        break;
      }
      Thread.sleep(100);
    }
    return got.stream().map(r -> json.readTree(r.getBodyAsString())).toList();
  }

  @Test
  void pushExtractsChangedMarkdownAndSendsToChunking() throws Exception {
    String commit = "c0ffee1234567890";
    github.stubFor(
        get(urlPathEqualTo("/repos/" + REPO + "/contents/operator/mfc/09-new.md"))
            .withQueryParam("ref", equalTo(commit))
            .withHeader("Authorization", equalTo("Bearer it-token"))
            .willReturn(aResponse().withBody("# 새 정책\r\n\r\n## 제1조 (적용)\r\n내용\r\n")));
    String push =
        """
        {"ref":"refs/heads/main","before":"0000","after":"%s","deleted":false,
         "repository":{"full_name":"%s"},
         "commits":[{"id":"%s","added":["operator/mfc/09-new.md","operator/mfc/README.md","img/a.png"],
                     "modified":[],"removed":["customer/faq/02-cancel.md"]}]}
        """
            .formatted(commit, REPO, commit);

    ResponseEntity<String> r = signed("push", push);
    assertThat(r.getStatusCode().value()).isEqualTo(202);
    assertThat(r.getBody()).contains("ACCEPTED");

    List<JsonNode> docs = received(2);
    assertThat(docs).as(docs.toString()).hasSize(2);
    assertThat(docs.toString()).as(docs.toString()).contains("ADDED");
    JsonNode added =
        docs.stream()
            .filter(d -> d.path("status").asString().equals("ADDED"))
            .findFirst()
            .orElseThrow();
    assertThat(added.path("path").asString()).isEqualTo("operator/mfc/09-new.md");
    assertThat(added.path("repository").asString()).isEqualTo(REPO);
    assertThat(added.path("commit").asString()).isEqualTo(commit);
    assertThat(added.path("title").asString()).isEqualTo("새 정책");
    assertThat(added.path("content").asString()).isEqualTo("# 새 정책\n\n## 제1조 (적용)\n내용\n");
    assertThat(added.path("contentSha256").asString()).hasSize(64);
    JsonNode removed =
        docs.stream()
            .filter(d -> d.path("status").asString().equals("REMOVED"))
            .findFirst()
            .orElseThrow();
    assertThat(removed.path("path").asString()).isEqualTo("customer/faq/02-cancel.md");
    assertThat(removed.path("content").isNull() || removed.path("content").isMissingNode())
        .isTrue();
  }

  @Test
  void badSignatureIsRejectedAndOtherEventsCarryNoFiles() {
    String body = "{\"repository\":{\"full_name\":\"" + REPO + "\"}}";
    assertThat(send("push", body, "sha256=00").getStatusCode().value()).isEqualTo(401);
    assertThat(signed("ping", body).getBody()).contains("PONG");
    var del =
        signed(
            "delete",
            "{\"ref\":\"feature\",\"ref_type\":\"branch\",\"repository\":{\"full_name\":\""
                + REPO
                + "\"}}");
    assertThat(del.getStatusCode().value()).isEqualTo(202);
    assertThat(del.getBody()).contains("REF_DELETED");
    var other =
        signed(
            "push",
            "{\"ref\":\"refs/heads/main\",\"repository\":{\"full_name\":\"someone/else\"},\"commits\":[]}");
    assertThat(other.getBody()).contains("IGNORED_REPOSITORY");
  }
}
