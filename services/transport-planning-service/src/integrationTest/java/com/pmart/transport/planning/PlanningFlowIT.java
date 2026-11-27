package com.pmart.transport.planning;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * 노선·정규차 → 출고일 배차 생성(TRF 가져오기·검증 V1~V8·D-06) → 요청서 옮기기(시각 검증) → 계획완료(fleet 점유 → execution 운행,
 * 거절·불명·이어 가기) → 운행 상태 사본 → 취소. cdc-outbound·fleet·execution 은 WireMock, DB 는 실제 PostgreSQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.planning.retry-interval=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "outbound", baseUrlProperties = "pmart.planning.cdc-outbound-base-url"),
  @ConfigureWireMock(name = "fleet", baseUrlProperties = "pmart.planning.fleet-base-url"),
  @ConfigureWireMock(name = "execution", baseUrlProperties = "pmart.planning.execution-base-url"),
  @ConfigureWireMock(name = "location", baseUrlProperties = "pmart.planning.cdc-location-base-url")
})
class PlanningFlowIT extends PostgresKafkaContainerSupport {

  static final String CDC = "CDC01";
  static final String SUP = TestJwt.operator("sup-1", List.of("CDC_SUPERVISOR"), List.of(CDC));
  static final String SERVICE = TestJwt.service("transport-execution-service");
  static final String WORKER = TestJwt.operator("w-1", List.of("CDC_WORKER"), List.of(CDC));
  static final String D = "2026-12-01";
  static final String DUE = "2026-12-01T22:30:00Z"; // D+1 07:30 KST

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("outbound")
  WireMockServer outbound;

  @InjectWireMock("fleet")
  WireMockServer fleet;

  @InjectWireMock("execution")
  WireMockServer execution;

  @InjectWireMock("location")
  WireMockServer location;

  RestClient http;

