package com.pmart.mfc.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 재고 이동 지시(IV-03): 센터 관리자 지시 → 작업자 집어 올리기·내려놓기 → 센터 관리자 취소. 수량 정본(inventory)은 WireMock. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inbound.post-interval=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.inbound.mfc-inventory-base-url"))
class MoveOrderIT extends PostgresKafkaContainerSupport {

  static final String MFC = "MFC-MV-1";
  static final String SUP = TestJwt.operator("sup-m", List.of("MFC_SUPERVISOR"), List.of(MFC));
  static final String WRK = TestJwt.operator("wrk-m", List.of("MFC_WORKER"), List.of(MFC));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired org.springframework.kafka.core.KafkaTemplate<String, String> kafka;
  @Autowired com.pmart.mfc.inbound.application.MoveOrderService moveOrders;

  @InjectWireMock("inventory")
  WireMockServer inv;

  RestClient http;

  record Res(int status, JsonNode body) {}

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  Res post(String token, String path, @Nullable Object body) {
    var spec =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "mfc-pda");
                });
    var e =
        (body == null
                ? spec
                : spec.contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body)))
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), mapper.readTree(e.getBody()));
  }

  void stub(String pathRegex, int status, Map<String, Object> body) {
    inv.stubFor(
        WireMock.post(urlPathMatching(pathRegex))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(body))));
  }

  static Map<String, Object> alloc(int qty, int picked, int dropped, String state, String cart) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("qty", qty);
    m.put("pickedQty", picked);
    m.put("droppedQty", dropped);
    m.put("state", state);
    m.put("cartLocation", cart);
    return m;
  }

  @Test
  void supervisorOrdersAndWorkerMovesThroughACart() {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("mfcId", MFC);
    order.put("sku", "E-00000009");
    order.put("lotId", "LOT-1");
    order.put("fromLocation", "A-S1-01-1-01");
    order.put("toLocation", "A-S1-02-1-01");
    order.put("qty", 10);
    order.put("reason", "선반 재배치");

    // 작업자는 지시 못 한다
    assertThat(post(WRK, "/move-orders", order).status()).isEqualTo(403);

    // inventory 거절 코드는 그대로
    stub(
        "/api/v1/internal/move-allocations",
        409,
        Map.of("code", "LOCATION_HAS_OTHER_SKU", "detail", "다른 상품이 있습니다"));
    Res rejected = post(SUP, "/move-orders", order);
    assertThat(rejected.status()).isEqualTo(409);
    assertThat(rejected.body().path("code").asString()).isEqualTo("LOCATION_HAS_OTHER_SKU");

    stub("/api/v1/internal/move-allocations", 201, alloc(10, 0, 0, "ACTIVE", null));
    Res created = post(SUP, "/move-orders", order);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String moveId = created.body().path("moveId").asString();
    assertThat(moveId).startsWith("MV-");
    assertThat(created.body().path("state").asString()).isEqualTo("ACTIVE");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/move-allocations"))
            .withRequestBody(matchingJsonPath("$.moveId", WireMock.equalTo(moveId))));

    // 작업자: 카트로 집어 올리기 → 사본 갱신, 실행 ID = moveId + 요청 키
    stub(
        "/api/v1/internal/move-allocations/.*/pickups", 200, alloc(10, 6, 0, "ACTIVE", "CRT-A-01"));
    Res picked =
        post(
            WRK,
            "/move-orders/" + moveId + "/pickups",
            Map.of("cartLocation", "CRT-A-01", "qty", 6));
    assertThat(picked.status()).as(picked.body().toString()).isEqualTo(200);
    assertThat(picked.body().path("onCartQty").asInt()).isEqualTo(6);
    assertThat(picked.body().path("cartLocation").asString()).isEqualTo("CRT-A-01");
    inv.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/move-allocations/" + moveId + "/pickups"))
            .withRequestBody(matchingJsonPath("$.executionId", WireMock.matching(moveId + ":.+"))));

    stub("/api/v1/internal/move-allocations/.*/drops", 200, alloc(10, 6, 6, "ACTIVE", "CRT-A-01"));
    Res dropped =
        post(
            WRK,
            "/move-orders/" + moveId + "/drops",
            Map.of("toLocation", "A-S1-02-1-01", "qty", 6));
    assertThat(dropped.body().path("droppedQty").asInt()).isEqualTo(6);

    // 취소는 센터 관리자만, 집지 않은 몫이 풀림
    assertThat(
            post(WRK, "/move-orders/" + moveId + "/cancellations", Map.of("reason", "x")).status())
        .isEqualTo(403);
    stub(
        "/api/v1/internal/move-allocations/.*/cancellations",
        200,
        alloc(6, 6, 6, "CANCELLED", "CRT-A-01"));
    Res cancelled =
        post(SUP, "/move-orders/" + moveId + "/cancellations", Map.of("reason", "나머지는 그대로"));
    assertThat(cancelled.body().path("state").asString()).isEqualTo("CANCELLED");
    assertThat(cancelled.body().path("qty").asInt()).isEqualTo(6);

    // 목록: 진행 중에는 없음, CANCELLED 로 보면 있음
    assertThat(
            get(WRK, "/move-orders?mfcId=" + MFC).body().path("items").findValuesAsString("moveId"))
        .doesNotContain(moveId);
    assertThat(
            get(WRK, "/move-orders?mfcId=" + MFC + "&state=CANCELLED")
                .body()
                .path("items")
                .findValuesAsString("moveId"))
        .contains(moveId);
  }

  /** 작업자 쪽 4단계: 긴급 표시, 담당 투영(AssignmentPublished) — 담당만 집어 올림, 카트에 실은 사람만 내려놓음. */
  @Test
  void assignedMoveIsPickedOnlyByAssigneeAndDroppedOnlyByPicker() throws Exception {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("mfcId", MFC);
    order.put("sku", "E-00000019");
    order.put("lotId", "LOT-9");
    order.put("fromLocation", "A-S1-03-1-01");
    order.put("toLocation", "A-S1-04-1-01");
    order.put("qty", 4);
    order.put("urgent", true);
    stub("/api/v1/internal/move-allocations", 201, alloc(4, 0, 0, "ACTIVE", null));
    // 상품명·바코드(0.9.0, BR-52 R19-1) — mfc-inventory SKU 사본, 선행 0 보존
    inv.stubFor(
        WireMock.get(urlPathMatching("/api/v1/internal/skus"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "items",
                                List.of(
                                    Map.of(
                                        "sku",
                                        "E-00000019",
                                        "productName",
                                        "매일우유 900mL",
                                        "barcodes",
                                        List.of("08801155720019", "8801155720019"))),
                                "missing",
                                List.of())))));
    Res created = post(SUP, "/move-orders", order);
    assertThat(created.body().path("urgent").asBoolean()).isTrue();
    assertThat(created.body().path("productName").asString()).isEqualTo("매일우유 900mL");
    assertThat(created.body().path("barcodes").path(0).asString()).isEqualTo("08801155720019");
    String moveId = created.body().path("moveId").asString();
    JsonNode listed =
        get(WRK, "/move-orders?mfcId=" + MFC).body().path("items").findParents("moveId").stream()
            .filter(n -> moveId.equals(n.path("moveId").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(listed.path("barcodes")).hasSize(2);
    // 서비스 간 조회(mfc-capacity)는 withLabels=false — inventory 를 부르지 않는다
    JsonNode bare = get(WRK, "/move-orders/" + moveId + "?withLabels=false").body();
    assertThat(bare.path("productName").isNull()).isTrue();
    assertThat(bare.path("barcodes")).isEmpty();
    String json =
        EventEnvelopeFixtures.envelope("AssignmentPublished")
            .from("mfc-capacity-service")
            .aggregate("AssignmentPlan", "MANUAL:" + moveId, 1)
            .payload(
                Map.of(
                    "planVersion",
                    1,
                    "mfcId",
                    MFC,
                    "assignments",
                    List.of(Map.of("taskId", moveId, "workerId", "wrk-m", "assignmentVersion", 1))))
            .buildJson();
    kafka.send("mfc.capacity.v1", "MANUAL:" + moveId, json).join();
    long until = System.currentTimeMillis() + 30_000;
    String assignee = null;
    while (System.currentTimeMillis() < until) {
      assignee = get(WRK, "/move-orders/" + moveId).body().path("assigneeId").asString(null);
      if ("wrk-m".equals(assignee)) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(assignee).isEqualTo("wrk-m");
    String other = TestJwt.operator("wrk-x", List.of("MFC_WORKER"), List.of(MFC));
    stub("/api/v1/internal/move-allocations/.*/pickups", 200, alloc(4, 4, 0, "ACTIVE", "CRT-A-01"));
    assertThat(
            post(
                    other,
                    "/move-orders/" + moveId + "/pickups",
                    Map.of("cartLocation", "CRT-A-01", "qty", 4))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NOT_ASSIGNEE");
    Res picked =
        post(
            WRK,
            "/move-orders/" + moveId + "/pickups",
            Map.of("cartLocation", "CRT-A-01", "qty", 4));
    assertThat(picked.status()).as(picked.body().toString()).isEqualTo(200);
    assertThat(picked.body().path("pickedBy").asString()).isEqualTo("wrk-m");
    assertThat(
            post(
                    other,
                    "/move-orders/" + moveId + "/drops",
                    Map.of("toLocation", "A-S1-04-1-01", "qty", 4))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NOT_PICKER");
  }

  @Test
  void systemReplenishmentsAreRecordedAsMoveOrdersOnce() {
    Map<String, Object> mv = new LinkedHashMap<>(alloc(10, 0, 0, "ACTIVE", null));
    mv.put("moveId", "RPL-IT-1");
    mv.put("kind", "REPLENISH");
    mv.put("mfcId", MFC);
    mv.put("sku", "E-00000010");
    mv.put("lotId", "LOT-9");
    mv.put("fromLocation", "A-S1-05-1-01");
    mv.put("toLocation", "A-S9-01-1-01");
    stub(
        "/api/v1/internal/replenishment-evaluations",
        200,
        Map.of("created", 1, "moves", List.of(mv), "skipped", List.of()));
    assertThat(moveOrders.recordReplenishments()).isEqualTo(1);
    assertThat(moveOrders.recordReplenishments()).isZero(); // 같은 이동은 한 번
    JsonNode order = get(WRK, "/move-orders/RPL-IT-1").body();
    assertThat(order.path("kind").asString()).isEqualTo("REPLENISH");
    assertThat(order.path("createdBy").asString()).isEqualTo("SYSTEM");
    assertThat(order.path("qty").asInt()).isEqualTo(10);
  }
}
