package com.pmart.customerassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.test.TestJwt;
import com.pmart.customerassistant.application.KnowledgeApprovals;
import com.pmart.customerassistant.application.KnowledgeIndexing;
import com.pmart.customerassistant.domain.Authority;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeManifest;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeManifest.Doc;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeManifest.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 고객 RAG 문서 관리(ADR-0094): 저장소에서 가져오기(customer_app + FAQ) → DB 로 색인(FAQ 목록도 같은 판) → 직원 초안 → 승인(시험이
 * ApprovalDecided 를 직접 넣는다) → 새 판·회신 → 재색인 → FAQ 목록이 새 판. 고객 토큰은 문서 관리 403, 내부 API 는 같이 쓰는 문서만.
 */
class KnowledgeDocumentIT extends AssistantITBase {

  static final String APP = "docs/rag/rag-docs/customer_app/08-order-cancel.md";
  static final String FAQ = "docs/business/customer-faq/02-cancel.md";

  static final String ADMIN = TestJwt.operator("admin-1", List.of("OPERATOR_ADMIN"), List.of());
  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());

  @Autowired KnowledgeIndexing indexing;
  @Autowired KnowledgeApprovals approvals;
  @Autowired EventEnvelopeCodec codec;
  @Autowired RecordingReceipts receipts;

  Path docsRepo;
  KnowledgeManifest manifest;

  @BeforeEach
  void knowledgeState() throws IOException {
    jdbc.update("DELETE FROM knowledge_application");
    jdbc.update("DELETE FROM knowledge_draft");
    jdbc.update("DELETE FROM knowledge_document_version");
    jdbc.update("DELETE FROM knowledge_document");
    jdbc.update(
        "UPDATE knowledge_reindex SET requested_at = NULL, started_at = NULL, finished_at = NULL,"
            + " last_result = NULL, last_snapshot_id = NULL, last_error = NULL");
    receipts.sent.clear();
    docsRepo = Files.createTempDirectory("customer-docs");
    write(
        APP,
        """
        # PMart 주문 취소 안내

        ## 제2조 (취소할 수 있는 시점)
        1. 상품 준비가 시작되기 전까지 앱에서 주문을 취소할 수 있습니다.
        """);
    write(
        FAQ,
        """
        # 주문 취소

        ## 주문을 취소하고 싶어요 [확정 정책]

        상품 준비 전에는 앱 주문 내역에서 취소할 수 있어요.
        """);
    manifest =
        new KnowledgeManifest(
            1,
            "customer",
            List.of(doc(APP, "customer-app"), doc(FAQ, "customer-faq")),
            List.of(),
            "test");
  }

  static Doc doc(String path, String domain) {
    return new Doc(path, domain, Kind.REFERENCE, Authority.CONFIRMED, 1, null, "customer");
  }

  void write(String path, String content) throws IOException {
    Path f = docsRepo.resolve(path);
    Files.createDirectories(f.getParent());
    Files.writeString(f, content, StandardCharsets.UTF_8);
  }

  @Test
  void importIndexEditFaqApproveAndShare() {
    assertThat(indexing.importFromRepo(docsRepo, manifest)).isEqualTo(2);
    assertThat(indexing.importFromRepo(docsRepo, manifest)).isZero();
    indexing.indexFromDb();
    // FAQ 목록이 DB 판으로 채워진다
    var faqs = get(customer("CU-k-1"), "/api/v1/customer-assistant/faqs", "customer-app");
    assertThat(faqs.status()).isEqualTo(200);
    assertThat(faqs.body().path("items").get(0).path("question").asString())
        .contains("주문을 취소하고 싶어요");

    // 문서 관리는 직원만 — 고객 토큰 403, 보기는 운영자 누구나
    assertThat(
            get(
                    customer("CU-k-1"),
                    "/api/v1/customer-assistant/knowledge-documents",
                    "customer-app")
                .status())
        .isEqualTo(403);
    var list = get(MD, "/api/v1/customer-assistant/knowledge-documents", "backoffice-web");
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().path("items")).hasSize(2);
    assertThat(list.body().path("items").get(0).path("readOnly").asBoolean()).isFalse();

    // FAQ 는 지위 태그 [확정 정책] 를 써도 된다(금지 표현 검사는 customer_app 만), customer_app 은 막힌다
    String newFaq =
        "# 주문 취소\n\n## 주문을 취소하고 싶어요 [확정 정책]\n\n상품 준비 전에는 앱 주문 내역에서 바로 취소할 수 있어요. 고객센터에 말씀하셔도 돼요.\n";
    assertThat(createDraft(MD, FAQ, "UPSERT", 1, newFaq).status()).isEqualTo(403);
    assertThat(createDraft(ADMIN, APP, "UPSERT", 1, "# 안내\n[설계안] 아직").status()).isEqualTo(400);
    var made = createDraft(ADMIN, FAQ, "UPSERT", 1, newFaq);
    assertThat(made.status()).isEqualTo(201);
    JsonNode request = made.body().path("approvalRequest");
    assertThat(request.path("targetService").asString()).isEqualTo("customer-assistant-service");

    approve("APR-C1", request);
    assertThat(receipts.sent).hasSize(1);
    assertThat(receipts.sent.get(0))
        .containsEntry("result", "APPLIED")
        .containsEntry("domainRef", FAQ + "@v2");
    assertThat(indexing.runRequestedReindex()).isTrue();
    var after = get(customer("CU-k-1"), "/api/v1/customer-assistant/faqs", "customer-app");
    assertThat(after.body().path("items").get(0).path("answer").asString()).contains("고객센터에 말씀하셔도");

    // 운영자 RAG 가 받아 가는 같이 쓰는 문서 — customer_app 만(FAQ 는 아님)
    JsonNode shared =
        json.readTree(
            RestClient.create()
                .get()
                .uri("http://localhost:" + port + "/api/v1/internal/knowledge-documents/shared")
                .header("X-Source-System", "ai-assistant-service") // 토큰 없음(서비스 사이, ADR-0026)
                .retrieve()
                .body(String.class));
    assertThat(shared.path("items")).hasSize(1);
    assertThat(shared.path("items").get(0).path("documentId").asString()).isEqualTo(APP);
    assertThat(shared.path("items").get(0).path("contentSha256").asString()).hasSize(64);
    // 사용자 토큰으로는 내부 경로를 못 쓴다
    assertThat(
            get(ADMIN, "/api/v1/internal/knowledge-documents/shared", "ai-assistant-service")
                .status())
        .isEqualTo(403);

    // 다른 정본 서비스(운영자 RAG)의 승인은 반영하지 않는다
    int before = receipts.sent.size();
    decide("APR-C2", request, "APPROVED", "ai-assistant-service");
    assertThat(receipts.sent).hasSize(before);
  }

  // ---- 승인 흉내 ----

  void approve(String approvalId, JsonNode request) {
    decide(approvalId, request, "APPROVED", "customer-assistant-service");
  }

  void decide(String approvalId, JsonNode request, String decision, String targetService) {
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetType", "KnowledgeDocument");
    target.put("targetId", request.path("targetRef").asString());
    target.put("targetService", targetService);
    target.put("expectedVersion", request.path("targetExpectedVersion").asInt());
    JsonNode line = request.path("lines").get(0);
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("lineId", line.path("lineKey").asString());
    l.put("decision", "APPROVED");
    l.put("attributes", json.convertValue(line.path("attributes"), Map.class));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("type", "RAG_DOCUMENT");
    payload.put("decision", decision);
    payload.put("approverId", "admin-2");
    payload.put("targetRef", target);
    payload.put("lines", List.of(l));
    EventEnvelope e =
        codec.create(
            "approval-service",
            "ApprovalDecided",
            1,
            "Approval",
            approvalId,
            2,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, "admin-2"),
            EventEnvelope.EventSource.REAL,
            payload);
    approvals.onApprovalDecided(e);
  }

  Http createDraft(String token, String documentId, String op, int base, @Nullable String content) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("documentId", documentId);
    body.put("op", op);
    body.put("baseVersion", base);
    body.put("content", content);
    return RestClient.create()
        .post()
        .uri("http://localhost:" + port + "/api/v1/customer-assistant/knowledge-drafts")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .header("X-Source-System", "backoffice-web")
        .contentType(MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(body))
        .exchange(
            (req, res) -> {
              byte[] b = res.getBody().readAllBytes();
              return new Http(
                  res.getStatusCode().value(),
                  b.length == 0 ? json.createObjectNode() : json.readTree(b),
                  new HttpHeaders());
            });
  }
}
