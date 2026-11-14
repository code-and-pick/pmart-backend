package com.pmart.mfc.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 초기 보유 반입(D2): INITIAL_STOCK 승인(ApprovalDecided, Kafka) → 센터별 반입 기록 → mfc-inventory
 * ConfirmReceipt(서비스 토큰, receiptId 멱등 키) → APPLIED. inventory 일시 장애 재시도, inventory 거절, 승인 내용 오류, 대상
 * 아닌 승인 무시. mfc-inventory·auth 는 WireMock, PostgreSQL·Kafka 는 실제.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.security.service-token.token-uri=${pmart.inbound.mfc-inventory-base-url}/api/v1/internal/service-tokens",
      "pmart.inbound.post-interval=PT1S"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.inbound.mfc-inventory-base-url"))
class InitialStockLoadIT extends PostgresKafkaContainerSupport {

  static final String TOPIC = "platform.approval.v1";
  static final String RECEIPTS = "/api/v1/internal/receipts";
  static final String SERVICE_TOKEN = TestJwt.service("mfc-inbound-service");
  static final String OPERATOR =
      TestJwt.operator("ops-1", List.of("INVENTORY_APPROVER"), List.of());

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("inventory")
  WireMockServer inventory;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    inventory.resetAll();
    inventory.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/service-tokens"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "access_token",
                        SERVICE_TOKEN,
                        "token_type",
                        "Bearer",
                        "expires_in",
                        300))));
  }

  ResponseDefinitionBuilder json(int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }

  void inventoryAccepts(String mfcId, String commitId) {
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(mfcId)))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "status",
                        "APPLIED",
                        "requestId",
                        "r",
                        "resultId",
                        commitId,
                        "appliedQty",
                        1))));
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-web");
                })
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static String op(String... centers) {
    return TestJwt.operator("ops-1", List.of("INVENTORY_APPROVER"), List.of(centers));
  }

  /** 현장 수령(입고 예정 → PENDING). */
  Res receive(String mfcId, String receiptId, Map<String, Integer> lines) {
    List<Map<String, Object>> ls = new java.util.ArrayList<>();
    lines.forEach((sku, q) -> ls.add(Map.of("sku", sku, "receivedQty", q)));
    return post(
        op(mfcId),
        "/initial-stock-loads/" + receiptId + "/receipts",
        UUID.randomUUID().toString(),
        Map.of("toLocationCode", "RCV-DOCK", "lines", ls));
  }

  /** inventory 수령 결과(줄별 lotId). */
  void receiptResult(String receiptId, List<Map<String, Object>> lines) {
    inventory.stubFor(
        WireMock.get(urlPathEqualTo(RECEIPTS + "/" + receiptId))
            .willReturn(
                json(200, Map.of("receiptId", receiptId, "commitId", "CMT-X", "lines", lines))));
  }

  static Map<String, Object> resultLine(int no, String sku, String lot, int qty) {
    return Map.of(
        "lineNo",
        no,
        "sku",
        sku,
        "lotId",
        lot,
        "locationCode",
        "RCV-DOCK",
        "normalQty",
        qty,
        "quarantinedQty",
        0,
        "pendingQty",
        0);
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** ApprovalDecided 를 토픽에 흘린다. 같은 봉투 JSON 을 돌려준다(재전달 시험용). */
  String approve(String approvalId, String type, String decision, List<Map<String, Object>> attrs) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("type", type);
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType",
            "InitialStockLoad",
            "targetId",
            "INIT-1",
            "targetService",
            "mfc-inbound-service"));
    payload.put(
        "lines",
        attrs.stream()
            .map(
                a -> {
                  Map<String, Object> l = new LinkedHashMap<>();
                  l.put("lineId", a.get("sku"));
                  l.put("decision", "APPROVED");
                  l.put("attributes", a);
                  return l;
                })
            .toList());
    payload.put("approverId", "inv-approver-1");
    payload.put("dossierVersion", 1);
    String json =
        EventEnvelopeFixtures.envelope("ApprovalDecided")
            .from("approval-service")
            .aggregate("Approval", approvalId, 2)
            .payload(payload)
            .buildJson();
    kafka.send(TOPIC, approvalId, json).join();
    return json;
  }

  static Map<String, Object> line(String mfcId, String sku, Object qty) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("mfcId", mfcId);
    m.put("sku", sku);
    m.put("qty", qty);
    return m;
  }

  JsonNode awaitLoads(String approvalId, Predicate<JsonNode> done) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(60));
    JsonNode items =
        get(OPERATOR, "/initial-stock-loads?approvalId=" + approvalId).body().path("items");
    while (!done.test(items) && Instant.now().isBefore(until)) {
      Thread.sleep(300);
      items = get(OPERATOR, "/initial-stock-loads?approvalId=" + approvalId).body().path("items");
    }
    return items;
  }

  static boolean allIn(JsonNode items, int n, String state) {
    if (items.size() != n) {
      return false;
    }
    for (JsonNode i : items) {
      if (!state.equals(i.path("state").asString())) {
        return false;
      }
    }
    return true;
  }

  static String mfc() {
    return "MFC-IT-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void approvedInitialStockIsPostedToInventoryPerCenter() throws Exception {
    String m1 = mfc();
    String m2 = mfc();
    inventoryAccepts(m1, "CMT-1");
    inventoryAccepts(m2, "CMT-2");
    String approvalId = "AP-" + UUID.randomUUID();
    String event =
        approve(
            approvalId,
            "INITIAL_STOCK",
            "APPROVED",
            List.of(
                line(m1, "E-00000001", 10), line(m1, "B-00000002", 3), line(m2, "E-00000001", 7)));

    // 승인 = 입고 예정(EXPECTED) — 승인만으로 재고를 만들지 않는다
    JsonNode items = awaitLoads(approvalId, i -> allIn(i, 2, "EXPECTED"));
    assertThat(allIn(items, 2, "EXPECTED")).as(items.toString()).isTrue();
    JsonNode first =
        items.path(0).path("mfcId").asString().equals(m1) ? items.path(0) : items.path(1);
    JsonNode second = first == items.path(0) ? items.path(1) : items.path(0);
    String receiptId = first.path("receiptId").asString();
    inventory.verify(0, postRequestedFor(urlPathEqualTo(RECEIPTS)));

    // 현장 수령: 승인에 없는 SKU 400, 승인 수량 초과 400, 다른 센터 운영자 403
    assertThat(receive(m1, receiptId, Map.of("E-00000009", 1)).status()).isEqualTo(400);
    assertThat(receive(m1, receiptId, Map.of("E-00000001", 11)).status()).isEqualTo(400);
    List<Map<String, Object>> one = List.of(Map.of("sku", "E-00000001", "receivedQty", 1));
    assertThat(
            post(
                    op(m2),
                    "/initial-stock-loads/" + receiptId + "/receipts",
                    UUID.randomUUID().toString(),
                    Map.of("toLocationCode", "RCV-DOCK", "lines", one))
                .status())
        .isEqualTo(403);
    receiptResult(
        receiptId,
        List.of(resultLine(1, "B-00000002", "LOT-B", 3), resultLine(2, "E-00000001", "LOT-E", 8)));
    receiptResult(
        second.path("receiptId").asString(), List.of(resultLine(1, "E-00000001", "LOT-E2", 7)));
    Res received = receive(m1, receiptId, Map.of("E-00000001", 8, "B-00000002", 3));
    assertThat(received.status()).as(received.body().toString()).isEqualTo(202);
    assertThat(received.body().path("appliedQty").asInt()).isEqualTo(11);
    // 한 번만 수령(EXPECTED 아님 → 409)
    assertThat(receive(m1, receiptId, Map.of("E-00000001", 1)).status()).isEqualTo(409);
    assertThat(receive(m2, second.path("receiptId").asString(), Map.of("E-00000001", 7)).status())
        .isEqualTo(202);

    items = awaitLoads(approvalId, i -> allIn(i, 2, "APPLIED"));
    assertThat(allIn(items, 2, "APPLIED")).as(items.toString()).isTrue();
    first = items.path(0).path("mfcId").asString().equals(m1) ? items.path(0) : items.path(1);
    assertThat(first.path("inventoryCommitId").asString()).isEqualTo("CMT-1");
    assertThat(first.path("lines")).hasSize(2);
    assertThat(first.path("receivingLocation").asString()).isEqualTo("RCV-DOCK");
    assertThat(first.path("attempts").asInt()).isEqualTo(1);

    // 수령 줄마다 품질검사 작업(수령 LOT, 수령 위치)
    // 상품명·바코드는 mfc-inventory SKU 사본에서(0.8.0, BR-53 R8) — 사본이 없는 SKU 는 null·빈 배열
    inventory.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "sku",
                                "E-00000001",
                                "productName",
                                "서울우유 1L",
                                "barcodes",
                                List.of("08801115114154"),
                                "state",
                                "ACTIVE",
                                "productVersion",
                                1)),
                        "missing",
                        List.of("B-00000002")))));
    JsonNode tasks =
        get(op(m1), "/inbound-tasks?mfcId=" + m1 + "&taskType=INSPECT").body().path("items");
    assertThat(tasks).hasSize(2);
    assertThat(tasks.findValuesAsString("lotId")).containsExactlyInAnyOrder("LOT-B", "LOT-E");
    assertThat(get(op(m2), "/inbound-tasks?mfcId=" + m1).status()).isEqualTo(403);
    JsonNode taskE =
        "E-00000001".equals(tasks.path(0).path("sku").asString()) ? tasks.path(0) : tasks.path(1);
    JsonNode taskB = taskE == tasks.path(0) ? tasks.path(1) : tasks.path(0);
    assertThat(taskE.path("productName").asString()).isEqualTo("서울우유 1L");
    assertThat(taskE.path("barcodes").path(0).asString()).isEqualTo("08801115114154");
    assertThat(taskB.path("productName").isNull()).isTrue();
    assertThat(taskB.path("barcodes")).isEmpty();
    // 단건 조회(0.8.0) — 그 센터 운영자만
    String taskEId = taskE.path("taskId").asString();
    Res single = get(op(m1), "/inbound-tasks/" + taskEId);
    assertThat(single.status()).isEqualTo(200);
    assertThat(single.body().path("productName").asString()).isEqualTo("서울우유 1L");
    assertThat(single.body().path("lotId").asString()).isEqualTo("LOT-E");
    assertThat(get(op(m2), "/inbound-tasks/" + taskEId).status()).isEqualTo(403);
    assertThat(get(op(m1), "/inbound-tasks/TSK-NOPE").status()).isEqualTo(404);
    // 반입 목록 줄에도
    JsonNode loadLines =
        get(op(m1), "/initial-stock-loads?approvalId=" + approvalId).body().path("items");
    assertThat(loadLines.findValuesAsString("productName")).contains("서울우유 1L");
    // 조회가 실패해도 작업 목록은 그대로(표시만 빈 값)
    inventory.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(aResponse().withStatus(503)));
    JsonNode degraded =
        get(op(m1), "/inbound-tasks?mfcId=" + m1 + "&taskType=INSPECT").body().path("items");
    assertThat(degraded).hasSize(2);
    assertThat(degraded.path(0).path("productName").isNull()).isTrue();

    // ConfirmReceipt: 서비스 토큰, Idempotency-Key = receiptId, TRF 없는 초기 반입 표기, 정상 수량만
    inventory.verify(
        postRequestedFor(urlPathEqualTo(RECEIPTS))
            .withoutHeader("Authorization") // 서비스 간 인증 없음(ADR-0026)
            .withHeader("Idempotency-Key", equalTo(receiptId))
            .withHeader("X-Source-System", equalTo("mfc-inbound-service"))
            .withRequestBody(matchingJsonPath("$.receiptId", equalTo(receiptId)))
            .withRequestBody(matchingJsonPath("$.trfId", equalTo("INITIAL-" + approvalId)))
            .withRequestBody(matchingJsonPath("$.departureId", equalTo("INITIAL")))
            .withRequestBody(matchingJsonPath("$.lines[0].sku", equalTo("B-00000002")))
            .withRequestBody(matchingJsonPath("$.lines[0].normalQty", equalTo("3")))
            .withRequestBody(matchingJsonPath("$.lines[0].quarantinedQty", equalTo("0")))
            .withRequestBody(matchingJsonPath("$.lines[0].toLocationCode", equalTo("RCV-DOCK")))
            .withRequestBody(matchingJsonPath("$.lines[1].normalQty", equalTo("8"))));

    // 서비스 조회(receiptId), 운영자 경로는 서비스 토큰 403, 내부 경로는 운영자 403
    Res internal = get(SERVICE_TOKEN, "/internal/initial-stock-loads/" + receiptId);
    assertThat(internal.status()).isEqualTo(200);
    assertThat(internal.body().path("state").asString()).isEqualTo("APPLIED");
    assertThat(internal.body().path("approvalId").asString()).isEqualTo(approvalId);
    assertThat(get(SERVICE_TOKEN, "/initial-stock-loads?approvalId=" + approvalId).status())
        .isEqualTo(403);
    assertThat(get(OPERATOR, "/internal/initial-stock-loads/" + receiptId).status()).isEqualTo(403);
    assertThat(get(SERVICE_TOKEN, "/internal/initial-stock-loads/RCV-none").status())
        .isEqualTo(404);
    assertThat(get(OPERATOR, "/initial-stock-loads?approvalId=AP-none").body().path("items"))
        .isEmpty();

    // 같은 이벤트 재전달 → 반입 기록이 늘지 않는다(inbox)
    kafka.send(TOPIC, approvalId, event).join();
    String marker = "AP-" + UUID.randomUUID();
    approve(marker, "INITIAL_STOCK", "APPROVED", List.of(line(m1, "E-00000003", 1)));
    awaitLoads(marker, i -> i.size() == 1);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM initial_stock_load WHERE approval_id = ?",
                Integer.class,
                approvalId))
        .isEqualTo(2);
  }

  @Test
  void inventoryOutageIsRetriedWithTheSameReceiptId() throws Exception {
    String m = mfc();
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(m)))
            .inScenario("outage")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse().withStatus(500)) // 응답 불명(UNKNOWN_RESULT). 503 은 HTTP 클라이언트가 자체 1회 재시도한다
            .willSetStateTo("up"));
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(m)))
            .inScenario("outage")
            .whenScenarioStateIs("up")
            .willReturn(
                json(201, Map.of("status", "APPLIED", "requestId", "r", "resultId", "CMT-R"))));
    String approvalId = "AP-" + UUID.randomUUID();
    approve(approvalId, "INITIAL_STOCK", "APPROVED", List.of(line(m, "E-00000001", 4)));
    String expected =
        awaitLoads(approvalId, i -> allIn(i, 1, "EXPECTED")).path(0).path("receiptId").asString();
    receiptResult(expected, List.of(resultLine(1, "E-00000001", "LOT-1", 4)));
    assertThat(receive(m, expected, Map.of("E-00000001", 4)).status()).isEqualTo(202);

    JsonNode items = awaitLoads(approvalId, i -> allIn(i, 1, "APPLIED"));
    assertThat(allIn(items, 1, "APPLIED")).as(items.toString()).isTrue();
    assertThat(items.path(0).path("attempts").asInt()).isEqualTo(2);
    assertThat(items.path(0).path("inventoryCommitId").asString()).isEqualTo("CMT-R");
    assertThat(items.path(0).path("lastError").isNull()).isTrue();
    String receiptId = items.path(0).path("receiptId").asString();
    inventory.verify(
        2,
        postRequestedFor(urlPathEqualTo(RECEIPTS))
            .withHeader("Idempotency-Key", equalTo(receiptId))
            .withRequestBody(matchingJsonPath("$.receiptId", equalTo(receiptId))));
    // 두 번 다 같은 본문(receivedAt 포함) — inventory 가 receiptId 로 중복 계상을 막을 수 있다
    var bodies =
        inventory.findAll(
            postRequestedFor(urlPathEqualTo(RECEIPTS))
                .withHeader("Idempotency-Key", equalTo(receiptId)));
    assertThat(bodies.get(0).getBodyAsString()).isEqualTo(bodies.get(1).getBodyAsString());
  }

  @Test
  void inventoryRejectionAndInvalidApprovalAreRecordedAsRejected() throws Exception {
    // inventory 의 명시적 거절(4xx) → REJECTED, 재시도 안 함
    String m = mfc();
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(m)))
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":409,"
                            + "\"code\":\"RECEIPT_ALREADY_POSTED\",\"traceId\":\"t\"}")));
    String rejectedByInventory = "AP-" + UUID.randomUUID();
    approve(rejectedByInventory, "INITIAL_STOCK", "APPROVED", List.of(line(m, "E-00000001", 4)));
    String exp =
        awaitLoads(rejectedByInventory, i -> allIn(i, 1, "EXPECTED"))
            .path(0)
            .path("receiptId")
            .asString();
    assertThat(receive(m, exp, Map.of("E-00000001", 4)).status()).isEqualTo(202);
    JsonNode r1 = awaitLoads(rejectedByInventory, i -> allIn(i, 1, "REJECTED"));
    assertThat(r1.path(0).path("rejectReason").asString())
        .isEqualTo("INVENTORY_REJECTED:RECEIPT_ALREADY_POSTED (HTTP 409)");

    // 승인 내용 오류(수량 0·SKU 형식) → HTTP 호출 없이 REJECTED(INVALID: …)
    String bad = mfc();
    String invalid = "AP-" + UUID.randomUUID();
    approve(
        invalid,
        "INITIAL_STOCK",
        "APPROVED",
        List.of(line(bad, "E-00000001", 0), line(bad, "X-1", 2)));
    JsonNode r2 = awaitLoads(invalid, i -> allIn(i, 1, "REJECTED"));
    assertThat(r2.path(0).path("rejectReason").asString()).startsWith("INVALID: ");
    assertThat(r2.path(0).path("mfcId").asString()).isEqualTo(bad);
    assertThat(r2.path(0).path("lines")).isEmpty();

    // 다른 유형·반려 승인은 무시. 같은 파티션 키 표식 승인이 처리된 뒤 확인
    String other = "AP-" + UUID.randomUUID();
    approve(other, "CENTER_MASTER", "APPROVED", List.of(line(bad, "E-00000001", 1)));
    String declined = "AP-" + UUID.randomUUID();
    approve(declined, "INITIAL_STOCK", "REJECTED", List.of(line(bad, "E-00000001", 1)));
    String marker = "AP-" + UUID.randomUUID();
    approve(marker, "INITIAL_STOCK", "APPROVED", List.of(line(bad, "X", 1)));
    awaitLoads(marker, i -> i.size() == 1);
    Thread.sleep(500);
    assertThat(get(OPERATOR, "/initial-stock-loads?approvalId=" + other).body().path("items"))
        .isEmpty();
    assertThat(get(OPERATOR, "/initial-stock-loads?approvalId=" + declined).body().path("items"))
        .isEmpty();
    inventory.verify(
        0,
        postRequestedFor(urlPathEqualTo(RECEIPTS))
            .withRequestBody(matchingJsonPath("$.mfcId", equalTo(bad))));
  }

  @Test
  void inspectionSplitsLotsAndPutawayCompletesTasks() throws Exception {
    String m = mfc();
    inventoryAccepts(m, "CMT-T");
    String approvalId = "AP-" + UUID.randomUUID();
    approve(approvalId, "INITIAL_STOCK", "APPROVED", List.of(line(m, "E-00000001", 6)));
    String receiptId =
        awaitLoads(approvalId, i -> allIn(i, 1, "EXPECTED")).path(0).path("receiptId").asString();
    receiptResult(receiptId, List.of(resultLine(1, "E-00000001", "LOT-PEND", 6)));
    receive(m, receiptId, Map.of("E-00000001", 6));
    awaitLoads(approvalId, i -> allIn(i, 1, "APPLIED"));
    JsonNode inspect =
        get(op(m), "/inbound-tasks?mfcId=" + m + "&taskType=INSPECT").body().path("items").path(0);
    String taskId = inspect.path("taskId").asString();
    assertThat(inspect.path("plannedQty").asInt()).isEqualTo(6);

    // 품질검사: inventory 첫 호출은 응답 불명(500) → 503 UNKNOWN_RESULT, 같은 키로 다시 → 같은 실행 ID 로 반영
    String exec = "/api/v1/internal/task-executions";
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(exec))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("INSPECT")))
            .inScenario("ins")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(500))
            .willSetStateTo("up"));
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(exec))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("INSPECT")))
            .inScenario("ins")
            .whenScenarioStateIs("up")
            .willReturn(
                json(201, Map.of("status", "APPLIED", "requestId", "r", "resultId", "CMT-I"))));
    inventory.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/commits/CMT-I"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "commitId",
                        "CMT-I",
                        "entries",
                        List.of(
                            entry("LOT-PEND", "PENDING_INSPECTION", -6),
                            entry("LOT-A", "NORMAL", 3),
                            entry("LOT-B", "NORMAL", 2),
                            entry("LOT-PEND", "QUARANTINED", 1))))));
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("qty", 3);
    a.put("resultQuality", "NORMAL");
    a.put("sourceLotRef", "MFG-A");
    a.put("expiryDate", "2026-12-31");
    a.put("manufactureDate", "2026-11-01"); // ADR-0041
    Map<String, Object> b = new LinkedHashMap<>(a);
    b.put("qty", 2);
    b.put("sourceLotRef", "MFG-B");
    Map<String, Object> q = Map.of("qty", 1, "resultQuality", "QUARANTINED");
    String key = UUID.randomUUID().toString();
    Map<String, Object> body = Map.of("results", List.of(a, b, q));
    // 제조일이 소비기한보다 늦으면 400(ADR-0041)
    Map<String, Object> wrongMfg = new LinkedHashMap<>(a);
    wrongMfg.put("manufactureDate", "2027-01-01");
    assertThat(
            post(
                    op(m),
                    "/inbound-tasks/" + taskId + "/inspections",
                    UUID.randomUUID().toString(),
                    Map.of("results", List.of(wrongMfg)))
                .status())
        .isEqualTo(400);
    // 잔량 초과 → 409
    assertThat(
            post(
                    op(m),
                    "/inbound-tasks/" + taskId + "/inspections",
                    UUID.randomUUID().toString(),
                    Map.of("results", List.of(Map.of("qty", 7, "resultQuality", "NORMAL"))))
                .status())
        .isEqualTo(409);
    // (시험 HTTP 클라이언트가 503 을 한 번 스스로 다시 보낼 수 있다 — 그때도 같은 키라 같은 실행 ID)
    Res first = post(op(m), "/inbound-tasks/" + taskId + "/inspections", key, body);
    assertThat(first.status()).as(first.body().toString()).isIn(200, 503);
    if (first.status() == 503) {
      assertThat(first.body().path("code").asString()).isEqualTo("UNKNOWN_RESULT");
    }
    Res again = post(op(m), "/inbound-tasks/" + taskId + "/inspections", key, body);
    assertThat(again.status()).as(again.body().toString()).isEqualTo(200);
    assertThat(again.body().path("applyStatus").asString()).isEqualTo("APPLIED");
    assertThat(again.body().path("task").path("state").asString()).isEqualTo("COMPLETED");
    assertThat(again.body().path("createdTaskIds")).hasSize(2);
    var calls = inventory.findAll(postRequestedFor(urlPathEqualTo(exec)));
    assertThat(calls).hasSize(2);
    assertThat(calls.get(0).getHeader("Idempotency-Key"))
        .isEqualTo(calls.get(1).getHeader("Idempotency-Key"));
    assertThat(calls.get(1).getBodyAsString())
        .contains("\"sourceLotRef\":\"MFG-A\"")
        .contains("\"manufactureDate\":\"2026-11-01\"")
        .contains("LOT-PEND");
    // 같은 키 재생 → 같은 결과(다시 호출 안 함)
    Res replay = post(op(m), "/inbound-tasks/" + taskId + "/inspections", key, body);
    assertThat(replay.body().path("executionId").asString())
        .isEqualTo(again.body().path("executionId").asString());
    assertThat(inventory.findAll(postRequestedFor(urlPathEqualTo(exec)))).hasSize(2);

    // 적치 작업: 나뉜 LOT 마다(3·2), 부분 적치 후 완료
    JsonNode puts =
        get(op(m), "/inbound-tasks?mfcId=" + m + "&taskType=PUTAWAY").body().path("items");
    assertThat(puts).hasSize(2);
    JsonNode putA =
        "LOT-A".equals(puts.path(0).path("lotId").asString()) ? puts.path(0) : puts.path(1);
    assertThat(putA.path("plannedQty").asInt()).isEqualTo(3);
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(exec))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("PUTAWAY")))
            .willReturn(
                json(201, Map.of("status", "APPLIED", "requestId", "r", "resultId", "CMT-P"))));
    String putPath = "/inbound-tasks/" + putA.path("taskId").asString() + "/putaways";
    Res p1 =
        post(
            op(m),
            putPath,
            UUID.randomUUID().toString(),
            Map.of("toLocationCode", "A-01-01", "qty", 2));
    assertThat(p1.body().path("task").path("state").asString()).isEqualTo("READY");
    Res p2 =
        post(
            op(m),
            putPath,
            UUID.randomUUID().toString(),
            Map.of("toLocationCode", "A-01-02", "qty", 1));
    assertThat(p2.body().path("task").path("doneQty").asInt()).isEqualTo(3);
    assertThat(p2.body().path("task").path("state").asString()).isEqualTo("COMPLETED");
    inventory.verify(
        postRequestedFor(urlPathEqualTo(exec))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("PUTAWAY")))
            .withRequestBody(matchingJsonPath("$.fromLocation", equalTo("RCV-DOCK")))
            .withRequestBody(matchingJsonPath("$.toLocation", equalTo("A-01-02")))
            .withRequestBody(matchingJsonPath("$.lines[0].lotId", equalTo("LOT-A"))));
    // inventory 거절(목적지 온도대 등) → REJECTED, 잔량 그대로
    JsonNode putB = putA == puts.path(0) ? puts.path(1) : puts.path(0);
    inventory.stubFor(
        WireMock.post(urlPathEqualTo(exec))
            .withRequestBody(matchingJsonPath("$.toLocation", equalTo("C-01-01")))
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"status\":409,\"code\":\"TEMPERATURE_ZONE_MISMATCH\",\"detail\":\"x\"}")));
    Res rej =
        post(
            op(m),
            "/inbound-tasks/" + putB.path("taskId").asString() + "/putaways",
            UUID.randomUUID().toString(),
            Map.of("toLocationCode", "C-01-01", "qty", 2));
    assertThat(rej.body().path("applyStatus").asString()).isEqualTo("REJECTED");
    assertThat(rej.body().path("rejectReason").asString()).startsWith("TEMPERATURE_ZONE_MISMATCH");
    assertThat(rej.body().path("task").path("doneQty").asInt()).isZero();
    assertThat(putB.path("createdAt").asString()).isNotBlank();
    assertThat(putB.path("assigneeId").isNull()).isTrue();

    // (0.9.0, BR-52 R2) 다음 작업 받기 배정(AssignmentPublished) → 담당 투영, 담당이 아니면 409 NOT_ASSIGNEE
    String putBId = putB.path("taskId").asString();
    String assigned =
        EventEnvelopeFixtures.envelope("AssignmentPublished")
            .from("mfc-capacity-service")
            .aggregate("AssignmentPlan", "MANUAL:" + putBId, 1)
            .payload(
                Map.of(
                    "planVersion",
                    1,
                    "mfcId",
                    m,
                    "assignments",
                    List.of(
                        Map.of("taskId", putBId, "workerId", "wrk-put", "assignmentVersion", 1))))
            .buildJson();
    kafka.send("mfc.capacity.v1", "MANUAL:" + putBId, assigned).join();
    long until = System.currentTimeMillis() + 30_000;
    String assignee = null;
    while (System.currentTimeMillis() < until) {
      assignee =
          get(op(m), "/inbound-tasks/" + putBId + "?withLabels=false")
              .body()
              .path("assigneeId")
              .asString(null);
      if ("wrk-put".equals(assignee)) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(assignee).isEqualTo("wrk-put");
    Res notMine =
        post(
            op(m),
            "/inbound-tasks/" + putBId + "/putaways",
            UUID.randomUUID().toString(),
            Map.of("toLocationCode", "A-01-03", "qty", 2));
    assertThat(notMine.status()).as(notMine.body().toString()).isEqualTo(409);
    assertThat(notMine.body().path("code").asString()).isEqualTo("NOT_ASSIGNEE");
    String worker = TestJwt.operator("wrk-put", List.of("MFC_WORKER"), List.of(m));
    Res mine =
        post(
            worker,
            "/inbound-tasks/" + putBId + "/putaways",
            UUID.randomUUID().toString(),
            Map.of("toLocationCode", "A-01-03", "qty", 2));
    assertThat(mine.status()).as(mine.body().toString()).isEqualTo(200);
    assertThat(mine.body().path("task").path("state").asString()).isEqualTo("COMPLETED");
  }

  static Map<String, Object> entry(String lot, String quality, int delta) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("sku", "E-00000001");
    e.put("lotId", lot);
    e.put("locationCode", "RCV-DOCK");
    e.put("qualityState", quality);
    e.put("workState", "UNSTOWED");
    e.put("symbol", "H");
    e.put("delta", delta);
    e.put("balanceVersion", 1);
    return e;
  }
}
