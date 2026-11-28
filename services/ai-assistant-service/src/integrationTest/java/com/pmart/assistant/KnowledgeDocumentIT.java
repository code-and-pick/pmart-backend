package com.pmart.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.application.KnowledgeApprovals;
import com.pmart.assistant.application.KnowledgeIndexing;
import com.pmart.assistant.domain.Authority;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Doc;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Kind;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.test.TestJwt;
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
 * 운영자 RAG 문서 관리(ADR-0092)와 상담 도우미(ADR-0093): 저장소에서 가져오기 → DB 로 색인 → 초안 → 승인(ApprovalDecided 를 시험이
 * 직접 넣는다 — Kafka 대신) → 새 판·회신 → 재색인 → 도우미가 새 문서로 답한다. 기준 판·본문 해시·읽기 전용·역할 검사.
 */
class KnowledgeDocumentIT extends AssistantITBase {

  static final String BO = "docs/rag/rag-docs/backoffice/05-customer-center-cases.md";
  static final String OC = "docs/rag/rag-docs/order_claim/03-cancel.md";
  static final String CA = "docs/rag/rag-docs/customer_app/01-order-guide.md";

  static final String ADMIN = TestJwt.operator("admin-1", List.of("OPERATOR_ADMIN"), List.of());
  static final String ADMIN2 = TestJwt.operator("admin-2", List.of("OPERATOR_ADMIN"), List.of());
  static final String CS = TestJwt.operator("cs-1", List.of("CS_AGENT"), List.of());
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
    docsRepo = Files.createTempDirectory("rag-docs");
    write(
        BO,
        """
        # PMart 고객센터·접수 건 처리 정책

        ## 제15조 (상담 본인 확인과 주문 확인)
        1. 상담원은 주소·연락처를 먼저 말하지 않습니다. 고객이 말한 내용을 화면의 정보와 맞춰 봅니다.
        2. 문의 종류별 본인 확인 질문: 주문 문의는 주문한 상품과 주문자 본인인지 묻습니다.
        """);
    write(
        OC,
        """
        # PMart 주문 취소 정책

        ## 제2조 (취소 가능 시점)
        1. 고객센터 상담원은 상품준비 전 주문만 고객 요청으로 대리 취소합니다. 주문 전체만 취소합니다.
        """);
    write(CA, "# 주문 안내\n\n## 주문하기\n1. 앱에서 주문합니다.\n");
    manifest =
        new KnowledgeManifest(
            1,
            "operator",
            List.of(doc(BO, "backoffice"), doc(OC, "order_claim"), doc(CA, "customer_app")),
            List.of(),
            "test");
  }

  static Doc doc(String path, String domain) {
    return new Doc(path, domain, Kind.REFERENCE, Authority.CONFIRMED, 4, null, "operator");
  }

  void write(String path, String content) throws IOException {
    Path f = docsRepo.resolve(path);
    Files.createDirectories(f.getParent());
    Files.writeString(f, content, StandardCharsets.UTF_8);
  }

  @Test
  void importIndexDraftApproveReindexAndGuide() {
    // 가져오기는 없는 문서만 — 두 번째는 0
    assertThat(indexing.importFromRepo(docsRepo, manifest)).isEqualTo(3);
    assertThat(indexing.importFromRepo(docsRepo, manifest)).isZero();
    var first = indexing.indexFromDb();
    assertThat(first.fileCount()).isEqualTo(3);
    assertThat(jdbc.queryForObject(
            "SELECT source_revision FROM index_snapshot WHERE snapshot_id = ?", String.class, first.snapshotId()))
        .isEqualTo("db:import");

    var list = get(ADMIN, "/api/v1/assistant/knowledge-documents");
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().path("items")).hasSize(3);
    assertThat(item(list.body(), BO).path("indexState").asString()).isEqualTo("INDEXED");
    assertThat(item(list.body(), CA).path("readOnly").asBoolean()).isTrue();
    // 보기는 운영자 누구나
    assertThat(get(MD, "/api/v1/assistant/knowledge-documents").status()).isEqualTo(200);

    // 초안: 편집 역할만, customer_app 은 막힘, 금지 표현·기준 판
    String newBody =
        """
        # PMart 고객센터·접수 건 처리 정책

        ## 제15조 (상담 본인 확인과 주문 확인)
        1. 상담원은 주소·연락처를 먼저 말하지 않습니다.
        2. 문의 종류별 본인 확인 질문: 주문 문의는 주문한 상품과 주문자 본인인지, 연락처가 조회되지 않으면 주문 때 연락처를 묻습니다.
        3. 해당 주문이 맞는지 주문 금액과 시각으로 다시 확인합니다.
        """;
    assertThat(createDraft(MD, BO, "UPSERT", 1, newBody).status()).isEqualTo(403);
    assertThat(createDraft(ADMIN, CA, "UPSERT", 1, "# 주문 안내\n").status()).isEqualTo(400);
    assertThat(createDraft(ADMIN, BO, "UPSERT", 1, "# 제목\n[설계안] 아직").status()).isEqualTo(400);
    assertThat(createDraft(ADMIN, BO, "UPSERT", 7, newBody).status()).isEqualTo(409);

    String key = UUID.randomUUID().toString();
    var made = createDraft(ADMIN, BO, "UPSERT", 1, newBody, key);
    assertThat(made.status()).isEqualTo(201);
    String draftId = made.text("draftId");
    JsonNode request = made.body().path("approvalRequest");
    assertThat(request.path("type").asString()).isEqualTo("RAG_DOCUMENT");
    assertThat(request.path("targetRef").asString()).isEqualTo(draftId);
    assertThat(request.path("targetExpectedVersion").asInt()).isEqualTo(1);
    // 같은 키는 같은 초안
    assertThat(createDraft(ADMIN, BO, "UPSERT", 1, newBody, key).text("draftId")).isEqualTo(draftId);
    // 승인자(다른 관리자)도 초안과 지금 판을 본다
    var seen = get(ADMIN2, "/api/v1/assistant/knowledge-drafts/" + draftId);
    assertThat(seen.status()).isEqualTo(200);
    assertThat(seen.body().path("current").path("version").asInt()).isEqualTo(1);

    // 승인 → 새 판·초안 닫힘·회신 APPLIED·재색인 요청
    approve("APR-1", request);
    var detail = get(ADMIN, "/api/v1/assistant/knowledge-documents/detail?documentId=" + BO);
    assertThat(detail.body().path("document").path("version").asInt()).isEqualTo(2);
    assertThat(detail.body().path("document").path("updatedBy").asString()).isEqualTo("admin-1");
    assertThat(detail.body().path("content").asString()).contains("주문 금액과 시각");
    assertThat(detail.body().path("versions")).hasSize(2);
    assertThat(get(ADMIN, "/api/v1/assistant/knowledge-documents/detail?documentId=" + BO + "&version=1")
            .body().path("content").asString())
        .doesNotContain("주문 금액과 시각");
    assertThat(get(ADMIN, "/api/v1/assistant/knowledge-drafts/" + draftId).text("state")).isEqualTo("APPLIED");
    assertThat(receipts.sent).hasSize(1);
    assertThat(receipts.sent.get(0)).containsEntry("result", "APPLIED").containsEntry("domainRef", BO + "@v2");
    assertThat(item(get(ADMIN, "/api/v1/assistant/knowledge-documents").body(), BO).path("indexState").asString())
        .isEqualTo("PENDING");

    // 같은 승인을 다시 받으면 판은 그대로, 같은 회신만 다시
    approve("APR-1", request);
    assertThat(jdbc.queryForObject("SELECT version FROM knowledge_document WHERE document_id = ?", Integer.class, BO))
        .isEqualTo(2);
    assertThat(receipts.sent).hasSize(2);
    assertThat(receipts.sent.get(1)).containsEntry("result", "APPLIED");

    // 재색인(주기 작업과 같은 길) → INDEXED, 출처 = 마지막 반영 승인
    assertThat(indexing.runRequestedReindex()).isTrue();
    assertThat(indexing.runRequestedReindex()).isFalse(); // 요청이 없으면 하지 않는다
    assertThat(item(get(ADMIN, "/api/v1/assistant/knowledge-documents").body(), BO).path("indexState").asString())
        .isEqualTo("INDEXED");
    assertThat(jdbc.queryForObject(
            "SELECT source_revision FROM index_snapshot WHERE state = 'ACTIVE'", String.class))
        .isEqualTo("db:APR-1");

    // 상담 도우미: 새 판의 본인 확인 절 + 문의 종류 근거
    var guide = get(CS, "/api/v1/assistant/consult-guides?category=ORDER&memberFound=false&orderStatus=CONFIRMED");
    assertThat(guide.status()).isEqualTo(200);
    assertThat(guide.body().path("identity").path("path").asString()).isEqualTo(BO);
    assertThat(guide.body().path("identity").path("excerpt").asString()).contains("주문 금액과 시각");
    List<String> policyPaths = new java.util.ArrayList<>();
    guide.body().path("policies").forEach(c -> policyPaths.add(c.path("path").asString()));
    assertThat(policyPaths).contains(OC);
    assertThat(guide.body().path("memberFound").asBoolean(true)).isFalse();
    assertThat(get(MD, "/api/v1/assistant/consult-guides?category=ORDER").status()).isEqualTo(403);
    assertThat(get(CS, "/api/v1/assistant/consult-guides?category=ORDER&orderStatus=bad-1").status())
        .isEqualTo(400);

    // 반영 기록
    var apps = get(ADMIN, "/api/v1/assistant/knowledge-applications");
    assertThat(apps.body().path("items").get(0).path("result").asString()).isEqualTo("APPLIED");
  }

  @Test
  void staleVersionChangedDraftAndRetire() {
    indexing.importFromRepo(docsRepo, manifest);
    String body1 = "# PMart 주문 취소 정책\n\n## 제2조\n1. 첫 번째 고치기.\n";
    String body2 = "# PMart 주문 취소 정책\n\n## 제2조\n1. 두 번째 고치기.\n";
    var a = createDraft(ADMIN, OC, "UPSERT", 1, body1);
    var b = createDraft(ADMIN, OC, "UPSERT", 1, body2);
    approve("APR-A", a.body().path("approvalRequest"));
    // b 는 판 1 기준 — 지금 판 2 → 반영 안 함
    approve("APR-B", b.body().path("approvalRequest"));
    assertThat(receipts.sent.get(1)).containsEntry("result", "REJECTED").containsEntry("reason", "STALE_VERSION");
    assertThat(get(ADMIN, "/api/v1/assistant/knowledge-drafts/" + b.text("draftId")).text("resultReason"))
        .isEqualTo("STALE_VERSION");

    // 승인 요청 뒤 초안을 고치면 본문 해시가 달라 반영 안 함
    var c = createDraft(ADMIN, OC, "UPSERT", 2, body2);
    JsonNode oldRequest = c.body().path("approvalRequest");
    var changed = put(ADMIN, "/api/v1/assistant/knowledge-drafts/" + c.text("draftId"), body2 + "\n2. 더.\n");
    assertThat(changed.status()).isEqualTo(200);
    assertThat(put(ADMIN2, "/api/v1/assistant/knowledge-drafts/" + c.text("draftId"), body2).status())
        .isEqualTo(404); // 남의 초안
    approve("APR-C", oldRequest);
    assertThat(receipts.sent.get(2)).containsEntry("reason", "DRAFT_CHANGED");

    // 내리기 → 색인에서 빠진다
    var r = createDraft(ADMIN, OC, "RETIRE", 2, null);
    approve("APR-R", r.body().path("approvalRequest"));
    assertThat(receipts.sent.get(3)).containsEntry("result", "APPLIED");
    indexing.runRequestedReindex();
    String active =
        jdbc.queryForObject("SELECT snapshot_id FROM index_snapshot WHERE state = 'ACTIVE'", String.class);
    assertThat(jdbc.queryForList(
            "SELECT path FROM index_source_file WHERE snapshot_id = ?", String.class, active))
        .containsExactlyInAnyOrder(BO, CA);
    assertThat(get(ADMIN, "/api/v1/assistant/knowledge-documents").body().path("items")).hasSize(2);
    assertThat(get(ADMIN, "/api/v1/assistant/knowledge-documents?includeRetired=true").body().path("items"))
        .hasSize(3);

    // 반려·다른 종류·다른 서비스 결정은 아무것도 하지 않는다
    int before = receipts.sent.size();
    decide("APR-X", c.body().path("approvalRequest"), "REJECTED", "RAG_DOCUMENT", "ai-assistant-service");
    decide("APR-Y", c.body().path("approvalRequest"), "APPROVED", "SEARCH_SYNONYM", "ai-assistant-service");
    decide("APR-Z", c.body().path("approvalRequest"), "APPROVED", "RAG_DOCUMENT", "search-service");
    assertThat(receipts.sent).hasSize(before);
  }

  // ---- 승인 흉내(approval-service 가 낼 ApprovalDecided) ----

  void approve(String approvalId, JsonNode request) {
    decide(approvalId, request, "APPROVED", "RAG_DOCUMENT", "ai-assistant-service");
  }

  void decide(String approvalId, JsonNode request, String decision, String type, String targetService) {
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
    payload.put("type", type);
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

  // ---- HTTP ----

  static JsonNode item(JsonNode list, String documentId) {
    for (JsonNode i : list.path("items")) {
      if (documentId.equals(i.path("documentId").asString())) {
        return i;
      }
    }
    throw new AssertionError("문서 없음: " + documentId);
  }

  Http createDraft(String token, String documentId, String op, int base, @Nullable String content) {
    return createDraft(token, documentId, op, base, content, UUID.randomUUID().toString());
  }

  Http createDraft(
      String token, String documentId, String op, int base, @Nullable String content, String key) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("documentId", documentId);
    body.put("op", op);
    body.put("baseVersion", base);
    body.put("content", content);
    return send("POST", token, "/api/v1/assistant/knowledge-drafts", body, key);
  }

  Http put(String token, String path, String content) {
    return send("PUT", token, path, Map.of("content", content), UUID.randomUUID().toString());
  }

  Http send(String method, String token, String path, Object body, String key) {
    return RestClient.create()
        .method(org.springframework.http.HttpMethod.valueOf(method))
        .uri("http://localhost:" + port + path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("Idempotency-Key", key)
        .header("X-Source-System", "backoffice-web")
        .contentType(MediaType.APPLICATION_JSON)
        .body(json.writeValueAsString(body))
        .exchange((req, res) -> http(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  Http get(String token, String path) {
    return RestClient.create()
        .get()
        .uri("http://localhost:" + port + path)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header("X-Source-System", "backoffice-web")
        .exchange((req, res) -> http(res.getStatusCode().value(), res.getBody().readAllBytes()));
  }

  Http http(int status, byte[] bytes) {
    return new Http(status, bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes), new HttpHeaders());
  }
}