  @BeforeEach
  void client() {
    // 0.3.0 도크 — 기본은 칸을 잡아 준다, 해제는 늘 됨
    location.stubFor(
        post(urlPathEqualTo("/api/v1/internal/dock-reservations"))
            .willReturn(
                okJson(
                    "{\"dockReservationId\":\"DKR-1\",\"dockCode\":\"CDC01-A-6-90-01-0-1\",\"state\":\"RESERVED\"}")));
    location.stubFor(
        post(urlPathMatching("/api/v1/internal/dock-reservations/.*/releases"))
            .willReturn(okJson("{\"state\":\"RELEASED\"}")));
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res call(String token, String path, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "it");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  Res read(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  JsonNode read(String body) {
    return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
  }

  void vehicle(String id, String temp, int maxRt) {
    fleet.stubFor(
        get(urlPathEqualTo("/api/v1/vehicles/" + id))
            .willReturn(
                okJson(
                    "{\"vehicleId\":\""
                        + id
                        + "\",\"plateNo\":\"12가0001\",\"contractType\":\"REGULAR\",\"tempClass\":\""
                        + temp
                        + "\",\"maxRt\":"
                        + maxRt
                        + ",\"maxWeightKg\":5000,\"active\":true}")));
  }

  void driver(String id) {
    fleet.stubFor(
        get(urlPathEqualTo("/api/v1/drivers/" + id))
            .willReturn(
                okJson(
                    "{\"driverId\":\""
                        + id
                        + "\",\"displayName\":\"기사 "
                        + id
                        + "\",\"active\":true}")));
  }

  static Map<String, Object> ready(Map<String, Object> m, String dueAt) {
    m.put("deadlineMeaning", "READY");
    m.put("dueAt", dueAt);
    return m;
  }

  static Map<String, Object> due(Map<String, Object> m, String dueAt) {
    m.put("dueAt", dueAt);
    return m;
  }

  static Map<String, Object> trf(
      String id,
      int version,
      String mfc,
      String state,
      int approved,
      int allocated,
      List<String> temps,
      Integer rt) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("trfId", id);
    m.put("trfNumber", "T" + id.substring(id.length() - 3));
    m.put("trfVersion", version);
    m.put("mfcId", mfc);
    m.put("state", state);
    m.put("dueAt", DUE);
    m.put("approvedQty", approved);
    m.put("allocatedQty", allocated);
    m.put("tempClasses", temps);
    m.put("boxCount", 10);
    m.put("rtCount", rt);
    m.put("weightG", 300000);
    m.put("receivedAt", "2026-12-01T05:00:00Z");
    return m;
  }

  void feed(List<Map<String, Object>> items) {
    outbound.stubFor(
        get(urlPathEqualTo("/api/v1/internal/transport-feed"))
            .willReturn(
                okJson(mapper.writeValueAsString(Map.of("truncated", false, "items", items)))));
  }

  JsonNode order(JsonNode gen, String routeId, String temp) {
    for (JsonNode o : gen.path("orders")) {
      if (routeId.equals(o.path("routeId").asString())
          && temp.equals(o.path("tempClass").asString())) {
        return o;
      }
    }
    return null;
  }

  JsonNode req(String trf) {
    return read(SUP, "/cdcs/" + CDC + "/transport-requests/" + trf).body();
  }

  @Test
  void routesGenerateMoveConfirmCancel() {
    // ---- 1) 노선·정규차 ----
    Map<String, Object> rt04 =
        Map.of(
            "routeId",
            "RT-04",
            "name",
            "부산",
            "departureTime",
            "00:30",
            "returnMinutes",
            240,
            "stops",
            List.of(
                Map.of("mfcId", "MFC-BJG-01", "legMinutes", 200),
                Map.of("mfcId", "MFC-BNG-01", "legMinutes", 20)));
    assertThat(call(WORKER, "/cdcs/" + CDC + "/routes", rt04).status()).isEqualTo(403);
    Res r = call(SUP, "/cdcs/" + CDC + "/routes", rt04);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    assertThat(r.body().path("stops").get(1).path("plannedArrivalOffsetMinutes").asInt())
        .isEqualTo(250);
    assertThat(
            call(
                    SUP,
                    "/cdcs/" + CDC + "/routes",
                    Map.of(
                        "routeId",
                        "RT-99",
                        "name",
                        "먼 곳",
                        "departureTime",
                        "00:30",
                        "returnMinutes",
                        60,
                        "stops",
                        List.of(Map.of("mfcId", "MFC-FAR", "legMinutes", 500))))
                .body()
                .path("code")
                .asString())
        .isEqualTo("ROUTE_TIME_INFEASIBLE");
    assertThat(
            call(
                    SUP,
                    "/cdcs/" + CDC + "/routes",
                    Map.of(
                        "routeId",
                        "RT-98",
                        "name",
                        "겹침",
                        "departureTime",
                        "01:00",
                        "returnMinutes",
                        60,
                        "stops",
                        List.of(Map.of("mfcId", "MFC-BNG-01", "legMinutes", 100))))
                .body()
                .path("code")
                .asString())
        .isEqualTo("MFC_ALREADY_ROUTED");
    assertThat(
            call(
                    SUP,
                    "/cdcs/" + CDC + "/routes",
                    Map.of(
                        "routeId",
                        "RT-07",
                        "name",
                        "세종",
                        "departureTime",
                        "02:00",
                        "returnMinutes",
                        150,
                        "stops",
                        List.of(Map.of("mfcId", "MFC-SEJ-01", "legMinutes", 150))))
                .status())
        .isEqualTo(201);
    vehicle("VH-04A", "AMBIENT", 20);
    vehicle("VH-07A", "AMBIENT", 20);
    vehicle("VH-04C", "CHILLED", 20);
    driver("DR-04A");
    driver("DR-07A");
    Res bad =
        call(
            SUP,
            "/cdcs/" + CDC + "/routes/RT-04/regular-vehicles",
            Map.of("tempClass", "AMBIENT", "vehicleId", "VH-04C", "driverId", "DR-04A"));
    assertThat(bad.body().path("code").asString()).isEqualTo("VEHICLE_NOT_ELIGIBLE");
    assertThat(
            call(
                    SUP,
                    "/cdcs/" + CDC + "/routes/RT-04/regular-vehicles",
                    Map.of("tempClass", "AMBIENT", "vehicleId", "VH-04A", "driverId", "DR-04A"))
                .status())
        .isEqualTo(200);
    assertThat(
            call(
                    SUP,
                    "/cdcs/" + CDC + "/routes/RT-07/regular-vehicles",
                    Map.of("tempClass", "AMBIENT", "vehicleId", "VH-07A", "driverId", "DR-07A"))
                .status())
        .isEqualTo(200);

    // ---- 2) 배차 생성 — 검증 ----
    feed(
        List.of(
            trf("TRF-001", 1, "MFC-BJG-01", "ALLOCATED", 50, 50, List.of("AMBIENT"), 5),
            trf("TRF-002", 1, "MFC-BNG-01", "PARTIALLY_ALLOCATED", 100, 60, List.of("AMBIENT"), 3),
            trf("TRF-003", 1, "MFC-SEJ-01", "ALLOCATION_FAILED", 10, 0, List.of(), null),
            trf("TRF-004", 1, "MFC-NOWHERE", "ALLOCATED", 10, 10, List.of("AMBIENT"), 1),
            trf("TRF-005", 1, "MFC-BJG-01", "IN_WAVE", 10, 10, List.of("AMBIENT", "CHILLED"), null),
            trf("TRF-006", 1, "MFC-BJG-01", "IN_WAVE", 10, 10, List.of("CHILLED"), 2),
            trf("TRF-007", 1, "MFC-SEJ-01", "IN_WAVE", 10, 10, List.of("AMBIENT"), 25),
            trf("TRF-008", 1, "MFC-SEJ-01", "IN_WAVE", 10, 10, List.of("AMBIENT"), null),
            trf("TRF-009", 1, "MFC-SEJ-01", "CANCELLED", 10, 0, List.of(), null),
            // 0.5.0 R-28 READY: 준비 12-02 14:00 KST → 출고일 12-01(D), 준비 12-02 06:00 KST → 11-30(지난 날
            // — 도달 불가)
            ready(
                trf("TRF-010", 1, "MFC-SEJ-01", "IN_WAVE", 10, 10, List.of("AMBIENT"), null),
                "2026-12-02T05:00:00Z"),
            ready(
                trf("TRF-011", 1, "MFC-SEJ-01", "IN_WAVE", 10, 10, List.of("AMBIENT"), null),
                "2026-12-01T21:00:00Z"),
            // ARRIVAL 이 D 구간 밖(12-03 KST)이면 이번 생성에 넣지 않음
            due(
                trf("TRF-012", 1, "MFC-SEJ-01", "IN_WAVE", 10, 10, List.of("AMBIENT"), null),
                "2026-12-02T20:00:00Z")));
    assertThat(
            call(WORKER, "/cdcs/" + CDC + "/transport-orders/generations", Map.of("shipDate", D))
                .status())
        .isEqualTo(403);
    Res g = call(SUP, "/cdcs/" + CDC + "/transport-orders/generations", Map.of("shipDate", D));
    assertThat(g.status()).as(g.body().toString()).isEqualTo(200);
    JsonNode o04 = order(g.body(), "RT-04", "AMBIENT");
    JsonNode o07 = order(g.body(), "RT-07", "AMBIENT");
    assertThat(o04.path("trfIds").toString()).contains("TRF-001", "TRF-002");
    // DN-4(0.4.0) 화면 번호 — trfIds 와 같은 순서
    for (int i = 0; i < o04.path("trfIds").size(); i++) {
      String id = o04.path("trfIds").get(i).asString();
      assertThat(o04.path("trfNumbers").get(i).asString())
          .isEqualTo("T" + id.substring(id.length() - 3));
    }
    assertThat(o04.path("stops").get(0).path("trfNumbers").size())
        .isEqualTo(o04.path("stops").get(0).path("trfIds").size());
    assertThat(o04.path("loadRt").asInt()).isEqualTo(8);
    assertThat(o04.path("state").asString()).isEqualTo("PLANNING");
    assertThat(o04.path("orderId").asString()).startsWith("TO-20261201-");
    assertThat(java.time.Instant.parse(o04.path("plannedDepartureAt").asString()))
        .isEqualTo(java.time.Instant.parse("2026-12-01T15:30:00Z"));
    assertThat(o07.path("trfIds").toString()).contains("TRF-008").doesNotContain("TRF-007");
    // R-28 READY
    assertThat(o07.path("trfIds").toString())
        .contains("TRF-010")
        .doesNotContain("TRF-011", "TRF-012");
    assertThat(req("TRF-010").path("shipDate").asString()).isEqualTo(D);
    JsonNode r11 = req("TRF-011");
    assertThat(r11.path("shipDate").asString()).isEqualTo("2026-11-30");
    assertThat(r11.path("state").asString()).isEqualTo("HELD");
    assertThat(r11.path("reason").asString()).isEqualTo("READY_UNREACHABLE");
    assertThat(g.body().path("orders").size()).isEqualTo(2); // 냉장·냉동은 정규차가 없어 만들지 않음
    assertThat(g.body().path("unplannedReasons").toString()).contains("NO_REGULAR_VEHICLE");
    assertThat(req("TRF-002").path("acceptedQty").asInt()).isEqualTo(60);
    assertThat(req("TRF-003").path("reason").asString()).isEqualTo("ALLOCATION_PENDING");
    assertThat(req("TRF-004").path("reason").asString()).isEqualTo("NO_ROUTE");
    assertThat(req("TRF-005").path("reason").asString()).isEqualTo("MIXED_TEMPERATURE");
    assertThat(req("TRF-006").path("reason").asString()).isEqualTo("NO_REGULAR_VEHICLE");
    assertThat(req("TRF-007").path("reason").asString()).isEqualTo("OVER_VEHICLE_CAPACITY");
    assertThat(req("TRF-009").path("state").asString()).isEqualTo("EXCLUDED");
    JsonNode held =
        read(SUP, "/cdcs/" + CDC + "/transport-requests?shipDate=" + D + "&state=HELD").body();
    assertThat(held.path("items").size()).isEqualTo(5);
    // 다시 생성해도 지시서는 그대로(같은 칸 하나)
    Res g2 = call(SUP, "/cdcs/" + CDC + "/transport-orders/generations", Map.of("shipDate", D));
    assertThat(order(g2.body(), "RT-04", "AMBIENT").path("orderId").asString())
        .isEqualTo(o04.path("orderId").asString());

    String id04 = o04.path("orderId").asString();
    String id07 = o07.path("orderId").asString();

    // ---- 3) 옮기기 — 세종을 부산 노선에 더하면 07:30 넘음, 결행은 요청서가 있으면 막힘 ----
    Res mv =
        call(
            SUP,
            "/cdcs/" + CDC + "/transport-orders/" + id07 + "/request-moves",
            Map.of("trfId", "TRF-008", "toOrderId", id04));
    assertThat(mv.body().path("code").asString())
        .as(mv.body().toString())
        .isEqualTo("ROUTE_TIME_INFEASIBLE");
    assertThat(
            call(
                    SUP,
                    "/cdcs/" + CDC + "/transport-orders/" + id07 + "/skips",
                    Map.of("reason", "x"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("HAS_REQUESTS");

    // ---- 4) 계획완료 — 점유 → 운행 ----
    fleet.stubFor(
        post(urlPathEqualTo("/api/v1/internal/reservations"))
            .willReturn(okJson("{\"reservationId\":\"FRS-1\",\"state\":\"HELD\"}")));
    fleet.stubFor(
        post(urlPathMatching("/api/v1/internal/reservations/.*/releases"))
            .willReturn(okJson("{\"reservationId\":\"FRS-1\",\"state\":\"RELEASED\"}")));
    execution.stubFor(
        post(urlPathEqualTo("/api/v1/internal/trips"))
            .willReturn(okJson("{\"state\":\"SCHEDULED\"}")));
    assertThat(
            call(WORKER, "/cdcs/" + CDC + "/transport-orders/" + id04 + "/confirmations", Map.of())
                .status())
        .isEqualTo(403);
    Res c = call(SUP, "/cdcs/" + CDC + "/transport-orders/" + id04 + "/confirmations", Map.of());
    assertThat(c.status()).as(c.body().toString()).isEqualTo(200);
    assertThat(c.body().path("state").asString()).isEqualTo("DISPATCHED");
    assertThat(c.body().path("reservationId").asString()).isEqualTo("FRS-1");
    assertThat(c.body().path("dockCode").asString()).isEqualTo("CDC01-A-6-90-01-0-1");
    JsonNode dockReq =
        read(
            location
                .findAll(postRequestedFor(urlPathEqualTo("/api/v1/internal/dock-reservations")))
                .get(0)
                .getBodyAsString());
    assertThat(java.time.Instant.parse(dockReq.path("window").path("end").asString()))
        .isEqualTo(java.time.Instant.parse("2026-12-01T15:30:00Z")); // 계획 출차
    assertThat(dockReq.path("temperatureZone").asString()).isEqualTo("AMBIENT");
    assertThat(req("TRF-001").path("state").asString()).isEqualTo("ASSIGNED");
    var trips = execution.findAll(postRequestedFor(urlPathEqualTo("/api/v1/internal/trips")));
    JsonNode tripBody = read(trips.get(trips.size() - 1).getBodyAsString());
    assertThat(tripBody.path("tripId").asString()).isEqualTo(id04);
    assertThat(tripBody.path("dockCode").asString()).isEqualTo("CDC01-A-6-90-01-0-1");
    assertThat(tripBody.path("stops").size()).isEqualTo(2);
    assertThat(tripBody.path("stops").get(0).path("trfIds").toString()).contains("TRF-001");
    var res = fleet.findAll(postRequestedFor(urlPathEqualTo("/api/v1/internal/reservations")));
    JsonNode resBody = read(res.get(0).getBodyAsString());
    assertThat(java.time.Instant.parse(resBody.path("window").path("start").asString()))
        .isEqualTo(java.time.Instant.parse("2026-12-01T15:00:00Z"));
    assertThat(resBody.path("purposeRef").asString()).isEqualTo(id04);
    // 같은 요청 다시 — 그대로
    assertThat(
            call(SUP, "/cdcs/" + CDC + "/transport-orders/" + id04 + "/confirmations", Map.of())
                .body()
                .path("state")
                .asString())
        .isEqualTo("DISPATCHED");
    // 계획완료 뒤 새 버전 — 반영하지 않고 사유만
    feed(List.of(trf("TRF-001", 2, "MFC-BJG-01", "ALLOCATED", 40, 40, List.of("AMBIENT"), 4)));
    call(SUP, "/cdcs/" + CDC + "/transport-orders/generations", Map.of("shipDate", D));
    assertThat(req("TRF-001").path("state").asString()).isEqualTo("ASSIGNED");
    assertThat(req("TRF-001").path("reason").asString()).isEqualTo("NEWER_VERSION");

    // ---- 5) 계획완료 거절(점유 겹침) → 계획중, 불명(execution 503) → CONFIRMING → 이어 가기 ----
    fleet.stubFor(
        post(urlPathEqualTo("/api/v1/internal/reservations"))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("{\"code\":\"RESOURCE_OCCUPIED\",\"detail\":\"겹침\"}")));
    Res occ = call(SUP, "/cdcs/" + CDC + "/transport-orders/" + id07 + "/confirmations", Map.of());
    assertThat(occ.status()).isEqualTo(409);
    assertThat(occ.body().path("code").asString()).isEqualTo("RESOURCE_OCCUPIED");
    JsonNode back = read(SUP, "/cdcs/" + CDC + "/transport-orders/" + id07).body();
    assertThat(back.path("state").asString()).isEqualTo("PLANNING");
    assertThat(back.path("history").toString()).contains("CONFIRM_REJECTED");
    fleet.resetMappings();
    fleet.stubFor(
        post(urlPathEqualTo("/api/v1/internal/reservations"))
            .willReturn(okJson("{\"reservationId\":\"FRS-2\",\"state\":\"HELD\"}")));
    fleet.stubFor(
        post(urlPathMatching("/api/v1/internal/reservations/.*/releases"))
            .willReturn(okJson("{\"reservationId\":\"FRS-2\",\"state\":\"RELEASED\"}")));
    vehicle("VH-07A", "AMBIENT", 20);
    driver("DR-07A");
    execution.stubFor(
        post(urlPathEqualTo("/api/v1/internal/trips"))
            .atPriority(1)
            .willReturn(aResponse().withStatus(503)));
    // 그 온도대 도크 칸이 없으면(NO_DOCK) 도크 없이 계획완료 — 이력은 한 번
    location.stubFor(
        post(urlPathEqualTo("/api/v1/internal/dock-reservations"))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withStatus(409)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("{\"code\":\"NO_DOCK\",\"detail\":\"도크 칸 없음\"}")));
    int before =
        fleet.findAll(postRequestedFor(urlPathEqualTo("/api/v1/internal/reservations"))).size();
    Res pend = call(SUP, "/cdcs/" + CDC + "/transport-orders/" + id07 + "/confirmations", Map.of());
    assertThat(pend.body().path("state").asString())
        .as(pend.body().toString())
        .isEqualTo("CONFIRMING");
    execution.resetMappings();
    execution.stubFor(post(urlPathEqualTo("/api/v1/internal/trips")).willReturn(okJson("{}")));
    Res again =
        call(SUP, "/cdcs/" + CDC + "/transport-orders/" + id07 + "/confirmations", Map.of());
    assertThat(again.body().path("state").asString()).isEqualTo("DISPATCHED");
    assertThat(again.body().path("dockCode").isNull()).isTrue();
    assertThat(
            again.body().path("history").findValuesAsString("action").stream()
                .filter("NO_DOCK"::equals)
                .count())
        .isEqualTo(1);
    assertThat(
            fleet.findAll(postRequestedFor(urlPathEqualTo("/api/v1/internal/reservations"))).size())
        .isEqualTo(before + 1); // 점유는 한 번만(이어 가기는 운행만 다시)

    // ---- 6) 운행 상태 사본 ----
    Map<String, Object> up = new LinkedHashMap<>();
    up.put("tripState", "DEPARTED");
    up.put("tripVersion", 4);
    up.put("at", "2026-12-01T15:31:00Z");
    assertThat(call(SERVICE, "/internal/transport-orders/" + id04 + "/trip-updates", up).status())
        .isEqualTo(204);
    assertThat(req("TRF-002").path("state").asString()).isEqualTo("DEPARTED");
    assertThat(
            location.findAll(
                postRequestedFor(
                    urlPathEqualTo("/api/v1/internal/dock-reservations/DKR-1/releases"))))
        .isNotEmpty(); // 출차하면 도크를 푼다
    up.put("tripState", "COMPLETED");
    up.put("tripVersion", 9);
    up.put("handedOverTrfIds", List.of("TRF-001"));
    call(SERVICE, "/internal/transport-orders/" + id04 + "/trip-updates", up);
    assertThat(req("TRF-001").path("state").asString()).isEqualTo("HANDED_OVER");
    up.put("tripState", "DEPARTED");
    up.put("tripVersion", 5); // 낮은 버전은 무시
    call(SERVICE, "/internal/transport-orders/" + id04 + "/trip-updates", up);
    assertThat(
            read(SUP, "/cdcs/" + CDC + "/transport-orders/" + id04)
                .body()
                .path("tripState")
                .asString())
        .isEqualTo("COMPLETED");

    // ---- 7) 취소(계획완료 뒤, 출차 전) — 운행 취소·점유 해제, 요청서는 잔여 물량으로 ----
    execution.stubFor(
        post(urlPathMatching("/api/v1/internal/trips/.*/cancellations")).willReturn(okJson("{}")));
    Res cx =
        call(
            SUP,
            "/cdcs/" + CDC + "/transport-orders/" + id07 + "/cancellations",
            Map.of("reason", "차량 고장"));
    assertThat(cx.body().path("state").asString()).as(cx.body().toString()).isEqualTo("CANCELLED");
    assertThat(req("TRF-008").path("state").asString()).isEqualTo("ACCEPTED");
    assertThat(
            fleet.findAll(
                postRequestedFor(urlPathMatching("/api/v1/internal/reservations/FRS-2/releases"))))
        .isNotEmpty();

    // ---- 8) 조회·권한 ----
    JsonNode list = read(SUP, "/cdcs/" + CDC + "/transport-orders?shipDate=" + D).body();
    List<String> states = new ArrayList<>();
    list.path("items").forEach(x -> states.add(x.path("state").asString()));
    assertThat(states).containsExactlyInAnyOrder("DISPATCHED", "CANCELLED");
    assertThat(
            read(
                    TestJwt.operator("x", List.of("CDC_SUPERVISOR"), List.of("CDC99")),
                    "/cdcs/" + CDC + "/transport-orders")
                .status())
        .isEqualTo(403);
    outbound.stubFor(
        get(urlPathEqualTo("/api/v1/internal/transport-feed"))
            .atPriority(1)
            .willReturn(aResponse().withStatus(503)));
    assertThat(
            call(SUP, "/cdcs/" + CDC + "/transport-orders/generations", Map.of("shipDate", D))
                .status())
        .isEqualTo(503);
  }
}
