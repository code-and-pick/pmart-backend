package com.pmart.mfc.fulfillment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 슬라이스 6b 점원 단말 흐름: 배정 투영(AssignmentPublished) → 시작(배정 동기 조회, 첫 PICK = PreparationStarted) → 피킹
 * 실적(재고 PICK) → PICK 완료·PACK 준비 → 포장 밀봉(재고 PACK) → 출고 READY·ShipmentReady. 결품 보고 BLOCKED, 재고 거절, 피킹
 * 뒤 중지(작업 종료·취소 복귀·RETURN 작업, IV-08). 재고·배정 정본은 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.fulfillment.mfc-inventory-base-url=${inv.url}",
      "pmart.fulfillment.mfc-capacity-base-url=${cap.url}",
      "pmart.fulfillment.ready-sweep-interval=PT1H",
      "pmart.fulfillment.handover-sweep-interval=PT1H",
      "pmart.fulfillment.handover-fence-ttl[MFC-T6]=PT2M"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "inv", baseUrlProperties = "inv.url"),
  @ConfigureWireMock(name = "cap", baseUrlProperties = "cap.url")
})
class TaskFlowIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-T6";
  static final String SVC = TestJwt.service("order-orchestration-service");
  static final String W1 = TestJwt.operator("w1", List.of("MFC_WORKER"), List.of(MFC));
  static final String W2 = TestJwt.operator("w2", List.of("MFC_WORKER"), List.of(MFC));
  static final String VIEWER = TestJwt.operator("v1", List.of(), List.of(MFC));
  static final String ADMIN = TestJwt.operator("OP-admin", List.of("OPERATOR_ADMIN"), List.of());
  static final String CS = TestJwt.operator("OP-cs", List.of("CS_AGENT"), List.of());
  static final String OTHER_CENTER =
      TestJwt.operator("w9", List.of("MFC_WORKER"), List.of("MFC-OTHER"));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired com.pmart.mfc.fulfillment.application.WorkStopService stops;
  @Autowired com.pmart.mfc.fulfillment.application.TaskService taskService;

  @InjectWireMock("inv")
  WireMockServer inv;

  @InjectWireMock("cap")
  WireMockServer cap;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    inv.resetAll();
    cap.resetAll();
    // SKU 사본 기본값(투영 없음) — 복귀 실적이 온도대를 읽는다(0.25.0, R19-8). 시험마다 더 높은 우선순위로 덮는다
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .atPriority(10)
            .willReturn(json(200, Map.of("items", List.of()))));
  }

  /** SKU 사본 — sku → 보관 온도대(바코드 없음). */
  void skuZones(Map<String, String> zones) {
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    zones.forEach(
        (sku, zone) ->
            items.add(
                Map.of(
                    "sku",
                    sku,
                    "state",
                    "ACTIVE",
                    "productVersion",
                    1,
                    "storageType",
                    zone,
                    "barcodes",
                    List.of())));
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(json(200, Map.of("items", items))));
  }

  static JsonNode line(JsonNode detail, String orderLineId) {
    for (JsonNode l : detail.path("lines")) {
      if (orderLineId.equals(l.path("orderLineId").asString())) {
        return l;
      }
    }
    throw new AssertionError("줄 " + orderLineId + " 없음: " + detail);
  }

  // ---------- 도우미 ----------

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(new ObjectMapper().writeValueAsString(body));
  }

  Res call(String method, String token, String path, @Nullable String key, @Nullable Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  if (key != null) {
                    h.set("Idempotency-Key", key);
                    h.set("X-Source-System", "mfc-pda");
                  }
                });
    var e =
        (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  /** 접수된 작업 지시(PICK READY·PACK WAITING). 줄 L1: 2개(두 LOT), L2: 1개. */
  String accept(String o) {
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        "RSV-" + o,
                        "state",
                        "CONFIRMED",
                        "parts",
                        List.of(
                            Map.of(
                                "partId",
                                "P1",
                                "sku",
                                "E-00000001",
                                "qty",
                                2,
                                "state",
                                "CONFIRMED"),
                            Map.of(
                                "partId",
                                "P2",
                                "sku",
                                "E-00000002",
                                "qty",
                                1,
                                "state",
                                "CONFIRMED"))))));
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("orderId", o);
    b.put("workVersion", 1);
    b.put("mfcId", MFC);
    b.put("reservationId", "RSV-" + o);
    b.put("deliveryMode", "SINGLE");
    b.put("outOfStockPreference", "CANCEL_OOS_ONLY");
    b.put(
        "shipments",
        List.of(
            Map.of(
                "shipmentId",
                o + "-S1",
                "lines",
                List.of(
                    Map.of("orderLineId", "L1", "sku", "E-00000001", "qty", 2, "productName", "두부"),
                    Map.of("orderLineId", "L2", "sku", "E-00000002", "qty", 1)))));
    Res r = call("POST", SVC, "/internal/work-orders", "c-" + o, b);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return o + "-S1";
  }

  /** 피킹 지시(inventory pick-plan) — picked·packed 는 시험 단계에 맞춰. */
  void plan(
      String o, int pickedA, int pickedB, int packedA, int packedB, int pickedL2, int packedL2) {
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/pick-plan"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        "RSV-" + o,
                        "state",
                        "CONFIRMED",
                        "parts",
                        List.of(
                            part(
                                "L1",
                                "E-00000001",
                                "LOT-A",
                                "2026-11-20",
                                1,
                                pickedA,
                                packedA,
                                "A-S1-01-1-01"),
                            part(
                                "L1",
                                "E-00000001",
                                "LOT-B",
                                "2026-12-01",
                                1,
                                pickedB,
                                packedB,
                                "A-S1-02-1-01"),
                            part(
                                "L2",
                                "E-00000002",
                                "LOT-C",
                                null,
                                1,
                                pickedL2,
                                packedL2,
                                "C-S1-01-1-01"))))));
  }

  static Map<String, Object> part(
      String line,
      String sku,
      String lot,
      @Nullable String exp,
      int qty,
      int picked,
      int packed,
      String loc) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("orderLineId", line);
    m.put("sku", sku);
    m.put("lotId", lot);
    m.put("lotNo", "N" + lot); // LOT 번호(inventory 0.21.0) — 시험용 값
    m.put("expiryDate", exp);
    m.put("state", "CONFIRMED");
    m.put("qty", qty);
    m.put("pickedQty", picked);
    m.put("packedQty", packed);
    m.put(
        "locations",
        List.of(Map.of("locationCode", loc, "qty", 5, "allocatedQty", Math.max(0, qty - picked))));
    return m;
  }

  void assigned(String taskId, String worker, int version) {
    String json =
        EventEnvelopeFixtures.envelope("AssignmentPublished")
            .from("mfc-capacity-service")
            .aggregate("AssignmentPlan", "MANUAL:" + taskId, version)
            .payload(
                Map.of(
                    "planVersion",
                    version,
                    "mfcId",
                    MFC,
                    "assignments",
                    List.of(
                        Map.of(
                            "taskId", taskId, "workerId", worker, "assignmentVersion", version))))
            .buildJson();
    kafka.send("mfc.capacity.v1", "MANUAL:" + taskId, json).join();
    cap.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/assignments"))
            .withQueryParam("taskId", equalTo(taskId))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "taskId",
                                taskId,
                                "workerId",
                                worker,
                                "assignmentVersion",
                                version,
                                "state",
                                "ACTIVE"))))));
    await(
        "SELECT COUNT(*) FROM task WHERE task_id = '"
            + taskId
            + "' AND assignment_version = "
            + version);
  }

  void await(String countSql) {
    long until = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < until) {
      Integer n = jdbc.queryForObject(countSql, Integer.class);
      if (n != null && n > 0) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("시간 안에 반영 안 됨: " + countSql);
  }

  void inventoryCommits(String type, int status, Object body) {
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo(type)))
            .willReturn(json(status, body)));
  }

  Res start(String token, String taskId, String worker, int version) {
    return call(
        "POST",
        token,
        "/tasks/" + taskId + "/starts",
        UUID.randomUUID().toString(),
        Map.of("workerId", worker, "assignmentVersion", version, "deviceId", "PDA-1"));
  }

  int outbox(String type, String aggregateId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
        Integer.class,
        type,
        aggregateId);
  }

  // ---------- 시험 ----------

  @Test
  void pickThenPackMakesShipmentReady() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    String pack = s + "-PACK";

    // 배정 전: 시작 못 한다(담당 없음), 센터 관리자가 배정 → ASSIGNED
    cap.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/assignments"))
            .willReturn(json(200, Map.of("items", List.of()))));
    Res noAssignee = start(W1, pick, "w1", 0);
    assertThat(noAssignee.status()).isEqualTo(409);
    assertThat(noAssignee.body().path("code").asString()).isEqualTo("NOT_ASSIGNEE");
    assigned(pick, "w1", 1);
    JsonNode mine = call("GET", W1, "/tasks?mfcId=" + MFC + "&assignee=me", null, null).body();
    assertThat(mine.path("items").findValuesAsString("taskId")).contains(pick);
    JsonNode mineRow = mine.path("items").path(0);
    assertThat(mineRow.path("lineCount").asInt()).isPositive();
    assertThat(mineRow.path("headline").path("otherLineCount").asInt())
        .isEqualTo(mineRow.path("lineCount").asInt() - 1);
    assertThat(mineRow.path("plannedQty").asInt()).isPositive();

    // 다른 작업자 토큰으로 w1 시작 → 403, w2 는 담당 아님 → 409
    assertThat(start(W2, pick, "w1", 1).status()).isEqualTo(403);
    assertThat(start(W2, pick, "w2", 1).body().path("code").asString()).isEqualTo("NOT_ASSIGNEE");

    // w1 시작 → IN_PROGRESS, 준비 시작 사건 한 번, 재고 위치 배정 한 번(같은 키 재생은 다시 안 부름)
    inv.stubFor(
        WireMock.post(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/location-allocations"))
            .willReturn(json(200, Map.of("reservationId", "RSV-" + o, "parts", List.of()))));
    String key = UUID.randomUUID().toString();
    Res started =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/starts",
            key,
            Map.of("workerId", "w1", "assignmentVersion", 1));
    assertThat(started.status()).as(started.body().toString()).isEqualTo(201);
    Res replay =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/starts",
            key,
            Map.of("workerId", "w1", "assignmentVersion", 1));
    assertThat(replay.status()).isEqualTo(201);
    assertThat(start(W1, pick, "w1", 1).body().path("code").asString())
        .isEqualTo("ALREADY_STARTED");
    assertThat(outbox("PreparationStarted", o)).isEqualTo(1);
    inv.verify(
        1,
        WireMock.postRequestedFor(
            urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/location-allocations")));
    assertThat(
            call("GET", W1, "/tasks/" + pick + "/starts/" + key, null, null)
                .body()
                .path("outcome")
                .asString())
        .isEqualTo("STARTED");
    // READY 전 출고 조회(피킹 중) — readyAt null 이어도 200(2026-11-17 PDA 패킹 화면 500 수정)
    Res picking = call("GET", VIEWER, "/shipments/" + s, null, null);
    assertThat(picking.status()).as(picking.body().toString()).isEqualTo(200);
    assertThat(picking.body().path("readyAt").isNull()).isTrue();

    // 상세: LOT 별 지시(소비기한·위치)
    plan(o, 0, 0, 0, 0, 0, 0);
    JsonNode detail = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(detail.path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(detail.path("activeStart").path("workerId").asString()).isEqualTo("w1");
    JsonNode l1 = detail.path("lines").path(0);
    assertThat(l1.path("picks")).hasSize(2);
    assertThat(l1.path("lotId").asString()).isEqualTo("LOT-A");
    assertThat(l1.path("locationId").asString()).isEqualTo("A-S1-01-1-01");
    // LOT 번호(0.11.0, IV-4) — PDA 가 찍은 GS1 (10) 과 대조
    assertThat(l1.path("picks").path(0).path("lotNo").asString()).isEqualTo("NLOT-A");
    assertThat(l1.path("picks").path(0).path("locations").path(0).path("allocatedQty").asInt())
        .isEqualTo(1);
    // 0.4.9 화면 표시: 칸이 정해진 조각은 OK·안내 없음
    assertThat(l1.path("picks").path(0).path("pickStatus").asString()).isEqualTo("OK");
    assertThat(l1.path("picks").path(0).path("unallocatedQty").asInt()).isZero();
    assertThat(l1.path("picks").path(0).path("notice").isNull()).isTrue();
    // 0.4.10 방문 순서: 시작 때 순서를 못 받으면(capacity 스텁 없음) 칸 코드 순
    assertThat(detail.path("routeMethod").asString()).isEqualTo("CODE_ORDER");
    assertThat(l1.path("picks").path(0).path("visitSeq").asInt()).isEqualTo(1);
    assertThat(detail.path("lines").path(1).path("picks").path(0).path("visitSeq").asInt())
        .isEqualTo(3);

    // 재고가 거절한 실적 → REJECTED, 수량 변화 없음
    inventoryCommits("PICK", 422, Map.of("code", "LOT_MISMATCH", "detail", "예약 LOT 이 아닙니다"));
    Res rejected =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            Map.of(
                "executionType",
                "PICK",
                "workerId",
                "w1",
                "fromLocation",
                "A-S1-09-1-01",
                "toLocation",
                "PAK-A-01",
                "lines",
                List.of(
                    Map.of(
                        "orderLineId",
                        "L1",
                        "sku",
                        "E-00000001",
                        "lotId",
                        "LOT-Z",
                        "pickedQty",
                        1))));
    assertThat(rejected.status()).isEqualTo(202);
    assertThat(rejected.body().path("status").asString()).isEqualTo("REJECTED");
    // mfc-app E1(0.16.0): 실행 조회에 거절 코드·설명
    JsonNode why =
        call(
                "GET",
                W1,
                "/tasks/" + pick + "/executions/" + rejected.body().path("resultId").asString(),
                null,
                null)
            .body();
    assertThat(why.path("rejectCode").asString()).isEqualTo("LOT_MISMATCH");
    assertThat(why.path("rejectReason").asString()).isEqualTo("예약 LOT 이 아닙니다");

    // 반영된 실적: L1 2개(두 선반은 두 번), L2 1개 → PICK 완료, PACK 준비
    inventoryCommits(
        "PICK", 201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1));
    for (var line :
        List.of(
            List.of("L1", "E-00000001", "LOT-A", "A-S1-01-1-01"),
            List.of("L1", "E-00000001", "LOT-B", "A-S1-02-1-01"),
            List.of("L2", "E-00000002", "LOT-C", "C-S1-01-1-01"))) {
      Res ok =
          call(
              "POST",
              W1,
              "/tasks/" + pick + "/executions",
              UUID.randomUUID().toString(),
              Map.of(
                  "executionType",
                  "PICK",
                  "workerId",
                  "w1",
                  "fromLocation",
                  line.get(3),
                  "toLocation",
                  "PAK-A-01",
                  "lines",
                  List.of(
                      Map.of(
                          "orderLineId",
                          line.get(0),
                          "sku",
                          line.get(1),
                          "lotId",
                          line.get(2),
                          "pickedQty",
                          1))));
      assertThat(ok.status()).as(ok.body().toString()).isEqualTo(202);
      assertThat(ok.body().path("status").asString()).isEqualTo("APPLIED");
    }
    // 실적 목록(0.24.0, 시뮬레이터 R-17): 최근 순 — 마지막 실적이 앞, 거절된 실적도 남는다. 상세 lastExecution = 마지막 찍은 칸
    Res execs = call("GET", W1, "/tasks/" + pick + "/executions", null, null);
    assertThat(execs.status()).as(execs.body().toString()).isEqualTo(200);
    JsonNode items = execs.body().path("items");
    assertThat(items).hasSize(4);
    assertThat(items.path(0).path("executionType").asString()).isEqualTo("PICK");
    assertThat(items.path(0).path("workerId").asString()).isEqualTo("w1");
    assertThat(items.path(0).path("fromLocation").asString()).isEqualTo("C-S1-01-1-01");
    assertThat(items.path(0).path("toLocation").asString()).isEqualTo("PAK-A-01");
    assertThat(items.path(0).path("state").asString()).isEqualTo("APPLIED");
    assertThat(items.path(0).path("lines").path(0).path("orderLineId").asString()).isEqualTo("L2");
    assertThat(items.path(0).path("lines").path(0).path("lotId").asString()).isEqualTo("LOT-C");
    assertThat(items.path(0).path("lines").path(0).path("pickedQty").asInt()).isEqualTo(1);
    assertThat(items.path(3).path("state").asString()).isEqualTo("REJECTED");
    assertThat(items.path(3).path("executionId").asString())
        .isEqualTo(rejected.body().path("resultId").asString());
    assertThat(
            call("GET", W1, "/tasks/" + pick + "/executions?size=1", null, null)
                .body()
                .path("items"))
        .hasSize(1);
    JsonNode lastExec =
        call("GET", VIEWER, "/tasks/" + pick, null, null).body().path("lastExecution");
    assertThat(lastExec.path("executionId").asString())
        .isEqualTo(items.path(0).path("executionId").asString());
    assertThat(lastExec.path("fromLocation").asString()).isEqualTo("C-S1-01-1-01");
    assertThat(lastExec.path("toLocation").asString()).isEqualTo("PAK-A-01");
    assertThat(lastExec.path("recordedAt").isMissingNode()).isFalse();
    JsonNode packLast = call("GET", W1, "/tasks/" + pack, null, null).body().path("lastExecution");
    assertThat(packLast.isNull() || packLast.isMissingNode()).isTrue();
    assertThat(call("GET", W1, "/tasks/NOPE-PICK/executions", null, null).status()).isEqualTo(404);
    // 재고 호출에 예약·주문 줄이 실린다
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.reservationId", equalTo("RSV-" + o)))
            .withRequestBody(matchingJsonPath("$.lines[0].orderLineId", equalTo("L2"))));
    // 남은 0 인데 더 집으면 409
    Res over =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            Map.of(
                "executionType",
                "PICK",
                "workerId",
                "w1",
                "fromLocation",
                "A-S1-01-1-01",
                "toLocation",
                "PAK-A-01",
                "lines",
                List.of(
                    Map.of(
                        "orderLineId",
                        "L1",
                        "sku",
                        "E-00000001",
                        "lotId",
                        "LOT-A",
                        "pickedQty",
                        1))));
    assertThat(over.status()).isEqualTo(409);
    assertThat(call("GET", VIEWER, "/tasks/" + pick, null, null).body().path("state").asString())
        .isEqualTo("COMPLETED");
    assertThat(call("GET", VIEWER, "/tasks/" + pack, null, null).body().path("state").asString())
        .isEqualTo("READY");
    // 0.18.0: 목록의 PACK 에 피킹한 사람(capacity 가 그 사람에게만 준다), PICK 은 null
    JsonNode listed =
        call(
                "GET",
                VIEWER,
                "/tasks?mfcId=" + MFC + "&state=READY&state=COMPLETED&size=200",
                null,
                null)
            .body()
            .path("items");
    for (JsonNode row : listed) {
      if (pack.equals(row.path("taskId").asString())) {
        assertThat(row.path("pickedBy").asString()).isEqualTo("w1");
      }
      if (pick.equals(row.path("taskId").asString())) {
        assertThat(row.path("pickedBy").isNull()).isTrue();
      }
    }
    assertThat(listed.findValuesAsString("taskId")).contains(pick, pack);

    // 포장: w2 배정·시작, 포장 위치는 집품분 위치에서 하나로 정해진다
    assigned(pack, "w2", 1);
    assertThat(call("GET", VIEWER, "/tasks/" + pack, null, null).body().path("state").asString())
        .isEqualTo("ASSIGNED");
    assertThat(start(W2, pack, "w2", 1).status()).isEqualTo(201);
    assertThat(
            call("GET", VIEWER, "/tasks/" + pack, null, null)
                .body()
                .path("packingLocations")
                .toString())
        .contains("PAK-A-01");
    plan(o, 1, 1, 0, 0, 1, 0);
    inventoryCommits(
        "PACK", 201, Map.of("status", "APPLIED", "resultId", "CMT-P", "appliedQty", 2));
    // 집품보다 많이 담으면 409
    Res tooMuch =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "contents",
                List.of(Map.of("orderLineId", "L1", "sku", "E-00000001", "qty", 3))));
    assertThat(tooMuch.status()).isEqualTo(409);
    // 첫 포장: L1 2개(LOT 은 서버가 기한 빠른 순 — LOT-A, LOT-B)
    Res p1 =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "weightG",
                600,
                "contents",
                List.of(Map.of("orderLineId", "L1", "sku", "E-00000001", "qty", 2))));
    assertThat(p1.status()).as(p1.body().toString()).isEqualTo(201);
    assertThat(p1.body().path("state").asString()).isEqualTo("SEALED");
    assertThat(p1.body().path("qrCode").asString()).startsWith("PKG:");
    assertThat(p1.body().path("contents").findValuesAsString("lotId"))
        .containsExactly("LOT-A", "LOT-B");
    assertThat(outbox("ShipmentReady", s)).isZero();
    // READY 전 출고 조회(포장 중) — 200, readyAt null, 밀봉 포장 1
    Res packing = call("GET", VIEWER, "/shipments/" + s, null, null);
    assertThat(packing.status()).as(packing.body().toString()).isEqualTo(200);
    assertThat(packing.body().path("readyAt").isNull()).isTrue();
    assertThat(packing.body().path("packages")).hasSize(1);
    // 두 번째 포장(냉장 L2) → PACK 완료, 출고 READY, PackingFinished·ShipmentReady
    plan(o, 1, 1, 1, 1, 1, 0);
    Res p2 =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "temperatureZone",
                "CHILLED",
                "contents",
                List.of(Map.of("orderLineId", "L2", "sku", "E-00000002", "qty", 1))));
    assertThat(p2.status()).as(p2.body().toString()).isEqualTo(201);
    JsonNode shipment = call("GET", VIEWER, "/shipments/" + s, null, null).body();
    assertThat(shipment.path("state").asString()).isEqualTo("READY");
    assertThat(shipment.path("packages")).hasSize(2);
    // F-7(0.12.0): 작업자 처리 내역(내부 — mfc-capacity 가 모은다)·작업 목록 활동 날짜·완료 시각
    java.time.Instant now = java.time.Instant.now();
    String window = "&from=" + now.minusSeconds(3600) + "&to=" + now.plusSeconds(3600);
    JsonNode packs =
        call(
                "GET",
                SVC,
                "/internal/work-activities?mfcId=" + MFC + "&operatorId=w2" + window,
                null,
                null)
            .body()
            .path("items");
    List<String> myPacks = new java.util.ArrayList<>();
    for (JsonNode a : packs) {
      if ((s + "-PACK").equals(a.path("taskId").asString())) {
        myPacks.add(a.path("activityType").asString());
      }
    }
    assertThat(myPacks).as("이 출고 포장 2개").containsExactly("PACK", "PACK");
    JsonNode picks =
        call(
                "GET",
                SVC,
                "/internal/work-activities?mfcId=" + MFC + "&operatorId=w1" + window,
                null,
                null)
            .body()
            .path("items");
    List<String> myPicks = new java.util.ArrayList<>();
    for (JsonNode a : picks) {
      if (pick.equals(a.path("taskId").asString())) {
        myPicks.add(a.path("activityType").asString() + ":" + a.path("result").asString());
      }
    }
    assertThat(myPicks).as("이 작업의 피킹 실적").contains("PICK:APPLIED");
    String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
    String yesterday =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusDays(1).toString();
    JsonNode myTasks =
        call(
                "GET",
                W1,
                "/tasks?mfcId=" + MFC + "&assignee=me&state=COMPLETED&activityFrom=" + today,
                null,
                null)
            .body()
            .path("items");
    JsonNode done = null;
    for (JsonNode t : myTasks) {
      if (t.path("taskId").asString().equals(pick)) {
        done = t;
      }
    }
    assertThat(done).as("오늘 끝낸 피킹").isNotNull();
    assertThat(done.path("completedAt").isNull()).isFalse();
    assertThat(
            call(
                    "GET",
                    W1,
                    "/tasks?mfcId=" + MFC + "&assignee=me&state=COMPLETED&activityTo=" + yesterday,
                    null,
                    null)
                .body()
                .path("items")
                .findValuesAsString("taskId"))
        .doesNotContain(pick);
    assertThat(call("GET", VIEWER, "/tasks/" + pack, null, null).body().path("state").asString())
        .isEqualTo("COMPLETED");
    assertThat(outbox("PackingFinished", s)).isEqualTo(1);
    assertThat(outbox("ShipmentReady", s)).isEqualTo(1);
  }

  static Map<String, Object> shortageBody(String line, String sku, String lot, String reason) {
    String shelf = "L2".equals(line) ? "C-S1-01-1-01" : "A-S1-01-1-01";
    return Map.of(
        "executionType",
        "PICK",
        "workerId",
        "w1",
        "fromLocation",
        shelf,
        "lines",
        List.of(
            Map.of(
                "orderLineId",
                line,
                "sku",
                sku,
                "lotId",
                lot,
                "pickedQty",
                0,
                "shortage",
                Map.of("qty", 1, "reason", reason))));
  }

  /**
   * 예약 보류분은 피킹 시작 때 결품으로(2026-11-08 #5 안 C): pick-plan 에 BLOCKED 조각(L2 1개)이 있으면 시작 직후 작업 BLOCKED +
   * LineShortageReported(reason BLOCKED, shortQty 1) — inventory 결품 보고는 부르지 않는다.
   */
  /** 피킹 중(시작 뒤) 생긴 예약 보류: ReservationBlocked → 점검 표시 → 주기 점검이 결품으로(#5 남은 빈틈 — 집품이 더 없어도). */
  @Test
  void reservationBlockedDuringPickingIsReportedWithoutAnotherPick() throws Exception {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    plan(o, 0, 0, 0, 0, 0, 0);
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    assertThat(call("GET", VIEWER, "/tasks/" + pick, null, null).body().path("state").asString())
        .isEqualTo("IN_PROGRESS");

    // 그 뒤 L2 가 격리로 보류됨
    Map<String, Object> blockedPart =
        new LinkedHashMap<>(part("L2", "E-00000002", "LOT-C", null, 1, 0, 0, "C-S1-01-1-01"));
    blockedPart.put("state", "BLOCKED");
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/pick-plan"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        "RSV-" + o,
                        "state",
                        "CONFIRMED",
                        "parts",
                        List.of(
                            part(
                                "L1", "E-00000001", "LOT-A", "2026-11-20", 2, 0, 0, "A-S1-01-1-01"),
                            blockedPart)))));
    String json =
        EventEnvelopeFixtures.envelope("ReservationBlocked")
            .from("mfc-inventory-service")
            .aggregate("Reservation", "RSV-" + o, 5)
            .payload(
                Map.of(
                    "reservationId",
                    "RSV-" + o,
                    "orderId",
                    o,
                    "mfcId",
                    MFC,
                    "sku",
                    "E-00000002",
                    "lot",
                    "LOT-C",
                    "blockedQty",
                    1,
                    "reason",
                    "QUALITY_HOLD"))
            .buildJson();
    kafka.send("mfc.inventory.v1", "RSV-" + o, json).join();
    long until = System.currentTimeMillis() + 30_000;
    Integer due = 0;
    while (System.currentTimeMillis() < until) {
      due =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM task WHERE task_id = ? AND blocked_check_due_at IS NOT NULL",
              Integer.class,
              pick);
      if (due != null && due > 0) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(due).isEqualTo(1);
    taskService.sweepBlockedChecks();
    JsonNode t = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(t.path("state").asString()).isEqualTo("BLOCKED");
    assertThat(outbox("LineShortageReported", o)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM task WHERE task_id = ? AND blocked_check_due_at IS NULL",
                Integer.class,
                pick))
        .isEqualTo(1);
  }

  /**
   * 0.4.10 피킹 방문 순서(ADR-0050 B2): 시작 때 위치 배정 뒤 칸 목록을 mfc-capacity 에 물어 저장하고, 상세가 그 순서로 줄·조각을
   * 늘어놓는다(냉장 칸 C-… 이 먼저 오면 L2 줄이 먼저).
   */
  @Test
  void pickDetailFollowsRouteFromCapacity() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    assigned(pick, "w1", 1);
    inv.stubFor(
        WireMock.post(
                urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/location-allocations"))
            .willReturn(json(200, Map.of("reservationId", "RSV-" + o, "parts", List.of()))));
    plan(o, 0, 0, 0, 0, 0, 0);
    cap.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/pick-routes"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "ordered",
                        List.of("C-S1-01-1-01", "A-S1-02-1-01", "A-S1-01-1-01"),
                        "method",
                        "ROUTING",
                        "startLocation",
                        "CRT-A-01"))));
    Res started = start(W1, pick, "w1", 1);
    assertThat(started.status()).as(started.body().toString()).isEqualTo(201);
    cap.verify(
        1,
        WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/pick-routes"))
            .withRequestBody(WireMock.containing("C-S1-01-1-01"))
            .withRequestBody(WireMock.containing("A-S1-01-1-01")));
    JsonNode d = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(d.path("routeMethod").asString()).isEqualTo("ROUTING");
    assertThat(d.path("lines").path(0).path("orderLineId").asString()).isEqualTo("L2");
    assertThat(d.path("lines").path(0).path("picks").path(0).path("visitSeq").asInt()).isEqualTo(1);
    JsonNode l1 = d.path("lines").path(1);
    assertThat(l1.path("orderLineId").asString()).isEqualTo("L1");
    // 같은 줄 안에서도 순서대로: LOT-B(A-S1-02, 2번) → LOT-A(A-S1-01, 3번)
    assertThat(l1.path("picks").path(0).path("lotId").asString()).isEqualTo("LOT-B");
    assertThat(l1.path("picks").path(0).path("visitSeq").asInt()).isEqualTo(2);
    assertThat(l1.path("picks").path(1).path("visitSeq").asInt()).isEqualTo(3);
  }

  /**
   * 0.7.0 PDA 스캔 대조: 작업 상세 줄에 그 SKU 의 활성 바코드 전부(barcodes)와 첫 값(barcode). 조회 때 mfc-inventory SKU 투영을
   * 한 번에 묻는다(줄마다 부르지 않음). 투영 없는 SKU·재고 조회 실패는 빈 바코드로 상세는 그대로.
   */
  @Test
  void taskLinesCarryBarcodesFromInventorySkuProjection() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    plan(o, 0, 0, 0, 0, 0, 0);
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .withQueryParam("skus", equalTo("E-00000001,E-00000002"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "sku",
                                "E-00000001",
                                "state",
                                "ACTIVE",
                                "productVersion",
                                3,
                                "barcodes",
                                List.of("8801234567893", "2990000000012"))),
                        "missing",
                        List.of("E-00000002")))));

    JsonNode d = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    JsonNode l1 = null;
    JsonNode l2 = null;
    for (JsonNode l : d.path("lines")) {
      if ("L1".equals(l.path("orderLineId").asString())) {
        l1 = l;
      } else if ("L2".equals(l.path("orderLineId").asString())) {
        l2 = l;
      }
    }
    assertThat(l1).isNotNull();
    assertThat(l2).isNotNull();
    List<String> codes = new java.util.ArrayList<>();
    l1.path("barcodes").forEach(b -> codes.add(b.asString()));
    assertThat(codes).containsExactly("8801234567893", "2990000000012");
    assertThat(l1.path("barcode").asString()).isEqualTo("8801234567893");
    assertThat(l2.path("barcodes").isEmpty()).isTrue();
    assertThat(l2.hasNonNull("barcode")).isFalse();
    inv.verify(1, WireMock.getRequestedFor(urlPathEqualTo("/api/v1/internal/skus")));

    // PACK 상세도 같은 바코드
    JsonNode pack = call("GET", VIEWER, "/tasks/" + s + "-PACK", null, null).body();
    assertThat(pack.path("lines").findValuesAsString("barcode")).contains("8801234567893");

    // 재고 SKU 조회 실패(503) → 상세는 200, 바코드는 비움
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(aResponse().withStatus(503)));
    Res down = call("GET", VIEWER, "/tasks/" + pick, null, null);
    assertThat(down.status()).isEqualTo(200);
    for (JsonNode l : down.body().path("lines")) {
      assertThat(l.path("barcodes").isEmpty()).isTrue();
      assertThat(l.hasNonNull("barcode")).isFalse();
    }
  }

  @Test
  void blockedReservationPartBecomesShortageAtPickStart() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    Map<String, Object> blockedPart =
        new LinkedHashMap<>(part("L2", "E-00000002", "LOT-C", null, 1, 0, 0, "C-S1-01-1-01"));
    blockedPart.put("state", "BLOCKED");
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/pick-plan"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "reservationId",
                        "RSV-" + o,
                        "state",
                        "CONFIRMED",
                        "parts",
                        List.of(
                            part(
                                "L1", "E-00000001", "LOT-A", "2026-11-20", 2, 0, 0, "A-S1-01-1-01"),
                            blockedPart)))));
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    JsonNode t = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(t.path("state").asString()).isEqualTo("BLOCKED");
    assertThat(t.path("blockReason").asString()).contains("예약 보류").contains("L2");
    assertThat(outbox("LineShortageReported", o)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT payload #>> '{payload,reason}' || ':' || (payload #>> '{payload,shortQty}')"
                    + " FROM outbox_event WHERE type = 'LineShortageReported' AND aggregateid = ?",
                String.class,
                o))
        .isEqualTo("BLOCKED:1");
    inv.verify(0, WireMock.postRequestedFor(urlPathEqualTo("/api/v1/internal/shortage-reports")));
  }

  @Test
  void shortageBlocksTaskAndResolutionResumesWork() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);

    // 결품 보고는 집품과 섞지 않는다(400), 위치·LOT 필수
    assertThat(
            call(
                    "POST",
                    W1,
                    "/tasks/" + pick + "/executions",
                    UUID.randomUUID().toString(),
                    Map.of(
                        "executionType",
                        "PICK",
                        "workerId",
                        "w1",
                        "fromLocation",
                        "A-S1-01-1-01",
                        "toLocation",
                        "PAK-A-01",
                        "lines",
                        List.of(
                            Map.of(
                                "orderLineId",
                                "L1",
                                "sku",
                                "E-00000001",
                                "lotId",
                                "LOT-A",
                                "pickedQty",
                                1,
                                "shortage",
                                Map.of("qty", 1, "reason", "SHORT")))))
                .status())
        .isEqualTo(400);

    // 1) L1 결품 → 재고가 같은 SKU 적격 LOT 으로 재확보(blockedQty 0) → 작업 계속(IN_PROGRESS), 결품 사건 없음
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/shortage-reports"))
            .withRequestBody(matchingJsonPath("$.orderLineId", equalTo("L1")))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "reportId",
                        "x",
                        "commitId",
                        "CMT-S1",
                        "resecuredQty",
                        1,
                        "blockedQty",
                        0))));
    Res resecured =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            shortageBody("L1", "E-00000001", "LOT-A", "SHORT"));
    assertThat(resecured.status()).as(resecured.body().toString()).isEqualTo(202);
    assertThat(resecured.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(call("GET", VIEWER, "/tasks/" + pick, null, null).body().path("state").asString())
        .isEqualTo("IN_PROGRESS");
    assertThat(outbox("LineShortageReported", o)).isZero();

    // 2) L2 파손 → 재확보 못 함(blockedQty 1) → BLOCKED, LineShortageReported(shortQty 1)
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/shortage-reports"))
            .withRequestBody(matchingJsonPath("$.orderLineId", equalTo("L2")))
            .willReturn(
                json(
                    201,
                    Map.of(
                        "reportId",
                        "y",
                        "commitId",
                        "CMT-S2",
                        "resecuredQty",
                        0,
                        "blockedQty",
                        1))));
    Res shortage =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            shortageBody("L2", "E-00000002", "LOT-C", "DAMAGED"));
    assertThat(shortage.status()).as(shortage.body().toString()).isEqualTo(202);
    JsonNode t = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(t.path("state").asString()).isEqualTo("BLOCKED");
    assertThat(t.path("blockReason").asString()).contains("L2").contains("DAMAGED");
    assertThat(outbox("LineShortageReported", o)).isEqualTo(1);
    // 통합 조회 MFC 칸(0.10.0, F-3): 결품 보고 두 건, PICK BLOCKED 와 사유
    JsonNode looked = call("GET", CS, "/work-orders?orderId=" + o, null, null).body();
    JsonNode lookedShipment = looked.path("workOrders").get(0).path("shipments").get(0);
    assertThat(lookedShipment.path("shortages")).hasSize(2);
    assertThat(lookedShipment.path("shortages").get(1).path("orderLineId").asString())
        .isEqualTo("L2");
    assertThat(lookedShipment.path("shortages").get(1).path("reason").asString())
        .isEqualTo("DAMAGED");
    assertThat(lookedShipment.path("shortages").get(1).path("qty").asInt()).isEqualTo(1);
    assertThat(lookedShipment.path("tasks").get(0).path("state").asString()).isEqualTo("BLOCKED");
    assertThat(lookedShipment.path("tasks").get(0).path("blockReason").asString())
        .contains("DAMAGED");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/shortage-reports"))
            .withRequestBody(matchingJsonPath("$.reservationId", equalTo("RSV-" + o)))
            .withRequestBody(matchingJsonPath("$.locationCode", equalTo("C-S1-01-1-01")))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("DAMAGED"))));
    inv.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions")));

    // 결품 사건에 고객 사전 선택·예약이 실린다(결품 사가 ADR-0045)
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'LineShortageReported'"
                    + " AND aggregateid = ?",
                String.class,
                o))
        .contains("\"outOfStockPreference\": \"CANCEL_OOS_ONLY\"")
        .contains("RSV-" + o);

    // 품절 상품만 취소 → 작업에서 그 수량을 빼고 BLOCKED 를 풀어 진행(IN_PROGRESS)
    String fid =
        call("GET", SVC, "/internal/work-orders?orderId=" + o, null, null)
            .body()
            .path("fulfillmentId")
            .asString();
    String rkey = UUID.randomUUID().toString();
    Map<String, Object> res = Map.of("orderLineId", "L2", "qty", 1, "resolution", "CANCEL_ITEM");
    Res resolved =
        call("POST", SVC, "/internal/work-orders/" + fid + "/shortage-resolutions", rkey, res);
    assertThat(resolved.status()).as(resolved.body().toString()).isEqualTo(200);
    assertThat(
            call("POST", SVC, "/internal/work-orders/" + fid + "/shortage-resolutions", rkey, res)
                .status())
        .isEqualTo(200);
    JsonNode after = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(after.path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(after.path("blockReason").isNull()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT cancelled_qty FROM task_line WHERE task_id = ? AND order_line_id = 'L2'",
                Integer.class,
                pick))
        .isEqualTo(1);
    // 남은 양보다 많이 → 409
    assertThat(
            call(
                    "POST",
                    SVC,
                    "/internal/work-orders/" + fid + "/shortage-resolutions",
                    UUID.randomUUID().toString(),
                    res)
                .status())
        .isEqualTo(409);
    // 결품률 지표(0.19.0, F-10): 결품 보고가 줄·사유로 남고 날짜·센터별로 센다
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_shortage WHERE task_id = ? AND reason = 'DAMAGED'",
                Integer.class,
                pick))
        .isPositive();
    String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
    JsonNode m =
        call("GET", VIEWER, "/metrics/picking-lines?from=" + today + "&to=" + today, null, null)
            .body();
    JsonNode row = null;
    for (JsonNode it : m.path("items")) {
      if (MFC.equals(it.path("mfcId").asString()) && today.equals(it.path("date").asString())) {
        row = it;
      }
    }
    assertThat(row).as(m.toString()).isNotNull();
    assertThat(row.path("shortLines").asInt()).isPositive();
    assertThat(row.path("shortLinesByReason").path("DAMAGED").asInt()).isPositive();
    assertThat(row.path("shortLinesByReason").has("SPOILED")).isTrue(); // 0.22.0
    assertThat(row.path("pickedLines").asInt())
        .isGreaterThanOrEqualTo(row.path("shortLines").asInt());
    // 다른 센터 운영자는 이 센터를 못 본다
    String outsider = TestJwt.operator("v9", List.of(), List.of("MFC-ELSE"));
    assertThat(
            call(
                    "GET",
                    outsider,
                    "/metrics/picking-lines?from=" + today + "&to=" + today + "&mfcId=" + MFC,
                    null,
                    null)
                .status())
        .isEqualTo(403);
    // 센터 결품 목록(0.21.0, OE-1): 보고 줄마다 — 작업·주문·상품·LOT·칸·보고자, 전체 건수, 다른 센터 403
    JsonNode sl =
        call("GET", VIEWER, "/shortages?mfcId=" + MFC + "&reason=DAMAGED", null, null).body();
    JsonNode mine = null;
    for (JsonNode it : sl.path("items")) {
      if (pick.equals(it.path("taskId").asString())) {
        mine = it;
      }
    }
    assertThat(mine).as(sl.toString()).isNotNull();
    assertThat(mine.path("reason").asString()).isEqualTo("DAMAGED");
    assertThat(mine.path("taskType").asString()).isEqualTo("PICK");
    assertThat(mine.path("orderId").asString()).isEqualTo(o);
    assertThat(mine.path("sku").asString()).isNotBlank();
    assertThat(mine.path("reportedBy").asString()).isEqualTo("w1");
    assertThat(mine.path("locationCode").asString()).isNotBlank();
    assertThat(sl.path("total").asInt()).isGreaterThanOrEqualTo(sl.path("items").size());
    assertThat(
            call("GET", VIEWER, "/shortages?mfcId=" + MFC + "&reason=SHORT", null, null)
                .body()
                .path("items")
                .findValuesAsString("taskId"))
        .doesNotContain(pick);
    assertThat(call("GET", outsider, "/shortages?mfcId=" + MFC, null, null).status())
        .isEqualTo(403);
    assertThat(call("GET", VIEWER, "/shortages?mfcId=" + MFC + "&cursor=x", null, null).status())
        .isEqualTo(400);
    // 출하 약속 요약(0.21.0, OE-2): 열린 PICK 출고가 넷 중 하나로 세진다, 임박 기본 15분
    JsonNode ps = call("GET", VIEWER, "/shipments/promise-summary?mfcId=" + MFC, null, null).body();
    assertThat(ps.path("dueWithinMinutes").asInt()).isEqualTo(15);
    assertThat(
            ps.path("overdue").asInt()
                + ps.path("dueSoon").asInt()
                + ps.path("onTime").asInt()
                + ps.path("noPromise").asInt())
        .as(ps.toString())
        .isPositive();
    assertThat(
            call("GET", outsider, "/shipments/promise-summary?mfcId=" + MFC, null, null).status())
        .isEqualTo(403);
    assertThat(
            call(
                    "GET",
                    VIEWER,
                    "/shipments/promise-summary?mfcId=" + MFC + "&dueWithinMinutes=0",
                    null,
                    null)
                .status())
        .isEqualTo(400);
  }

  /** 피킹 뒤 전체 중지(IV-08, ADR-0046): 작업 종료·출고 취소 → 재고 취소 복귀 → RESOLVED → RETURN 작업으로 선반 복귀. */
  @Test
  void stopAfterPickClosesWorkAndReturnsPickedToShelf() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    String ret = s + "-RETURN";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    inventoryCommits(
        "PICK", 201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1));
    Res picked =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            Map.of(
                "executionType",
                "PICK",
                "workerId",
                "w1",
                "fromLocation",
                "A-S1-01-1-01",
                "toLocation",
                "PAK-A-01",
                "lines",
                List.of(
                    Map.of(
                        "orderLineId",
                        "L1",
                        "sku",
                        "E-00000001",
                        "lotId",
                        "LOT-A",
                        "pickedQty",
                        1))));
    assertThat(picked.body().path("status").asString()).isEqualTo("APPLIED");

    // 재고 결과 모름(500) → PENDING(작업은 이미 종료, 복귀 작업은 대기)
    String cancelPath = "/api/v1/internal/reservations/RSV-" + o + "/cancel-returns";
    inv.stubFor(WireMock.post(urlPathEqualTo(cancelPath)).willReturn(json(500, Map.of())));
    String key = UUID.randomUUID().toString();
    Map<String, Object> stopBody =
        Map.of(
            "stopRequestId",
            "STOP-" + o,
            "orderId",
            o,
            "scope",
            "FULL",
            "lines",
            List.of(Map.of("orderLineId", "L1", "qty", 2), Map.of("orderLineId", "L2", "qty", 1)));
    Res pending = call("POST", SVC, "/internal/stop-requests", key, stopBody);
    assertThat(pending.status()).as(pending.body().toString()).isEqualTo(200);
    assertThat(pending.body().path("state").asString()).isEqualTo("PENDING");
    assertThat(call("GET", VIEWER, "/tasks/" + pick, null, null).body().path("state").asString())
        .isEqualTo("CLOSED_PARTIAL");
    assertThat(
            call("GET", VIEWER, "/tasks/" + s + "-PACK", null, null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM shipment WHERE shipment_id = ?", String.class, s))
        .isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE task_id = ?", String.class, ret))
        .isEqualTo("WAITING");
    assertThat(outbox("WorkStopResult", o)).isZero();
    // 중지 뒤 집품 실적은 받지 않는다(작업 종료)
    assertThat(
            call(
                    "POST",
                    W1,
                    "/tasks/" + pick + "/executions",
                    UUID.randomUUID().toString(),
                    Map.of(
                        "executionType",
                        "PICK",
                        "workerId",
                        "w1",
                        "fromLocation",
                        "A-S1-02-1-01",
                        "toLocation",
                        "PAK-A-01",
                        "lines",
                        List.of(
                            Map.of(
                                "orderLineId",
                                "L1",
                                "sku",
                                "E-00000001",
                                "lotId",
                                "LOT-B",
                                "pickedQty",
                                1))))
                .status())
        .isEqualTo(409);

    // 재고 반영 → 같은 키로 다시 → RESOLVED, 포장 전 단위 판정(집지 않은 양·집은 양) STOPPED, RETURN READY
    inv.stubFor(
        WireMock.post(urlPathEqualTo(cancelPath))
            .willReturn(
                json(200, Map.of("status", "APPLIED", "resultId", "RSV-" + o, "appliedQty", 3))));
    Res done = call("POST", SVC, "/internal/stop-requests", key, stopBody);
    assertThat(done.body().path("state").asString())
        .as(done.body().toString())
        .isEqualTo("RESOLVED");
    JsonNode pkg = done.body().path("packages").path(0);
    assertThat(pkg.path("verdict").asString()).isEqualTo("STOPPED");
    assertThat(pkg.path("shipmentId").asString()).isEqualTo(s);
    assertThat(pkg.path("stoppedLines").toString())
        .contains("NOT_PICKED")
        .contains("\"workStateAtStop\":\"PICKED\"");
    assertThat(done.body().path("notStartedLines")).isEmpty();
    inv.verify(
        postRequestedFor(urlPathEqualTo(cancelPath))
            .withHeader("Idempotency-Key", equalTo("STOP-" + o))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("CANCEL_AFTER_PICK")))
            .withRequestBody(matchingJsonPath("$.returns[0].locationCode", equalTo("PAK-A-01")))
            .withRequestBody(matchingJsonPath("$.returns[0].workState", equalTo("PICKED")))
            .withRequestBody(matchingJsonPath("$.returns[0].lotId", equalTo("LOT-A"))));
    assertThat(outbox("WorkStopResult", o)).isEqualTo(1);
    assertThat(
            call("GET", SVC, "/internal/stop-requests/STOP-" + o, null, null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("RESOLVED");

    // 복귀 작업: 배정 → 시작 → 지시(포장 위치·LOT) → 남은 양 초과 409 → 선반으로 옮김(재고 MOVE) → COMPLETED
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE task_id = ?", String.class, ret))
        .isEqualTo("READY");
    assigned(ret, "w2", 1);
    assertThat(start(W2, ret, "w2", 1).status()).isEqualTo(201);
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/placement-suggestions"))
            .willReturn(
                json(200, Map.of("locationCodes", List.of("A-S1-01-1-01", "B-S9-01-1-01")))));
    JsonNode rd = call("GET", VIEWER, "/tasks/" + ret, null, null).body();
    assertThat(rd.path("taskType").asString()).isEqualTo("RETURN");
    // 복귀 추천 칸(#59) — 그 상품이 있던 칸이 앞
    assertThat(
            rd.path("lines")
                .path(0)
                .path("picks")
                .path(0)
                .path("suggestedToLocations")
                .path(0)
                .asString())
        .isEqualTo("A-S1-01-1-01");
    JsonNode rp = rd.path("lines").path(0).path("picks").path(0);
    assertThat(rp.path("lotId").asString()).isEqualTo("LOT-A");
    assertThat(rp.path("remainingQty").asInt()).isEqualTo(1);
    assertThat(rp.path("locations").path(0).path("locationCode").asString()).isEqualTo("PAK-A-01");
    // PICK 실적은 RETURN 작업에 못 넣는다
    assertThat(
            call(
                    "POST",
                    W2,
                    "/tasks/" + ret + "/executions",
                    UUID.randomUUID().toString(),
                    returnBody("PICK", 1))
                .status())
        .isEqualTo(422);
    assertThat(
            call(
                    "POST",
                    W2,
                    "/tasks/" + ret + "/executions",
                    UUID.randomUUID().toString(),
                    returnBody("RETURN", 2))
                .body()
                .path("code")
                .asString())
        .isEqualTo("QTY_EXCEEDS_REMAINING");
    inventoryCommits(
        "MOVE", 201, Map.of("status", "APPLIED", "resultId", "CMT-M1", "appliedQty", 1));
    Res moved =
        call(
            "POST",
            W2,
            "/tasks/" + ret + "/executions",
            UUID.randomUUID().toString(),
            returnBody("RETURN", 1));
    assertThat(moved.status()).as(moved.body().toString()).isEqualTo(202);
    assertThat(moved.body().path("status").asString()).isEqualTo("APPLIED");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("MOVE")))
            .withRequestBody(matchingJsonPath("$.fromLocation", equalTo("PAK-A-01")))
            .withRequestBody(matchingJsonPath("$.toLocation", equalTo("A-S1-01-1-01")))
            .withRequestBody(matchingJsonPath("$.lines[0].lotId", equalTo("LOT-A"))));
    assertThat(call("GET", VIEWER, "/tasks/" + ret, null, null).body().path("state").asString())
        .isEqualTo("COMPLETED");
  }

  static Map<String, Object> pickBody(
      String from, @Nullable String to, String line, String sku, String lot) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("executionType", "PICK");
    b.put("workerId", "w1");
    b.put("fromLocation", from);
    if (to != null) {
      b.put("toLocation", to);
    }
    b.put("lines", List.of(Map.of("orderLineId", line, "sku", sku, "lotId", lot, "pickedQty", 1)));
    return b;
  }

  /**
   * 피킹 도착지 = 장바구니(2026-11-17 PDA 요청 P1·P2·P4): 도착지 없으면 400, 한 출고 = 한 장바구니(409 CART_MISMATCH), 포장은
   * 장바구니 → 패킹대(재고 PACK from 장바구니·to 패킹대), 중지하면 포장 전 양은 장바구니에서·포장분은 패킹대에서 복귀.
   */
  @Test
  void pickIntoCartThenPackAtStationAndStopReturnsFromWhereUnitsAre() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    String pack = s + "-PACK";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    inventoryCommits(
        "PICK", 201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1));

    Res noCart =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            pickBody("A-S1-01-1-01", null, "L1", "E-00000001", "LOT-A"));
    assertThat(noCart.status()).isEqualTo(400);
    assertThat(noCart.body().path("code").asString()).isEqualTo("TO_LOCATION_REQUIRED");

    Res first =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            pickBody("A-S1-01-1-01", "CRT-A-01", "L1", "E-00000001", "LOT-A"));
    assertThat(first.body().path("status").asString())
        .as(first.body().toString())
        .isEqualTo("APPLIED");
    Res otherCart =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/executions",
            UUID.randomUUID().toString(),
            pickBody("A-S1-02-1-01", "CRT-A-02", "L1", "E-00000001", "LOT-B"));
    assertThat(otherCart.status()).isEqualTo(409);
    assertThat(otherCart.body().path("code").asString()).isEqualTo("CART_MISMATCH");
    for (var b :
        List.of(
            pickBody("A-S1-02-1-01", "CRT-A-01", "L1", "E-00000001", "LOT-B"),
            pickBody("C-S1-01-1-01", "CRT-A-01", "L2", "E-00000002", "LOT-C"))) {
      Res ok = call("POST", W1, "/tasks/" + pick + "/executions", UUID.randomUUID().toString(), b);
      assertThat(ok.body().path("status").asString()).as(ok.body().toString()).isEqualTo("APPLIED");
    }
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("PICK")))
            .withRequestBody(matchingJsonPath("$.toLocation", equalTo("CRT-A-01"))));

    // 장바구니를 찍어 주문 불러오기(0.25.0, R20-5): 그 장바구니로 집품한 출고의 PACK(상태 기본 = 끝나지 않은 것)
    List<String> onCart =
        call("GET", W2, "/tasks?mfcId=" + MFC + "&type=PACK&cartCode=CRT-A-01&size=200", null, null)
            .body()
            .path("items")
            .findValuesAsString("taskId");
    assertThat(onCart).contains(pack);
    // 거절된 장바구니(409 CART_MISMATCH)는 연결되지 않는다
    assertThat(
            call("GET", W2, "/tasks?mfcId=" + MFC + "&cartCode=CRT-A-02&size=200", null, null)
                .body()
                .path("items")
                .findValuesAsString("taskId"))
        .doesNotContain(pack, pick);

    // 포장: 집품분 위치 = 장바구니, 패킹대를 찍어 밀봉 → 재고 PACK 은 장바구니 → 패킹대
    assigned(pack, "w2", 1);
    assertThat(start(W2, pack, "w2", 1).status()).isEqualTo(201);
    // 줄 온도대(0.25.0, R19-2) — 재고 SKU 사본 storageType
    skuZones(Map.of("E-00000001", "CHILLED", "E-00000002", "AMBIENT"));
    JsonNode packDetail = call("GET", VIEWER, "/tasks/" + pack, null, null).body();
    assertThat(line(packDetail, "L1").path("temperatureZone").asString()).isEqualTo("CHILLED");
    assertThat(line(packDetail, "L2").path("temperatureZone").asString()).isEqualTo("AMBIENT");
    assertThat(
            call("GET", VIEWER, "/tasks/" + pack, null, null)
                .body()
                .path("packingLocations")
                .toString())
        .contains("CRT-A-01");
    plan(o, 1, 1, 0, 0, 1, 0);
    inventoryCommits(
        "PACK", 201, Map.of("status", "APPLIED", "resultId", "CMT-P", "appliedQty", 2));
    Res p1 =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "icePackCount",
                2,
                "packingLocation",
                "PAK-A-01",
                "contents",
                List.of(Map.of("orderLineId", "L1", "sku", "E-00000001", "qty", 2))));
    assertThat(p1.status()).as(p1.body().toString()).isEqualTo(201);
    // 아이스팩 수(0.25.0, R19-9) — 밀봉 응답·단건 조회
    assertThat(p1.body().path("icePackCount").asInt()).isEqualTo(2);
    assertThat(
            call("GET", VIEWER, "/packages/" + p1.body().path("packageId").asString(), null, null)
                .body()
                .path("icePackCount")
                .asInt())
        .isEqualTo(2);
    assertThat(
            jdbc.queryForMap(
                "SELECT packing_location, source_location FROM package WHERE shipment_id = ?", s))
        .containsEntry("packing_location", "PAK-A-01")
        .containsEntry("source_location", "CRT-A-01");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("PACK")))
            .withRequestBody(matchingJsonPath("$.fromLocation", equalTo("CRT-A-01")))
            .withRequestBody(matchingJsonPath("$.toLocation", equalTo("PAK-A-01"))));

    // 중지: L2(포장 전)는 장바구니 PICKED, L1 포장분은 패킹대 PACKED
    String cancelPath = "/api/v1/internal/reservations/RSV-" + o + "/cancel-returns";
    inv.stubFor(
        WireMock.post(urlPathEqualTo(cancelPath))
            .willReturn(
                json(200, Map.of("status", "APPLIED", "resultId", "RSV-" + o, "appliedQty", 3))));
    Res done =
        call(
            "POST",
            SVC,
            "/internal/stop-requests",
            UUID.randomUUID().toString(),
            Map.of(
                "stopRequestId",
                "STOP-" + o,
                "orderId",
                o,
                "scope",
                "FULL",
                "lines",
                List.of(
                    Map.of("orderLineId", "L1", "qty", 2), Map.of("orderLineId", "L2", "qty", 1))));
    assertThat(done.body().path("state").asString())
        .as(done.body().toString())
        .isEqualTo("RESOLVED");
    inv.verify(
        postRequestedFor(urlPathEqualTo(cancelPath))
            .withRequestBody(matchingJsonPath("$.returns[0].orderLineId", equalTo("L2")))
            .withRequestBody(matchingJsonPath("$.returns[0].locationCode", equalTo("CRT-A-01")))
            .withRequestBody(matchingJsonPath("$.returns[0].workState", equalTo("PICKED")))
            .withRequestBody(matchingJsonPath("$.returns[1].locationCode", equalTo("PAK-A-01")))
            .withRequestBody(matchingJsonPath("$.returns[1].workState", equalTo("PACKED")))
            .withRequestBody(matchingJsonPath("$.returns[2].locationCode", equalTo("PAK-A-01"))));

    // 복귀(0.25.0): L1 = 냉장(CHILLED) → 보낸 값과 상관없이 QUARANTINED(R19-8). 예외(0.26.0, R19-7): L2 SHORT →
    // 처리한 양, L1 DAMAGED → 격리 MOVE
    String ret = s + "-RETURN";
    assigned(ret, "w2", 1);
    assertThat(start(W2, ret, "w2", 1).status()).isEqualTo(201);
    JsonNode rd = call("GET", VIEWER, "/tasks/" + ret, null, null).body();
    assertThat(line(rd, "L1").path("temperatureZone").asString()).isEqualTo("CHILLED");

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("executionType", "RETURN");
    report.put("workerId", "w2");
    report.put("fromLocation", "CRT-A-01");
    report.put(
        "lines",
        List.of(
            Map.of(
                "orderLineId",
                "L2",
                "sku",
                "E-00000002",
                "lotId",
                "LOT-C",
                "pickedQty",
                0,
                "shortage",
                Map.of("qty", 2, "reason", "SHORT"))));
    Res tooMany =
        call("POST", W2, "/tasks/" + ret + "/executions", UUID.randomUUID().toString(), report);
    assertThat(tooMany.status()).isEqualTo(409);
    assertThat(tooMany.body().path("code").asString()).isEqualTo("QTY_EXCEEDS_REMAINING");
    report.put(
        "lines",
        List.of(
            Map.of(
                "orderLineId",
                "L2",
                "sku",
                "E-00000002",
                "lotId",
                "LOT-C",
                "pickedQty",
                0,
                "shortage",
                Map.of("qty", 1, "reason", "SHORT", "note", "장바구니에 없음"))));
    String reportKey = UUID.randomUUID().toString();
    Res reported = call("POST", W2, "/tasks/" + ret + "/executions", reportKey, report);
    assertThat(reported.status()).as(reported.body().toString()).isEqualTo(202);
    assertThat(reported.body().path("status").asString()).isEqualTo("APPLIED");
    // SHORT(0.26.0): 재고 호출 없이 처리한 양으로 센다 — 남은 복귀량이 줄고, 위치 재고는 관리자 재고 정정
    inv.verify(
        0,
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("MOVE"))));
    JsonNode afterReport = call("GET", VIEWER, "/tasks/" + ret, null, null).body();
    assertThat(afterReport.path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(line(afterReport, "L2").path("remainingQty").asInt()).isZero();
    JsonNode lastExec =
        call("GET", VIEWER, "/tasks/" + ret + "/executions", null, null)
            .body()
            .path("items")
            .path(0);
    assertThat(lastExec.path("shortages").path(0).path("reason").asString()).isEqualTo("SHORT");
    assertThat(lastExec.path("appliedQty").asInt()).isEqualTo(1);
    // 같은 키 다시 — 같은 실행, 두 번 세지 않는다
    Res again = call("POST", W2, "/tasks/" + ret + "/executions", reportKey, report);
    assertThat(again.body().path("executionId").asString())
        .isEqualTo(reported.body().path("executionId").asString());
    assertThat(
            jdbc.queryForObject(
                "SELECT cancelled_qty FROM task_line WHERE task_id = ? AND order_line_id = 'L2'",
                Integer.class,
                ret))
        .isEqualTo(1);

    // 냉장 L1 을 NORMAL 로 보내도 재고 MOVE 는 QUARANTINED
    inventoryCommits(
        "MOVE", 201, Map.of("status", "APPLIED", "resultId", "CMT-M1", "appliedQty", 1));
    Map<String, Object> move = new LinkedHashMap<>();
    move.put("executionType", "RETURN");
    move.put("workerId", "w2");
    move.put("fromLocation", "PAK-A-01");
    move.put("toLocation", "A-S1-01-1-01");
    move.put(
        "lines",
        List.of(
            Map.of(
                "orderLineId",
                "L1",
                "sku",
                "E-00000001",
                "lotId",
                "LOT-A",
                "pickedQty",
                1,
                "resultQuality",
                "NORMAL")));
    Res moved =
        call("POST", W2, "/tasks/" + ret + "/executions", UUID.randomUUID().toString(), move);
    assertThat(moved.body().path("status").asString())
        .as(moved.body().toString())
        .isEqualTo("APPLIED");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("MOVE")))
            .withRequestBody(matchingJsonPath("$.lines[0].lotId", equalTo("LOT-A")))
            .withRequestBody(matchingJsonPath("$.lines[0].resultQuality", equalTo("QUARANTINED"))));

    // 온도대를 모르면(재고 SKU 조회 503) 기록하지 않고 503 — 판매 가능으로 되돌리지 않는다
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(aResponse().withStatus(503)));
    int before =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM task_execution WHERE task_id = ?", Integer.class, ret);
    move.put(
        "lines",
        List.of(
            Map.of("orderLineId", "L1", "sku", "E-00000001", "lotId", "LOT-B", "pickedQty", 1)));
    Res down =
        call("POST", W2, "/tasks/" + ret + "/executions", UUID.randomUUID().toString(), move);
    assertThat(down.status()).as(down.body().toString()).isEqualTo(503);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE task_id = ?", Integer.class, ret))
        .isEqualTo(before);

    // DAMAGED(0.26.0): 격리로 둘 선반이 없으면 400, 있으면 같은 MOVE 를 QUARANTINED 로 그 선반에
    Map<String, Object> damaged = new LinkedHashMap<>();
    damaged.put("executionType", "RETURN");
    damaged.put("workerId", "w2");
    damaged.put("fromLocation", "PAK-A-01");
    damaged.put(
        "lines",
        List.of(
            Map.of(
                "orderLineId",
                "L1",
                "sku",
                "E-00000001",
                "lotId",
                "LOT-B",
                "pickedQty",
                0,
                "shortage",
                Map.of("qty", 1, "reason", "DAMAGED"))));
    Res noShelf =
        call("POST", W2, "/tasks/" + ret + "/executions", UUID.randomUUID().toString(), damaged);
    assertThat(noShelf.status()).as(noShelf.body().toString()).isEqualTo(400);
    assertThat(noShelf.body().path("code").asString()).isEqualTo("TO_LOCATION_REQUIRED");
    damaged.put("toLocation", "A-S9-01-1-01");
    Res quarantined =
        call("POST", W2, "/tasks/" + ret + "/executions", UUID.randomUUID().toString(), damaged);
    assertThat(quarantined.body().path("status").asString())
        .as(quarantined.body().toString())
        .isEqualTo("APPLIED");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("MOVE")))
            .withRequestBody(matchingJsonPath("$.fromLocation", equalTo("PAK-A-01")))
            .withRequestBody(matchingJsonPath("$.toLocation", equalTo("A-S9-01-1-01")))
            .withRequestBody(matchingJsonPath("$.lines[0].lotId", equalTo("LOT-B")))
            .withRequestBody(matchingJsonPath("$.lines[0].qty", equalTo("1")))
            .withRequestBody(matchingJsonPath("$.lines[0].resultQuality", equalTo("QUARANTINED"))));
    assertThat(quarantined.body().path("appliedQty").asInt()).isEqualTo(1);
    // 모두 처리(옮김 1 + 격리 1 + 없음 1) → 복귀 작업 완료
    JsonNode finished = call("GET", VIEWER, "/tasks/" + ret, null, null).body();
    assertThat(finished.path("state").asString()).as(finished.toString()).isEqualTo("COMPLETED");
    assertThat(line(finished, "L1").path("remainingQty").asInt()).isZero();

    // 센터 결품 목록에 복귀 예외(taskType RETURN) — 관리자가 재고 정정할 대상이 보인다. 고객 결품 사건은 없다
    List<JsonNode> returnRows = new java.util.ArrayList<>();
    call("GET", VIEWER, "/shortages?mfcId=" + MFC + "&size=200", null, null)
        .body()
        .path("items")
        .forEach(
            i -> {
              if (ret.equals(i.path("taskId").asString())) {
                returnRows.add(i);
              }
            });
    assertThat(returnRows).hasSize(2);
    assertThat(returnRows).allMatch(i -> "RETURN".equals(i.path("taskType").asString()));
    assertThat(returnRows.stream().map(i -> i.path("reason").asString()))
        .containsExactlyInAnyOrder("SHORT", "DAMAGED");
    JsonNode shortRow =
        returnRows.stream()
            .filter(i -> "SHORT".equals(i.path("reason").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(shortRow.path("locationCode").asString()).isEqualTo("CRT-A-01");
    assertThat(shortRow.path("lotId").asString()).isEqualTo("LOT-C");
    assertThat(shortRow.path("reportedBy").asString()).isEqualTo("w2");
    assertThat(outbox("LineShortageReported", o)).isZero();
  }

  /** 재고가 취소 복귀를 거절 → PENDING + lastError, 자동 재시도(sweep)는 다시 보내지 않음 → 운영자 재시도로 RESOLVED. */
  @Test
  void rejectedCancelReturnStopsUntilOperatorRetries() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    inventoryCommits(
        "PICK", 201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1));
    assertThat(
            call(
                    "POST",
                    W1,
                    "/tasks/" + pick + "/executions",
                    UUID.randomUUID().toString(),
                    returnLikePick())
                .body()
                .path("status")
                .asString())
        .isEqualTo("APPLIED");

    String cancelPath = "/api/v1/internal/reservations/RSV-" + o + "/cancel-returns";
    inv.stubFor(
        WireMock.post(urlPathEqualTo(cancelPath))
            .willReturn(json(409, Map.of("code", "RETURN_MISMATCH", "detail", "취소 복귀 수량이 다릅니다"))));
    Res stop =
        call(
            "POST",
            SVC,
            "/internal/stop-requests",
            UUID.randomUUID().toString(),
            Map.of(
                "stopRequestId",
                "STOP-" + o,
                "orderId",
                o,
                "scope",
                "FULL",
                "lines",
                List.of(
                    Map.of("orderLineId", "L1", "qty", 2), Map.of("orderLineId", "L2", "qty", 1))));
    assertThat(stop.body().path("state").asString()).isEqualTo("PENDING");
    assertThat(stop.body().path("lastError").asString()).startsWith("RETURN_MISMATCH");
    inv.verify(1, postRequestedFor(urlPathEqualTo(cancelPath)));

    // 자동 재시도는 실패로 멈춘 건을 다시 보내지 않는다
    stops.sweepPending();
    stops.sweepPending();
    inv.verify(1, postRequestedFor(urlPathEqualTo(cancelPath)));

    // 처리 대기 목록(0.9.0, F-4): OPERATOR_ADMIN 만, 실패 시각·센터·작업 지시
    JsonNode failed = failedStop("STOP-" + o);
    assertThat(failed.path("orderId").asString()).isEqualTo(o);
    assertThat(failed.path("mfcId").asString()).isEqualTo(MFC);
    assertThat(failed.path("fulfillmentId").asString()).isNotBlank();
    assertThat(failed.path("lastError").asString()).startsWith("RETURN_MISMATCH");
    assertThat(failed.path("failedAt").isNull()).isFalse();
    assertThat(failed.path("lastRetry").isNull()).isTrue();
    assertThat(call("GET", W1, "/stop-requests?failed=true", null, null).status()).isEqualTo(403);
    assertThat(call("GET", ADMIN, "/stop-requests?failed=false", null, null).status())
        .isEqualTo(400);
    assertThat(
            call("GET", ADMIN, "/stop-requests?failed=true&mfcId=MFC-OTHER", null, null)
                .body()
                .path("items"))
        .noneMatch(i -> i.path("stopRequestId").asString().equals("STOP-" + o));

    // 운영자 재시도가 다시 실패 → 목록에 남고, 운영자 ID·메모가 lastRetry 로
    Res again =
        call(
            "POST",
            SVC,
            "/internal/stop-requests/STOP-" + o + "/retries",
            UUID.randomUUID().toString(),
            Map.of("operatorId", "OP-admin", "reason", "재고 확인 전 시도"));
    assertThat(again.body().path("lastError").asString()).startsWith("RETURN_MISMATCH");
    JsonNode stillFailed = failedStop("STOP-" + o);
    assertThat(stillFailed.path("lastRetry").path("operatorId").asString()).isEqualTo("OP-admin");
    assertThat(stillFailed.path("lastRetry").path("reason").asString()).isEqualTo("재고 확인 전 시도");
    inv.verify(2, postRequestedFor(urlPathEqualTo(cancelPath)));

    // 원인을 고친 뒤 운영자 재시도 → RESOLVED, 사유 지워짐, 같은 키로 재고에 다시
    inv.stubFor(
        WireMock.post(urlPathEqualTo(cancelPath))
            .willReturn(
                json(200, Map.of("status", "APPLIED", "resultId", "RSV-" + o, "appliedQty", 3))));
    Res retried =
        call(
            "POST",
            SVC,
            "/internal/stop-requests/STOP-" + o + "/retries",
            UUID.randomUUID().toString(),
            null);
    assertThat(retried.status()).as(retried.body().toString()).isEqualTo(200);
    assertThat(retried.body().path("state").asString()).isEqualTo("RESOLVED");
    assertThat(retried.body().path("lastError").isNull()).isTrue();
    // 처리되면 목록에서 빠진다. 본문 없는 재시도도 기록(운영자 ID·메모 없이) — 실제로 다시 시도했으므로
    assertThat(call("GET", ADMIN, "/stop-requests?failed=true", null, null).body().path("items"))
        .noneMatch(i -> i.path("stopRequestId").asString().equals("STOP-" + o));
    assertThat(retries("STOP_REQUEST", "STOP-" + o)).isEqualTo(2);
    inv.verify(
        3,
        postRequestedFor(urlPathEqualTo(cancelPath))
            .withHeader("Idempotency-Key", equalTo("STOP-" + o)));
    assertThat(outbox("WorkStopResult", o)).isEqualTo(1);
    // 끝난 뒤 다시 불러도 그대로
    assertThat(
            call(
                    "POST",
                    SVC,
                    "/internal/stop-requests/STOP-" + o + "/retries",
                    UUID.randomUUID().toString(),
                    null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("RESOLVED");
    // 끝난 뒤의 재시도는 다시 시도한 것이 아니라 기록하지 않는다
    assertThat(retries("STOP_REQUEST", "STOP-" + o)).isEqualTo(2);
    assertThat(call("POST", SVC, "/internal/stop-requests/STOP-NONE/retries", "k", null).status())
        .isEqualTo(404);
  }

  /** 처리 대기 목록에서 그 중지 요청(없으면 실패) — 다른 시험의 항목이 섞이므로 커서로 끝까지 본다. */
  JsonNode failedStop(String stopRequestId) {
    return findInList("/stop-requests?failed=true", "stopRequestId", stopRequestId);
  }

  JsonNode findInList(String path, String idField, String id) {
    String cursor = null;
    do {
      Res r =
          call(
              "GET",
              ADMIN,
              path + "&size=2" + (cursor == null ? "" : "&cursor=" + cursor),
              null,
              null);
      assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
      for (JsonNode i : r.body().path("items")) {
        if (i.path(idField).asString().equals(id)) {
          return i;
        }
      }
      JsonNode next = r.body().path("page").path("nextCursor");
      cursor = next.isNull() || next.isMissingNode() ? null : next.asString();
    } while (cursor != null);
    throw new AssertionError(id + " 가 " + path + " 목록에 없습니다");
  }

  static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false);
  }

  int retries(String type, String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM manual_retry WHERE target_type = ? AND target_id = ?",
        Integer.class,
        type,
        id);
  }

  // ---------- 라이더 인계(6c, ADR-0047) ----------

  static final String RIDER1 = TestJwt.rider("R1");
  static final String RIDER2 = TestJwt.rider("R2");

  record Pkg(String id, String qr) {}

  /** 피킹·포장까지 끝난 출고(포장 2개: L1 2개, L2 1개) — 출고 READY. */
  /** 피킹을 다 하고 PACK 을 시작한 출고(포장 전). */
  String packStarted(String o) {
    String s = accept(o);
    String pick = s + "-PICK";
    String pack = s + "-PACK";
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    inventoryCommits(
        "PICK", 201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1));
    for (var line :
        List.of(
            List.of("L1", "E-00000001", "LOT-A", "A-S1-01-1-01"),
            List.of("L1", "E-00000001", "LOT-B", "A-S1-02-1-01"),
            List.of("L2", "E-00000002", "LOT-C", "C-S1-01-1-01"))) {
      Res ok =
          call(
              "POST",
              W1,
              "/tasks/" + pick + "/executions",
              UUID.randomUUID().toString(),
              Map.of(
                  "executionType",
                  "PICK",
                  "workerId",
                  "w1",
                  "fromLocation",
                  line.get(3),
                  "toLocation",
                  "PAK-A-01",
                  "lines",
                  List.of(
                      Map.of(
                          "orderLineId",
                          line.get(0),
                          "sku",
                          line.get(1),
                          "lotId",
                          line.get(2),
                          "pickedQty",
                          1))));
      assertThat(ok.body().path("status").asString()).isEqualTo("APPLIED");
    }
    assigned(pack, "w2", 1);
    assertThat(start(W2, pack, "w2", 1).status()).isEqualTo(201);
    return s;
  }

  List<Pkg> readyShipment(String o) {
    String s = packStarted(o);
    plan(o, 1, 1, 0, 0, 1, 0);
    inventoryCommits(
        "PACK", 201, Map.of("status", "APPLIED", "resultId", "CMT-P", "appliedQty", 1));
    Res p1 =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "contents",
                List.of(Map.of("orderLineId", "L1", "sku", "E-00000001", "qty", 2))));
    plan(o, 1, 1, 1, 1, 1, 0);
    Res p2 =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "contents",
                List.of(Map.of("orderLineId", "L2", "sku", "E-00000002", "qty", 1))));
    assertThat(call("GET", VIEWER, "/shipments/" + s, null, null).body().path("state").asString())
        .isEqualTo("READY");
    return List.of(
        new Pkg(p1.body().path("packageId").asString(), p1.body().path("qrCode").asString()),
        new Pkg(p2.body().path("packageId").asString(), p2.body().path("qrCode").asString()));
  }

  void deliveryEvent(String type, String deliveryId, int version, Map<String, Object> payload) {
    Map<String, Object> pl = new LinkedHashMap<>(payload);
    pl.put("deliveryId", deliveryId);
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from("delivery-dispatch-service")
            .aggregate("Delivery", deliveryId, version)
            .payload(pl)
            .buildJson();
    kafka.send("delivery.delivery.v1", deliveryId, json).join();
  }

  /** 배송 생성 + 라이더 배정(투영 반영까지 기다림). */
  void assignRider(String o, String deliveryId, String rider, int version) {
    if (version == 1) {
      deliveryEvent(
          "DeliveryCreated",
          deliveryId,
          1,
          Map.of("orderId", o, "shipmentId", o + "-S1", "mfcId", MFC, "type", "FORWARD"));
      deliveryEvent(
          "DeliveryAssigned",
          deliveryId,
          2,
          Map.of(
              "orderId",
              o,
              "riderId",
              rider,
              "assignmentVersion",
              1,
              "acceptedAt",
              "2026-11-08T01:00:00Z"));
    } else {
      deliveryEvent(
          "DeliveryAssignmentChanged",
          deliveryId,
          version + 1,
          Map.of(
              "newRiderId",
              rider,
              "assignmentVersion",
              version,
              "reason",
              "REASSIGN",
              "changedAt",
              "2026-11-08T01:05:00Z"));
    }
    await(
        "SELECT COUNT(*) FROM delivery_assignment WHERE delivery_id = '"
            + deliveryId
            + "' AND state = 'ASSIGNED' AND rider_id = '"
            + rider
            + "' AND assignment_version = "
            + version
            + " AND shipment_id IS NOT NULL");
  }

  Res authorize(String token, Pkg p, String rider, String deliveryId, String qr) {
    return call(
        "POST",
        token,
        "/packages/" + p.id() + "/handover-authorizations",
        UUID.randomUUID().toString(),
        Map.of("riderId", rider, "deliveryId", deliveryId, "scannedCode", qr));
  }

  Res commit(String token, Pkg p, JsonNode auth, String rider) {
    return call(
        "POST",
        token,
        "/packages/" + p.id() + "/handover-commits",
        UUID.randomUUID().toString(),
        Map.of(
            "handoverAuthorizationId",
            auth.path("handoverAuthorizationId").asString(),
            "fenceVersion",
            auth.path("fenceVersion").asInt(),
            "riderId",
            rider));
  }

  Map<String, Object> fullStop(String o) {
    return Map.of(
        "stopRequestId",
        "STOP-" + o,
        "orderId",
        o,
        "scope",
        "FULL",
        "lines",
        List.of(Map.of("orderLineId", "L1", "qty", 2), Map.of("orderLineId", "L2", "qty", 1)));
  }

  /** R-29(0.27.0): 주문번호로 현장 상태·센터 출고 목록(지난 주문 — 날짜·최근 순). 번호 사본 12345678 이 있는 주문. */
  private void orderNumberLookups(String o, String shipmentId) {
    // 작업 지시 조회 — orderId 대신 번호(공백·하이픈 무시), 그 센터 직원도
    Res byNumber = call("GET", CS, "/work-orders?orderNumber=1234-5678", null, null);
    assertThat(byNumber.status()).as(byNumber.body().toString()).isEqualTo(200);
    assertThat(byNumber.body().path("orderId").asString()).isEqualTo(o);
    assertThat(call("GET", VIEWER, "/work-orders?orderNumber=12345678", null, null).status())
        .isEqualTo(200);
    assertThat(call("GET", OTHER_CENTER, "/work-orders?orderNumber=12345678", null, null).status())
        .isEqualTo(403);
    // 둘 다·둘 다 없음·8자리 아님 400, 사본 없는 번호 404
    assertThat(
            call("GET", CS, "/work-orders?orderId=" + o + "&orderNumber=12345678", null, null)
                .status())
        .isEqualTo(400);
    assertThat(call("GET", CS, "/work-orders", null, null).status()).isEqualTo(400);
    assertThat(call("GET", CS, "/work-orders?orderNumber=123", null, null).status()).isEqualTo(400);
    assertThat(call("GET", CS, "/work-orders?orderNumber=99999998", null, null).status())
        .isEqualTo(404);

    // 센터 출고 목록 — 번호로 그 주문 출고만
    JsonNode one =
        call("GET", VIEWER, "/shipments?mfcId=" + MFC + "&orderNumber=12345678", null, null).body();
    assertThat(one.path("items").findValuesAsString("shipmentId")).containsExactly(shipmentId);
    assertThat(
            call("GET", VIEWER, "/shipments?mfcId=" + MFC + "&orderNumber=99999998", null, null)
                .body()
                .path("items"))
        .isEmpty();
    // 오늘(서울) 만든 출고, 최근 순 — 커서로 넘겨도 만든 시각이 줄어드는 순서, 이 출고가 있다
    String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
    List<String> created = new java.util.ArrayList<>();
    List<String> ids = new java.util.ArrayList<>();
    String cursor = null;
    do {
      JsonNode page =
          call(
                  "GET",
                  VIEWER,
                  "/shipments?mfcId="
                      + MFC
                      + "&createdTo="
                      + today
                      + "&sort=recent&size=2"
                      + (cursor == null ? "" : "&cursor=" + cursor),
                  null,
                  null)
              .body();
      for (JsonNode it : page.path("items")) {
        created.add(it.path("createdAt").asString());
        ids.add(it.path("shipmentId").asString());
      }
      cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString(null);
    } while (cursor != null);
    assertThat(ids).contains(shipmentId).doesNotHaveDuplicates();
    List<java.time.Instant> times =
        created.stream().map(t -> java.time.OffsetDateTime.parse(t).toInstant()).toList();
    assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    // 잘못된 값 400
    assertThat(call("GET", VIEWER, "/shipments?mfcId=" + MFC + "&sort=newest", null, null).status())
        .isEqualTo(400);
    assertThat(
            call(
                    "GET",
                    VIEWER,
                    "/shipments?mfcId=" + MFC + "&createdFrom=2026-01-01&createdTo=2026-03-01",
                    null,
                    null)
                .status())
        .isEqualTo(400);
  }

  /** 배정 라이더만 QR 인계 → 재고 인계 전기 → 포장 HANDED_OVER·출고 부분/전체 인계, 인계된 주문의 전체 중지는 거절. */
  /** F-5·F-8(0.13.0): 화면용 주문번호 사본, 라벨 숫자로 인계 허가(QR 과 같은 허가). */
  @Test
  void labelNumbersShowOrderNumberAndHandoverByCode() throws Exception {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    List<Pkg> pkgs = readyShipment(o);
    Pkg p1 = pkgs.get(0);
    String dlv = "DLV-" + o;
    String s = o + "-S1";
    // 주문 확정 사건 → 화면용 주문번호(작업 지시보다 늦게 와도 된다)
    kafka
        .send(
            "order.order.v1",
            o,
            EventEnvelopeFixtures.envelope("OrderConfirmed")
                .from("order-service")
                .aggregate("Order", o, 2)
                .payload(Map.of("orderId", o, "orderNumber", "12345678"))
                .buildJson())
        .join();
    java.time.Instant until = java.time.Instant.now().plusSeconds(30);
    JsonNode shipment = call("GET", VIEWER, "/shipments/" + s, null, null).body();
    while (shipment.path("orderNumber").isNull() && java.time.Instant.now().isBefore(until)) {
      Thread.sleep(200);
      shipment = call("GET", VIEWER, "/shipments/" + s, null, null).body();
    }
    assertThat(shipment.path("orderNumber").asString()).isEqualTo("12345678");
    JsonNode pk = call("GET", VIEWER, "/packages/" + p1.id(), null, null).body();
    assertThat(pk.path("orderNumber").asString()).isEqualTo("12345678");
    String code = pk.path("handoverCode").asString();
    assertThat(code).matches("[0-9]{4}"); // 0.23.0 R-26
    // 같은 출고의 다른 포장과 번호가 겹치지 않는다(센터 안 OPEN·SEALED 끼리)
    assertThat(
            call("GET", VIEWER, "/packages/" + pkgs.get(1).id(), null, null)
                .body()
                .path("handoverCode")
                .asString())
        .matches("[0-9]{4}")
        .isNotEqualTo(code);
    assertThat(
            call("GET", W1, "/tasks/" + s + "-PICK", null, null)
                .body()
                .path("orderNumber")
                .asString())
        .isEqualTo("12345678");
    orderNumberLookups(o, s);

    // 숫자 인계: 배정 전 → DELIVERY_MISMATCH, 틀린 숫자 → HANDOVER_CODE_MISMATCH, 맞으면 QR 과 같은 허가
    Map<String, Object> byCode =
        new java.util.HashMap<>(Map.of("riderId", "R1", "deliveryId", dlv, "handoverCode", code));
    assertThat(
            call(
                    "POST",
                    RIDER1,
                    "/packages/handover-code-authorizations",
                    UUID.randomUUID().toString(),
                    byCode)
                .body()
                .path("code")
                .asString())
        .isEqualTo("DELIVERY_MISMATCH");
    assignRider(o, dlv, "R1", 1);
    String wrong = code.equals("0000") ? "0001" : "0000";
    Res bad =
        call(
            "POST",
            RIDER1,
            "/packages/handover-code-authorizations",
            UUID.randomUUID().toString(),
            Map.of("riderId", "R1", "deliveryId", dlv, "handoverCode", wrong));
    assertThat(bad.status()).isEqualTo(409);
    assertThat(bad.body().path("code").asString()).isEqualTo("HANDOVER_CODE_MISMATCH");
    assertThat(bad.body().toString()).contains("attemptsLeft");
    Res ok =
        call(
            "POST",
            RIDER1,
            "/packages/handover-code-authorizations",
            UUID.randomUUID().toString(),
            byCode);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
    assertThat(ok.body().path("packageId").asString()).isEqualTo(p1.id());
    // QR 로 다시 찍으면 같은 허가
    assertThat(
            authorize(RIDER1, p1, "R1", dlv, p1.qr())
                .body()
                .path("handoverAuthorizationId")
                .asString())
        .isEqualTo(ok.body().path("handoverAuthorizationId").asString());
    // 0.23.0(R-26): 같은 라이더·배송이 5번 틀리면 잠김 — 맞는 번호도 거절(QR·점원 확인만)
    String last = "";
    for (int i = 0; i < 4; i++) {
      last =
          call(
                  "POST",
                  RIDER1,
                  "/packages/handover-code-authorizations",
                  UUID.randomUUID().toString(),
                  Map.of("riderId", "R1", "deliveryId", dlv, "handoverCode", wrong))
              .body()
              .path("code")
              .asString();
    }
    assertThat(last).isEqualTo("HANDOVER_CODE_LOCKED");
    Res lockedOk =
        call(
            "POST",
            RIDER1,
            "/packages/handover-code-authorizations",
            UUID.randomUUID().toString(),
            byCode);
    assertThat(lockedOk.status()).isEqualTo(409);
    assertThat(lockedOk.body().path("code").asString()).isEqualTo("HANDOVER_CODE_LOCKED");
    // 다른 라이더 토큰 → 403
    assertThat(
            call(
                    "POST",
                    RIDER1,
                    "/packages/handover-code-authorizations",
                    UUID.randomUUID().toString(),
                    Map.of("riderId", "R2", "deliveryId", dlv, "handoverCode", code))
                .status())
        .isEqualTo(403);
  }

  /**
   * PDA 처럼 찍으며 한 바퀴(2026-11-20, R-26 확인): 피킹 — 지시 위치 라벨·상품 바코드·LOT 라벨(GS1-128 (10) = lotNo)을 찍어 대조한
   * 값으로 집품 → 포장 — 상품 바코드 대조 후 포장 2개 → 라벨(QR·4자리 번호) → 라이더는 하나는 QR, 하나는 번호로 인계 → 출고 HANDED_OVER.
   */
  @Test
  void pdaScanPickPackAndRiderHandoverByQrAndCode() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    String pack = s + "-PACK";
    Map<String, String> productLabel =
        Map.of("E-00000001", "8801234567893", "E-00000002", "8809876543210");
    inv.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .withQueryParam("skus", equalTo("E-00000001,E-00000002"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "sku",
                                "E-00000001",
                                "state",
                                "ACTIVE",
                                "productVersion",
                                1,
                                "barcodes",
                                List.of(productLabel.get("E-00000001"))),
                            Map.of(
                                "sku",
                                "E-00000002",
                                "state",
                                "ACTIVE",
                                "productVersion",
                                1,
                                "barcodes",
                                List.of(productLabel.get("E-00000002")))),
                        "missing",
                        List.of()))));
    plan(o, 0, 0, 0, 0, 0, 0);
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    inventoryCommits(
        "PICK", 201, Map.of("status", "APPLIED", "resultId", "CMT-1", "appliedQty", 1));

    // ---- 피킹: 지시를 받아 LOT 조각마다 위치·상품·LOT 를 찍는다 ----
    JsonNode pd = call("GET", W1, "/tasks/" + pick, null, null).body();
    int picked = 0;
    for (JsonNode line : pd.path("lines")) {
      String sku = line.path("sku").asString();
      List<String> barcodes = new java.util.ArrayList<>();
      line.path("barcodes").forEach(b -> barcodes.add(b.asString()));
      for (JsonNode part : line.path("picks")) {
        String loc = part.path("locations").path(0).path("locationCode").asString();
        assertThat(loc).as(part.toString()).isNotBlank();
        // 찍은 값(실물 라벨) — 위치 라벨, 상품 바코드, LOT 라벨(GS1-128)
        String scannedLoc = loc;
        String scannedProduct = productLabel.get(sku);
        String scannedLot =
            "(01)0" + scannedProduct + "(17)261231(10)" + part.path("lotNo").asString();
        // PDA 대조: 위치 = 지시 위치, 상품 바코드 ∈ 줄 바코드, (10) = 지시 lotNo, 다른 상품 바코드는 막힘
        assertThat(scannedLoc).isEqualTo(loc);
        assertThat(barcodes).as("상품 바코드 대조 " + sku).contains(scannedProduct);
        assertThat(barcodes).doesNotContain("8800000000000");
        String lotNoFromLabel = scannedLot.substring(scannedLot.indexOf("(10)") + 4);
        assertThat(lotNoFromLabel).isNotBlank().isEqualTo(part.path("lotNo").asString());
        Res ok =
            call(
                "POST",
                W1,
                "/tasks/" + pick + "/executions",
                UUID.randomUUID().toString(),
                Map.of(
                    "executionType",
                    "PICK",
                    "workerId",
                    "w1",
                    "fromLocation",
                    scannedLoc,
                    "toLocation",
                    "PAK-A-01",
                    "lines",
                    List.of(
                        Map.of(
                            "orderLineId",
                            line.path("orderLineId").asString(),
                            "sku",
                            sku,
                            "lotId",
                            part.path("lotId").asString(),
                            "pickedQty",
                            part.path("remainingQty").asInt()))));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(202);
        assertThat(ok.body().path("status").asString()).isEqualTo("APPLIED");
        picked += part.path("remainingQty").asInt();
      }
    }
    assertThat(picked).as("지시대로 다 집음(L1 2 + L2 1)").isEqualTo(3);

    // ---- 포장: 상품 바코드를 찍어 담고 포장 2개 ----
    assigned(pack, "w2", 1);
    assertThat(start(W2, pack, "w2", 1).status()).isEqualTo(201);
    plan(o, 1, 1, 0, 0, 1, 0);
    JsonNode kd = call("GET", W2, "/tasks/" + pack, null, null).body();
    assertThat(kd.path("lines").size()).isPositive();
    for (JsonNode line : kd.path("lines")) {
      List<String> barcodes = new java.util.ArrayList<>();
      line.path("barcodes").forEach(b -> barcodes.add(b.asString()));
      assertThat(barcodes).contains(productLabel.get(line.path("sku").asString()));
    }
    inventoryCommits(
        "PACK", 201, Map.of("status", "APPLIED", "resultId", "CMT-P", "appliedQty", 1));
    Res k1 =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "contents",
                List.of(Map.of("orderLineId", "L1", "sku", "E-00000001", "qty", 2))));
    assertThat(k1.status()).as(k1.body().toString()).isEqualTo(201);
    plan(o, 1, 1, 1, 1, 1, 0);
    Res k2 =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/packages",
            UUID.randomUUID().toString(),
            Map.of(
                "bagCount",
                1,
                "contents",
                List.of(Map.of("orderLineId", "L2", "sku", "E-00000002", "qty", 1))));
    assertThat(k2.status()).as(k2.body().toString()).isEqualTo(201);
    assertThat(call("GET", VIEWER, "/shipments/" + s, null, null).body().path("state").asString())
        .isEqualTo("READY");

    // ---- 라벨: QR 원문 + 4자리 번호(서로 다름) ----
    Pkg p1 = new Pkg(k1.body().path("packageId").asString(), k1.body().path("qrCode").asString());
    Pkg p2 = new Pkg(k2.body().path("packageId").asString(), k2.body().path("qrCode").asString());
    JsonNode label1 = call("GET", VIEWER, "/packages/" + p1.id(), null, null).body();
    JsonNode label2 = call("GET", VIEWER, "/packages/" + p2.id(), null, null).body();
    assertThat(label1.path("state").asString()).isEqualTo("SEALED");
    assertThat(p1.qr()).startsWith("PKG:" + p1.id() + ":");
    String code2 = label2.path("handoverCode").asString();
    assertThat(code2).matches("[0-9]{4}");
    assertThat(label1.path("handoverCode").asString()).matches("[0-9]{4}").isNotEqualTo(code2);

    // ---- 라이더: 포장 1 은 QR, 포장 2 는 QR 이 안 찍혀 번호 입력 ----
    String dlv = "DLV-" + o;
    assignRider(o, dlv, "R1", 1);
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/package-handovers"))
            .willReturn(
                json(201, Map.of("status", "APPLIED", "resultId", "CMT-H", "appliedQty", 1))));
    Res a1 = authorize(RIDER1, p1, "R1", dlv, p1.qr());
    assertThat(a1.status()).as(a1.body().toString()).isEqualTo(201);
    Res c1 = commit(RIDER1, p1, a1.body(), "R1");
    assertThat(c1.status()).as(c1.body().toString()).isEqualTo(201);
    assertThat(c1.body().path("shipmentState").asString()).isEqualTo("PARTIALLY_HANDED_OVER");
    Res a2 =
        call(
            "POST",
            RIDER1,
            "/packages/handover-code-authorizations",
            UUID.randomUUID().toString(),
            Map.of("riderId", "R1", "deliveryId", dlv, "handoverCode", code2));
    assertThat(a2.status()).as(a2.body().toString()).isEqualTo(201);
    assertThat(a2.body().path("packageId").asString()).isEqualTo(p2.id());
    Res c2 = commit(RIDER1, p2, a2.body(), "R1");
    assertThat(c2.status()).as(c2.body().toString()).isEqualTo(201);
    assertThat(c2.body().path("packageState").asString()).isEqualTo("HANDED_OVER");
    assertThat(c2.body().path("shipmentState").asString()).isEqualTo("HANDED_OVER");
    assertThat(outbox("PackageHandedOver", p2.id())).isEqualTo(1);
    // 2026-11-20(라이더 앱 실서버): 인계 뒤 같은 번호를 다시 넣으면 ALREADY_HANDED_OVER — 틀린 횟수에 넣지 않는다
    for (int i = 0; i < 6; i++) {
      Res again =
          call(
              "POST",
              RIDER1,
              "/packages/handover-code-authorizations",
              UUID.randomUUID().toString(),
              Map.of("riderId", "R1", "deliveryId", dlv, "handoverCode", code2));
      assertThat(again.status()).isEqualTo(409);
      assertThat(again.body().path("code").asString()).isEqualTo("ALREADY_HANDED_OVER");
    }
  }

  @Test
  void assignedRiderHandsOverPackagesAndStopIsRefusedAfterHandover() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    List<Pkg> pkgs = readyShipment(o);
    Pkg p1 = pkgs.get(0);
    Pkg p2 = pkgs.get(1);
    String dlv = "DLV-" + o;

    // 배정 전에는 그 배송의 포장이 아니다
    assertThat(authorize(RIDER1, p1, "R1", dlv, p1.qr()).body().path("code").asString())
        .isEqualTo("DELIVERY_MISMATCH");
    JsonNode noDelivery =
        call("GET", VIEWER, "/shipments/" + o + "-S1", null, null).body().path("deliveryId");
    assertThat(noDelivery.isNull() || noDelivery.isMissingNode()).isTrue();
    assignRider(o, dlv, "R1", 1);
    // R-19(0.24.0): 배송이 생기면(DeliveryCreated 투영) 인계 전에도 출고에 배송 ID
    assertThat(
            call("GET", VIEWER, "/shipments/" + o + "-S1", null, null)
                .body()
                .path("deliveryId")
                .asString())
        .isEqualTo(dlv);
    // 운영자 토큰·다른 라이더·무효 QR 은 막는다
    assertThat(authorize(W1, p1, "R1", dlv, p1.qr()).status()).isEqualTo(403);
    Res other = authorize(RIDER2, p1, "R2", dlv, p1.qr());
    assertThat(other.status()).isEqualTo(403);
    assertThat(other.body().path("code").asString()).isEqualTo("RIDER_MISMATCH");
    assertThat(
            authorize(RIDER1, p1, "R1", dlv, "PKG:" + p1.id() + ":0")
                .body()
                .path("code")
                .asString())
        .isEqualTo("PACKAGE_INVALIDATED");

    // 출하대기 랙(0.20.0, F-12): 밀봉 포장을 랙에 → 재고 STAGE(패킹대 → 랙), 같은 랙은 그대로, 라이더는 못 한다
    inventoryCommits(
        "STAGE", 201, Map.of("status", "APPLIED", "resultId", "CMT-STG", "appliedQty", 2));
    assertThat(
            call(
                    "POST",
                    RIDER1,
                    "/packages/" + p1.id() + "/staging",
                    UUID.randomUUID().toString(),
                    Map.of("stagingLocation", "DSP-A-01"))
                .status())
        .isEqualTo(403);
    Res staged =
        call(
            "POST",
            W1,
            "/packages/" + p1.id() + "/staging",
            UUID.randomUUID().toString(),
            Map.of("stagingLocation", "DSP-A-01"));
    assertThat(staged.status()).as(staged.body().toString()).isEqualTo(200);
    assertThat(staged.body().path("stagingLocation").asString()).isEqualTo("DSP-A-01");
    assertThat(staged.body().path("packingLocation").asString()).isEqualTo("PAK-A-01");
    assertThat(
            call(
                    "POST",
                    W1,
                    "/packages/" + p1.id() + "/staging",
                    UUID.randomUUID().toString(),
                    Map.of("stagingLocation", "DSP-A-01"))
                .status())
        .isEqualTo(200);
    inv.verify(
        1,
        postRequestedFor(urlPathEqualTo("/api/v1/internal/task-executions"))
            .withRequestBody(matchingJsonPath("$.executionType", equalTo("STAGE")))
            .withRequestBody(matchingJsonPath("$.fromLocation", equalTo("PAK-A-01")))
            .withRequestBody(matchingJsonPath("$.toLocation", equalTo("DSP-A-01"))));

    // 허가 → 같은 라이더가 다시 찍으면 같은 허가
    Res a1 = authorize(RIDER1, p1, "R1", dlv, p1.qr());
    assertThat(a1.status()).as(a1.body().toString()).isEqualTo(201);
    assertThat(a1.body().path("fenceVersion").asInt()).isEqualTo(1);
    assertThat(
            authorize(RIDER1, p1, "R1", dlv, p1.qr())
                .body()
                .path("handoverAuthorizationId")
                .asString())
        .isEqualTo(a1.body().path("handoverAuthorizationId").asString());

    // 확정 → 재고 인계 전기(포장 위치·LOT) → 포장 HANDED_OVER, 출고 PARTIALLY_HANDED_OVER, PackageHandedOver
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/package-handovers"))
            .willReturn(
                json(201, Map.of("status", "APPLIED", "resultId", "CMT-H1", "appliedQty", 2))));
    Res c1 = commit(RIDER1, p1, a1.body(), "R1");
    assertThat(c1.status()).as(c1.body().toString()).isEqualTo(201);
    assertThat(c1.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(c1.body().path("packageState").asString()).isEqualTo("HANDED_OVER");
    assertThat(c1.body().path("shipmentState").asString()).isEqualTo("PARTIALLY_HANDED_OVER");
    assertThat(c1.body().path("inventoryCommitId").asString()).isEqualTo("CMT-H1");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/package-handovers"))
            .withRequestBody(
                matchingJsonPath("$.locationCode", equalTo("DSP-A-01"))) // 랙에서 인계(F-12)
            .withRequestBody(matchingJsonPath("$.reservationId", equalTo("RSV-" + o)))
            .withRequestBody(matchingJsonPath("$.lines[0].lotId", equalTo("LOT-A"))));
    assertThat(outbox("PackageHandedOver", p1.id())).isEqualTo(1);
    // R-19(0.24.0): 출고 조회에 배송 ID(인계 사실·배송 투영)
    assertThat(
            call("GET", VIEWER, "/shipments/" + o + "-S1", null, null)
                .body()
                .path("deliveryId")
                .asString())
        .isEqualTo(dlv);
    // F-9(0.15.0): 센터 출고 목록 — 일부 인계(포장 2 중 1), 인계 라이더·시각, 작업 상태
    String shipmentId = o + "-S1";
    JsonNode board = null;
    String cursor = null;
    do {
      JsonNode page =
          call(
                  "GET",
                  VIEWER,
                  "/shipments?mfcId="
                      + MFC
                      + "&size=200"
                      + (cursor == null ? "" : "&cursor=" + cursor),
                  null,
                  null)
              .body();
      for (JsonNode it : page.path("items")) {
        if (shipmentId.equals(it.path("shipmentId").asString())) {
          board = it;
        }
      }
      cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asString(null);
    } while (board == null && cursor != null);
    assertThat(board).as("끝나지 않은 출고 목록에 있다").isNotNull();
    assertThat(board.path("state").asString()).isEqualTo("PARTIALLY_HANDED_OVER");
    assertThat(board.path("pick").path("state").asString()).isEqualTo("COMPLETED");
    assertThat(board.path("pack").path("state").asString()).isEqualTo("COMPLETED");
    assertThat(board.path("packageCounts").path("total").asInt()).isEqualTo(2);
    assertThat(board.path("packageCounts").path("handedOver").asInt()).isEqualTo(1);
    assertThat(board.path("packageCounts").path("sealed").asInt()).isEqualTo(1);
    JsonNode handed = null;
    for (JsonNode pk : board.path("packages")) {
      if (p1.id().equals(pk.path("packageId").asString())) {
        handed = pk;
      }
    }
    assertThat(handed.path("riderId").asString()).isEqualTo("R1");
    assertThat(handed.path("handedOverAt").isNull()).isFalse();
    assertThat(handed.path("handoverCode").asString()).matches("[0-9]{4}"); // DL-1·DL-2(0.21.0)
    // 다른 센터 직원 403, 끝난 출고만 고르면 이 출고는 없다
    assertThat(
            call(
                    "GET",
                    TestJwt.operator("x", List.of("MFC_SUPERVISOR"), List.of("MFC-OTHER")),
                    "/shipments?mfcId=" + MFC,
                    null,
                    null)
                .status())
        .isEqualTo(403);
    assertThat(
            call(
                    "GET",
                    VIEWER,
                    "/shipments?mfcId=" + MFC + "&state=HANDED_OVER&size=200",
                    null,
                    null)
                .body()
                .path("items")
                .findValuesAsString("shipmentId"))
        .doesNotContain(shipmentId);
    // 같은 허가로 다시 → 같은 인계(재고 다시 안 감), 다시 찍기 → 이미 인계
    assertThat(commit(RIDER1, p1, a1.body(), "R1").body().path("handoverId").asString())
        .isEqualTo(c1.body().path("handoverId").asString());
    inv.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/internal/package-handovers")));
    assertThat(authorize(RIDER1, p1, "R1", dlv, p1.qr()).body().path("code").asString())
        .isEqualTo("ALREADY_HANDED_OVER");
    assertThat(
            call("GET", SVC, "/packages/" + p1.id() + "/handover-resolution", null, null)
                .body()
                .path("verdict")
                .asString())
        .isEqualTo("HANDED_OVER");

    // 인계된 포장이 있는 주문의 전체 중지 → 거절(RESOLVED + HANDED_OVER), 작업·출고 그대로
    Res stop =
        call("POST", SVC, "/internal/stop-requests", UUID.randomUUID().toString(), fullStop(o));
    assertThat(stop.body().path("state").asString())
        .as(stop.body().toString())
        .isEqualTo("RESOLVED");
    assertThat(stop.body().path("packages").toString()).contains("HANDED_OVER").contains(p1.id());
    assertThat(
            call("GET", VIEWER, "/shipments/" + o + "-S1", null, null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("PARTIALLY_HANDED_OVER");
    inv.verify(
        0,
        postRequestedFor(
            urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/cancel-returns")));

    // 남은 포장: 재고 결과 모름 → 202 UNKNOWN_RESULT·판정 인계 진행 중 → 같은 허가로 다시 → 확정, 출고 HANDED_OVER
    Res a2 = authorize(RIDER1, p2, "R1", dlv, p2.qr());
    assertThat(a2.status()).as(a2.body().toString()).isEqualTo(201);
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/package-handovers"))
            .willReturn(json(500, Map.of())));
    Res unknown = commit(RIDER1, p2, a2.body(), "R1");
    assertThat(unknown.status()).isEqualTo(202);
    assertThat(unknown.body().path("status").asString()).isEqualTo("UNKNOWN_RESULT");
    JsonNode res =
        call("GET", SVC, "/packages/" + p2.id() + "/handover-resolution", null, null).body();
    assertThat(res.path("verdict").asString()).isEqualTo("HANDOVER_IN_PROGRESS");
    assertThat(res.path("inventoryApplyStatus").asString()).isEqualTo("UNKNOWN_RESULT");
    // 재고 거절 → REJECTED(자동 재시도·재확정으로 다시 안 감) → 운영자 재시도(D-8)로 같은 인계 ID 다시
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/package-handovers"))
            .willReturn(json(409, Map.of("code", "HANDOVER_QTY_EXCEEDED", "detail", "포장 잔량 부족"))));
    Res rejected = commit(RIDER1, p2, a2.body(), "R1");
    assertThat(rejected.status()).isEqualTo(202);
    assertThat(rejected.body().path("status").asString()).isEqualTo("REJECTED");
    assertThat(rejected.body().path("nextAction").asString()).isEqualTo("RECONCILE");
    assertThat(rejected.body().path("rejectReason").asString()).startsWith("HANDOVER_QTY_EXCEEDED");
    int sent =
        inv.findAll(postRequestedFor(urlPathEqualTo("/api/v1/internal/package-handovers"))).size();
    assertThat(commit(RIDER1, p2, a2.body(), "R1").body().path("status").asString())
        .isEqualTo("REJECTED");
    inv.verify(sent, postRequestedFor(urlPathEqualTo("/api/v1/internal/package-handovers")));
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/package-handovers"))
            .willReturn(
                json(201, Map.of("status", "APPLIED", "resultId", "CMT-H2", "appliedQty", 1))));
    String hid = rejected.body().path("handoverId").asString();
    // 통합 조회(0.10.0, F-3)에도 인계 거절이 보인다
    JsonNode lookedPkg =
        stream(
                call("GET", CS, "/work-orders?orderId=" + o, null, null)
                    .body()
                    .path("workOrders")
                    .get(0)
                    .path("shipments")
                    .get(0)
                    .path("packages"))
            .filter(x -> x.path("packageId").asString().equals(p2.id()))
            .findFirst()
            .orElseThrow();
    assertThat(lookedPkg.path("handoverStatus").asString()).isEqualTo("REJECTED");
    assertThat(lookedPkg.path("handoverRejectReason").asString())
        .startsWith("HANDOVER_QTY_EXCEEDED");
    assertThat(lookedPkg.path("handoverAuthorizedAt").isNull()).isFalse();
    // 처리 대기 목록(0.9.0, F-4)
    JsonNode listed = findInList("/package-handovers?status=REJECTED", "handoverId", hid);
    assertThat(listed.path("packageId").asString()).isEqualTo(p2.id());
    assertThat(listed.path("orderId").asString()).isEqualTo(o);
    assertThat(listed.path("mfcId").asString()).isEqualTo(MFC);
    assertThat(listed.path("deliveryId").asString()).isEqualTo(dlv);
    assertThat(listed.path("rejectReason").asString()).startsWith("HANDOVER_QTY_EXCEEDED");
    assertThat(listed.path("rejectedAt").isNull()).isFalse();
    assertThat(call("GET", ADMIN, "/package-handovers?status=APPLIED", null, null).status())
        .isEqualTo(400);
    assertThat(call("GET", RIDER1, "/package-handovers?status=REJECTED", null, null).status())
        .isEqualTo(403);
    Res c2 =
        call(
            "POST",
            SVC,
            "/internal/package-handovers/" + hid + "/retries",
            UUID.randomUUID().toString(),
            Map.of("operatorId", "OP-admin", "reason", "실물 확인·재고 정정함"));
    assertThat(c2.status()).as(c2.body().toString()).isEqualTo(200);
    assertThat(c2.body().path("status").asString()).isEqualTo("APPLIED");
    assertThat(c2.body().path("shipmentState").asString()).isEqualTo("HANDED_OVER");
    assertThat(c2.body().path("handoverId").asString())
        .isEqualTo(unknown.body().path("handoverId").asString());
    assertThat(outbox("PackageHandedOver", p2.id())).isEqualTo(1);
    assertThat(
            call("GET", ADMIN, "/package-handovers?status=REJECTED", null, null)
                .body()
                .path("items"))
        .noneMatch(i -> i.path("handoverId").asString().equals(hid));
    assertThat(
            jdbc.queryForObject(
                "SELECT operator_id || '|' || reason FROM manual_retry WHERE target_type = 'HANDOVER'"
                    + " AND target_id = ?",
                String.class,
                hid))
        .isEqualTo("OP-admin|실물 확인·재고 정정함");
  }

  /** 주문 번호로 현장 상태(0.10.0, F-3): 본사 역할은 모든 센터, 그 센터 직원만, 다른 센터 403, 없는 주문 404. */
  @Test
  void orderLookupShowsFieldStateForStaff() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    List<Pkg> pkgs = readyShipment(o);

    Res r = call("GET", CS, "/work-orders?orderId=" + o, null, null);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    JsonNode v = r.body();
    assertThat(v.path("orderId").asString()).isEqualTo(o);
    assertThat(v.path("asOf").path("asOf").isMissingNode()).isFalse();
    assertThat(v.path("stopRequests")).isEmpty();
    JsonNode wo = v.path("workOrders").get(0);
    assertThat(v.path("workOrders")).hasSize(1);
    assertThat(wo.path("kind").asString()).isEqualTo("ORIGINAL");
    assertThat(wo.path("mfcId").asString()).isEqualTo(MFC);
    assertThat(wo.path("acceptance").asString()).isEqualTo("ACCEPTED");
    assertThat(wo.path("workVersion").asInt()).isEqualTo(1);
    assertThat(wo.path("preparationStartedAt").isNull()).isFalse();
    JsonNode sh = wo.path("shipments").get(0);
    assertThat(sh.path("state").asString()).isEqualTo("READY");
    assertThat(sh.path("readyAt").isNull()).isFalse();
    JsonNode pick = sh.path("tasks").get(0);
    assertThat(pick.path("taskType").asString()).isEqualTo("PICK");
    assertThat(pick.path("state").asString()).isEqualTo("COMPLETED");
    assertThat(pick.path("startedBy").asString()).isEqualTo("w1");
    assertThat(pick.path("startedAt").isNull()).isFalse();
    assertThat(pick.path("endedAt").isNull()).isFalse();
    assertThat(pick.path("plannedQty").asInt()).isEqualTo(3);
    assertThat(pick.path("completedQty").asInt()).isEqualTo(3);
    assertThat(pick.path("remainingQty").asInt()).isZero();
    JsonNode pack = sh.path("tasks").get(1);
    assertThat(pack.path("taskType").asString()).isEqualTo("PACK");
    assertThat(pack.path("state").asString()).isEqualTo("COMPLETED");
    assertThat(sh.path("shortages")).isEmpty();
    assertThat(sh.path("packages")).hasSize(2);
    JsonNode p0 = sh.path("packages").get(0);
    assertThat(p0.path("packageId").asString()).isEqualTo(pkgs.get(0).id());
    assertThat(p0.path("state").asString()).isEqualTo("SEALED");
    assertThat(p0.path("sealedAt").isNull()).isFalse();
    assertThat(p0.path("fenceState").asString()).isEqualTo("NONE");
    assertThat(p0.path("handoverStatus").isNull()).isTrue();
    // 개인정보·점원 메모 없음
    assertThat(r.body().toString()).doesNotContain("note").doesNotContain("qrCode");

    // 그 센터 직원은 보고, 다른 센터 직원·라이더는 못 본다
    assertThat(call("GET", W1, "/work-orders?orderId=" + o, null, null).status()).isEqualTo(200);
    assertThat(call("GET", OTHER_CENTER, "/work-orders?orderId=" + o, null, null).status())
        .isEqualTo(403);
    assertThat(call("GET", RIDER1, "/work-orders?orderId=" + o, null, null).status())
        .isEqualTo(403);
    assertThat(call("GET", ADMIN, "/work-orders?orderId=ORD-NONE-" + o, null, null).status())
        .isEqualTo(404);
  }

  /** 재포장(WK-07): 새 포장·새 QR, 이전 포장 무효(이전 QR 거절), 인계 진행 중이면 거절, 내용 변경은 아직 없음. */
  @Test
  void repackIssuesNewPackageAndInvalidatesOldLabel() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    List<Pkg> pkgs = readyShipment(o);
    Pkg p1 = pkgs.get(0);
    Pkg p2 = pkgs.get(1);
    String dlv = "DLV-" + o;
    assignRider(o, dlv, "R1", 1);

    // 조회 전용 운영자는 못 한다
    assertThat(
            call(
                    "POST",
                    VIEWER,
                    "/packages/" + p1.id() + "/repackings",
                    UUID.randomUUID().toString(),
                    Map.of("reason", "DAMAGED_BAG"))
                .status())
        .isEqualTo(403);
    // 내용 변경은 아직 없다(같은 줄·LOT 에 수량만 +1)
    JsonNode c0 =
        call("GET", W1, "/packages/" + p1.id(), null, null).body().path("contents").get(0);
    Res changed =
        call(
            "POST",
            W1,
            "/packages/" + p1.id() + "/repackings",
            UUID.randomUUID().toString(),
            Map.of(
                "reason",
                "CONTENT_CHANGE",
                "contents",
                List.of(
                    Map.of(
                        "orderLineId",
                        c0.path("orderLineId").asString(),
                        "sku",
                        c0.path("sku").asString(),
                        "lotId",
                        c0.path("lotId").asString(),
                        "qty",
                        c0.path("qty").asInt() + 1))));
    assertThat(changed.status()).as(changed.body().toString()).isEqualTo(422);
    assertThat(changed.body().path("code").asString()).isEqualTo("CONTENT_MISMATCH");

    String key = UUID.randomUUID().toString();
    Res r =
        call(
            "POST",
            W1,
            "/packages/" + p1.id() + "/repackings",
            key,
            Map.of("reason", "DAMAGED_BAG"));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    String newId = r.body().path("packageId").asString();
    assertThat(newId).isNotEqualTo(p1.id());
    assertThat(r.body().path("packageVersion").asInt()).isEqualTo(2);
    assertThat(r.body().path("state").asString()).isEqualTo("SEALED");
    String newQr = r.body().path("qrCode").asString();
    assertThat(newQr).isEqualTo("PKG:" + newId + ":2");
    // 같은 키 → 같은 새 포장
    assertThat(
            call(
                    "POST",
                    W1,
                    "/packages/" + p1.id() + "/repackings",
                    key,
                    Map.of("reason", "DAMAGED_BAG"))
                .body()
                .path("packageId")
                .asString())
        .isEqualTo(newId);
    JsonNode old = call("GET", W1, "/packages/" + p1.id(), null, null).body();
    assertThat(old.path("state").asString()).isEqualTo("INVALIDATED");
    assertThat(old.path("invalidatedReason").asString()).isEqualTo("DAMAGED_BAG→" + newId);
    assertThat(outbox("PackageInvalidated", p1.id())).isEqualTo(1);
    // 출고에는 유효 포장 2개(새 포장 + p2)
    JsonNode shipment =
        call("GET", W1, "/shipments/" + old.path("shipmentId").asString(), null, null).body();
    assertThat(shipment.path("packages").size()).isEqualTo(2);
    // 이전 QR 은 거절, 새 QR 로 허가
    assertThat(authorize(RIDER1, p1, "R1", dlv, p1.qr()).body().path("code").asString())
        .isEqualTo("PACKAGE_INVALIDATED");
    Pkg np = new Pkg(newId, newQr);
    Res a = authorize(RIDER1, np, "R1", dlv, newQr);
    assertThat(a.status()).as(a.body().toString()).isEqualTo(201);
    JsonNode fence = call("GET", W1, "/packages/" + newId, null, null).body().path("fence");
    assertThat(fence.path("state").asString()).isEqualTo("HANDOVER_IN_PROGRESS");
    assertThat(fence.path("riderId").asString()).isEqualTo("R1");
    // 허가가 살아 있는 포장·무효 포장은 재포장 거절
    Res live =
        call(
            "POST",
            W1,
            "/packages/" + newId + "/repackings",
            UUID.randomUUID().toString(),
            Map.of("reason", "LABEL_UNREADABLE"));
    assertThat(live.status()).isEqualTo(409);
    assertThat(live.body().path("code").asString()).isEqualTo("FENCE_ACTIVE");
    assertThat(
            call(
                    "POST",
                    W1,
                    "/packages/" + p1.id() + "/repackings",
                    UUID.randomUUID().toString(),
                    Map.of("reason", "LABEL_UNREADABLE"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("INVALID_STATE");
    // 다른 포장은 그대로 재포장 가능
    assertThat(
            call(
                    "POST",
                    W2,
                    "/packages/" + p2.id() + "/repackings",
                    UUID.randomUUID().toString(),
                    Map.of("reason", "LABEL_UNREADABLE"))
                .status())
        .isEqualTo(201);
  }

  /**
   * D-1 안 C(2026-11-08): 재배정은 이전 라이더 허가를 끝내지 않는다(이전 라이더 확정 409, 새 라이더 FENCE_ACTIVE → 만료 뒤
   * FENCE_EXPIRED_UNCONFIRMED). 중지는 확인 전까지 PENDING → 점원 확인 AT_STATION → 허가 해제 → 중지 완료(취소 복귀).
   */
  @Test
  void expiredFenceNeedsClerkCheckBeforeOtherRiderOrStop() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    List<Pkg> pkgs = readyShipment(o);
    Pkg p1 = pkgs.get(0);
    String dlv = "DLV-" + o;
    assignRider(o, dlv, "R1", 1);
    Res old = authorize(RIDER1, p1, "R1", dlv, p1.qr());
    assertThat(old.status()).isEqualTo(201);

    // 재배정(R1 → R2): R1 은 확정 못 함, R1 허가는 남아 있어 R2 는 FENCE_ACTIVE
    assignRider(o, dlv, "R2", 2);
    Res oldCommit = commit(RIDER1, p1, old.body(), "R1");
    assertThat(oldCommit.status()).isEqualTo(409);
    assertThat(oldCommit.body().path("code").asString()).isEqualTo("NOT_ASSIGNED");
    assertThat(authorize(RIDER2, p1, "R2", dlv, p1.qr()).body().path("code").asString())
        .isEqualTo("FENCE_ACTIVE");
    // 살아 있는 허가는 점원 확인 대상 아님
    assertThat(check(p1, "AT_STATION").body().path("code").asString()).isEqualTo("FENCE_ACTIVE");

    // 만료(확정 요청 없음) → 다른 라이더는 점원 확인 전 거절, 판정에 확인 필요
    jdbc.update(
        "UPDATE package SET fence_expires_at = now() - interval '1 minute' WHERE package_id = ?",
        p1.id());
    assertThat(authorize(RIDER2, p1, "R2", dlv, p1.qr()).body().path("code").asString())
        .isEqualTo("FENCE_EXPIRED_UNCONFIRMED");
    assertThat(
            call("GET", SVC, "/packages/" + p1.id() + "/handover-resolution", null, null)
                .body()
                .path("checkRequired")
                .asBoolean())
        .isTrue();
    // 0.4.9 센터 전체 확인 필요 목록
    JsonNode toCheck =
        call("GET", W1, "/packages?mfcId=" + MFC + "&checkRequired=true", null, null).body();
    assertThat(toCheck.path("items").findValuesAsString("packageId")).contains(p1.id());
    // DL-1(0.21.0): 목록 표기용 포장 라벨 6자리
    for (JsonNode it : toCheck.path("items")) {
      if (p1.id().equals(it.path("packageId").asString())) {
        assertThat(it.path("handoverCode").asString()).matches("\\d{4}");
      }
    }
    // MB-3(2026-11-18): 센터 관리자도 본다, 역할 없는 같은 센터 운영자는 403
    assertThat(
            call(
                    "GET",
                    TestJwt.operator("sup", List.of("MFC_SUPERVISOR"), List.of(MFC)),
                    "/packages?mfcId=" + MFC + "&checkRequired=true",
                    null,
                    null)
                .body()
                .path("items")
                .findValuesAsString("packageId"))
        .contains(p1.id());
    assertThat(
            call("GET", VIEWER, "/packages?mfcId=" + MFC + "&checkRequired=true", null, null)
                .status())
        .isEqualTo(403);
    assertThat(
            call("GET", W1, "/packages?mfcId=" + MFC + "&checkRequired=false", null, null).status())
        .isEqualTo(400);

    // 중지도 확인 전까지 PENDING
    Res stop =
        call("POST", SVC, "/internal/stop-requests", UUID.randomUUID().toString(), fullStop(o));
    assertThat(stop.body().path("state").asString()).isEqualTo("PENDING");
    assertThat(stop.body().path("packages").toString()).contains("HANDOVER_IN_PROGRESS");
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/reservations/RSV-" + o + "/cancel-returns"))
            .willReturn(
                json(200, Map.of("status", "APPLIED", "resultId", "RSV-" + o, "appliedQty", 3))));
    stops.sweepPending();
    assertThat(
            call("GET", SVC, "/internal/stop-requests/STOP-" + o, null, null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("PENDING");

    // 점원 확인 — 포장대에 있음 → 허가 해제 → 중지 완료
    Res checked = check(p1, "AT_STATION");
    assertThat(checked.status()).as(checked.body().toString()).isEqualTo(200);
    assertThat(checked.body().path("checkRequired").asBoolean()).isFalse();
    assertThat(checked.body().path("lastCheck").path("result").asString()).isEqualTo("AT_STATION");
    assertThat(
            call("GET", W1, "/packages?mfcId=" + MFC + "&checkRequired=true", null, null)
                .body()
                .path("items")
                .findValuesAsString("packageId"))
        .doesNotContain(p1.id());
    stops.sweepPending();
    JsonNode done = call("GET", SVC, "/internal/stop-requests/STOP-" + o, null, null).body();
    assertThat(done.path("state").asString()).as(done.toString()).isEqualTo("RESOLVED");
    assertThat(
            call("GET", VIEWER, "/shipments/" + o + "-S1", null, null)
                .body()
                .path("state")
                .asString())
        .isEqualTo("CANCELLED");
    inv.verify(0, postRequestedFor(urlPathEqualTo("/api/v1/internal/package-handovers")));
  }

  /** D-1 안 C: 같은 라이더·배송은 만료 뒤 확인 없이 다시 찍기. 점원 확인 TAKEN_BY_RIDER → 그 라이더에게 인계 확정(재고 전기). */
  @Test
  void sameRiderRescansWithoutCheckAndTakenByRiderCommitsHandover() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    List<Pkg> pkgs = readyShipment(o);
    Pkg p1 = pkgs.get(0);
    Pkg p2 = pkgs.get(1);
    String dlv = "DLV-" + o;
    assignRider(o, dlv, "R1", 1);
    assertThat(authorize(RIDER1, p1, "R1", dlv, p1.qr()).status()).isEqualTo(201);
    jdbc.update(
        "UPDATE package SET fence_expires_at = now() - interval '1 minute' WHERE package_id = ?",
        p1.id());
    Res again = authorize(RIDER1, p1, "R1", dlv, p1.qr());
    assertThat(again.status()).as(again.body().toString()).isEqualTo(201);
    assertThat(again.body().path("fenceVersion").asInt()).isEqualTo(2);

    // p2: 찍고 만료 → 점원이 "포장대에 없음" → R1 에게 인계 확정
    assertThat(authorize(RIDER1, p2, "R1", dlv, p2.qr()).status()).isEqualTo(201);
    jdbc.update(
        "UPDATE package SET fence_expires_at = now() - interval '1 minute' WHERE package_id = ?",
        p2.id());
    inv.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/package-handovers"))
            .willReturn(
                json(201, Map.of("status", "APPLIED", "resultId", "CMT-HC", "appliedQty", 1))));
    Res taken = check(p2, "TAKEN_BY_RIDER");
    assertThat(taken.status()).as(taken.body().toString()).isEqualTo(200);
    assertThat(taken.body().path("verdict").asString()).isEqualTo("HANDED_OVER");
    assertThat(taken.body().path("inventoryCommitId").asString()).isEqualTo("CMT-HC");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/package-handovers"))
            .withRequestBody(matchingJsonPath("$.riderId", equalTo("R1")))
            .withRequestBody(matchingJsonPath("$.packageId", equalTo(p2.id()))));
    assertThat(outbox("PackageHandedOver", p2.id())).isEqualTo(1);
    // 다시 확인 → 확인할 허가 없음(재고 한 번)
    assertThat(check(p2, "TAKEN_BY_RIDER").body().path("code").asString())
        .isEqualTo("INVALID_STATE");
    inv.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/internal/package-handovers")));
  }

  Res check(Pkg p, String result) {
    return call(
        "POST",
        W1,
        "/packages/" + p.id() + "/handover-checks",
        UUID.randomUUID().toString(),
        Map.of("result", result, "note", "시험"));
  }

  /**
   * 배차 요청(D-5): 피킹 대기 전 409·피킹 시작 전 가능(F-2), 포장 중 점원 요청 MANUAL(예상 시각 = 지금 + 분), 다시 = 200 같은 요청, 포장
   * 끝까지 없으면 READY 때 AUTO.
   */
  @Test
  void dispatchIsRequestedOnlyByClerk() {
    String o0 = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s0 = accept(o0);
    // F-2(0.14.0): 피킹 시작 전에도 요청한다 — 단 예약 주문 대기(PICK WAITING)는 아직 안 된다
    jdbc.update("UPDATE task SET state = 'WAITING' WHERE task_id = ?", s0 + "-PICK");
    assertThat(dispatch(W2, s0, 5).body().path("code").asString()).isEqualTo("INVALID_STATE");
    jdbc.update("UPDATE task SET state = 'READY' WHERE task_id = ?", s0 + "-PICK");
    Res beforePick = dispatch(W2, s0, 25);
    assertThat(beforePick.status()).as(beforePick.body().toString()).isIn(200, 201);
    assertThat(beforePick.body().path("estimatedPackMinutes").asInt()).isEqualTo(25);
    assertThat(beforePick.body().path("trigger").asString()).isEqualTo("MANUAL");

    String o1 = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s1 = packStarted(o1);
    Res manual = dispatch(W2, s1, 7);
    assertThat(manual.status()).as(manual.body().toString()).isEqualTo(201);
    assertThat(manual.body().path("trigger").asString()).isEqualTo("MANUAL");
    assertThat(manual.body().path("estimatedPackMinutes").asInt()).isEqualTo(7);
    java.time.Duration gap =
        java.time.Duration.between(
            java.time.Instant.parse(manual.body().path("requestedAt").asString()),
            java.time.Instant.parse(manual.body().path("estimatedReadyAt").asString()));
    assertThat(gap).isEqualTo(java.time.Duration.ofMinutes(7));
    Res again = dispatch(W2, s1, 3);
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("estimatedPackMinutes").asInt()).isEqualTo(7);
    assertThat(outbox("DispatchRequested", s1)).isEqualTo(1);
    assertThat(dispatch(W2, s1, 0).status()).isEqualTo(400); // 계약 최소 1분

    // 요청 없이 출하 대기가 돼도 자동 요청하지 않는다(사용자 2026-11-13 "자동요청빼", BR-51) — 점원이 그 뒤에 요청
    String o2 = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    readyShipment(o2);
    String s2 = o2 + "-S1";
    assertThat(outbox("DispatchRequested", s2)).isZero();
    JsonNode ship = call("GET", VIEWER, "/shipments/" + s2, null, null).body();
    assertThat(ship.path("state").asString()).isEqualTo("READY");
    assertThat(
            ship.path("dispatchRequest").isMissingNode() || ship.path("dispatchRequest").isNull())
        .as(ship.toString())
        .isTrue();
    Res late = dispatch(W2, s2, 5);
    assertThat(late.status()).as(late.body().toString()).isEqualTo(201);
    assertThat(late.body().path("trigger").asString()).isEqualTo("MANUAL");
    assertThat(outbox("DispatchRequested", s2)).isEqualTo(1);
  }

  /** 포장 사이 재구성(C-12 1부): 두 포장 → 다른 묶음의 두 포장, 합계 같아야, 이전 QR 무효. */
  @Test
  void packagesAreRegroupedWithinShipmentKeepingTotals() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    List<Pkg> pkgs = readyShipment(o);
    String s = o + "-S1";
    Map<String, Object> l1a =
        Map.of("orderLineId", "L1", "sku", "E-00000001", "lotId", "LOT-A", "qty", 1);
    Map<String, Object> l1b =
        Map.of("orderLineId", "L1", "sku", "E-00000001", "lotId", "LOT-B", "qty", 1);
    Map<String, Object> l2c =
        Map.of("orderLineId", "L2", "sku", "E-00000002", "lotId", "LOT-C", "qty", 1);
    List<String> ids = List.of(pkgs.get(0).id(), pkgs.get(1).id());
    // 합계가 다르면 422
    Res wrong =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/repackings",
            UUID.randomUUID().toString(),
            Map.of(
                "reason",
                "CONTENT_CHANGE",
                "packageIds",
                ids,
                "packages",
                List.of(Map.of("bagCount", 1, "contents", List.of(l1a, l2c)))));
    assertThat(wrong.status()).as(wrong.body().toString()).isEqualTo(422);
    assertThat(wrong.body().path("code").asString()).isEqualTo("CONTENT_MISMATCH");
    Res ok =
        call(
            "POST",
            W2,
            "/shipments/" + s + "/repackings",
            UUID.randomUUID().toString(),
            Map.of(
                "reason",
                "CONTENT_CHANGE",
                "packageIds",
                ids,
                "packages",
                List.of(
                    Map.of("bagCount", 1, "contents", List.of(l1a, l2c)),
                    Map.of("bagCount", 1, "contents", List.of(l1b)))));
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
    assertThat(ok.body().path("packages")).hasSize(2);
    assertThat(outbox("PackageInvalidated", pkgs.get(0).id())).isEqualTo(1);
    assertThat(outbox("PackageInvalidated", pkgs.get(1).id())).isEqualTo(1);
    JsonNode ship = call("GET", VIEWER, "/shipments/" + s, null, null).body();
    assertThat(ship.path("packages")).hasSize(2);
    assertThat(ship.path("packages").findValuesAsString("packageId"))
        .doesNotContain(pkgs.get(0).id(), pkgs.get(1).id());
    // 이전 QR 은 무효
    String dlv = "DLV-" + o;
    assignRider(o, dlv, "R1", 1);
    assertThat(
            authorize(RIDER1, pkgs.get(0), "R1", dlv, pkgs.get(0).qr())
                .body()
                .path("code")
                .asString())
        .isEqualTo("PACKAGE_INVALIDATED");
  }

  /** 작업자 쪽 3단계: 늦음·무실적 표시(시작 뒤 예상 2배·30분), 작업 내놓기(담당만 → capacity 해제 → READY, 완료량 그대로). */
  @Test
  void lateAndIdleAreFlaggedAndAssigneeCanReleaseTask() {
    String o = "ORD-" + UUID.randomUUID().toString().substring(0, 8);
    String s = accept(o);
    String pick = s + "-PICK";
    plan(o, 0, 0, 0, 0, 0, 0);
    assigned(pick, "w1", 1);
    assertThat(start(W1, pick, "w1", 1).status()).isEqualTo(201);
    JsonNode fresh = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(fresh.path("late").asBoolean()).isFalse();
    assertThat(fresh.path("idle").asBoolean()).isFalse();
    jdbc.update("UPDATE task SET started_at = now() - interval '1 hour' WHERE task_id = ?", pick);
    JsonNode old = call("GET", VIEWER, "/tasks/" + pick, null, null).body();
    assertThat(old.path("late").asBoolean()).isTrue(); // 7분 × 2 넘김
    assertThat(old.path("idle").asBoolean()).isTrue(); // 30분 실적 없음
    JsonNode listed =
        call("GET", VIEWER, "/tasks?mfcId=" + MFC + "&state=IN_PROGRESS", null, null).body();
    assertThat(listed.toString()).contains("\"late\":true");

    // 담당이 아니면 409(capacity 를 부르지 않음), 담당이면 capacity 해제 → READY
    Res notMine =
        call(
            "POST",
            W2,
            "/tasks/" + pick + "/releases",
            UUID.randomUUID().toString(),
            Map.of("workerId", "w2", "reason", "교대"));
    assertThat(notMine.status()).isEqualTo(409);
    cap.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/assignments/" + pick + "/releases"))
            .willReturn(json(200, Map.of("taskId", pick, "released", true))));
    Res rel =
        call(
            "POST",
            W1,
            "/tasks/" + pick + "/releases",
            UUID.randomUUID().toString(),
            Map.of("workerId", "w1", "reason", "퇴근"));
    assertThat(rel.status()).as(rel.body().toString()).isEqualTo(200);
    assertThat(rel.body().path("state").asString()).isEqualTo("READY");
    assertThat(rel.body().path("assignment").path("workerId").isNull()).isTrue();
    cap.verify(
        WireMock.postRequestedFor(
                urlPathEqualTo("/api/v1/internal/assignments/" + pick + "/releases"))
            .withRequestBody(matchingJsonPath("$.workerId", equalTo("w1"))));
  }

  Res dispatch(String token, String shipmentId, int minutes) {
    return call(
        "POST",
        token,
        "/shipments/" + shipmentId + "/dispatch-requests",
        UUID.randomUUID().toString(),
        Map.of("estimatedPackMinutes", minutes));
  }

  static Map<String, Object> returnLikePick() {
    return Map.of(
        "executionType",
        "PICK",
        "workerId",
        "w1",
        "fromLocation",
        "A-S1-01-1-01",
        "toLocation",
        "PAK-A-01",
        "lines",
        List.of(
            Map.of("orderLineId", "L1", "sku", "E-00000001", "lotId", "LOT-A", "pickedQty", 1)));
  }

  static Map<String, Object> returnBody(String type, int qty) {
    return Map.of(
        "executionType",
        type,
        "workerId",
        "w2",
        "fromLocation",
        "PAK-A-01",
        "toLocation",
        "A-S1-01-1-01",
        "lines",
        List.of(
            Map.of("orderLineId", "L1", "sku", "E-00000001", "lotId", "LOT-A", "pickedQty", qty)));
  }
}
