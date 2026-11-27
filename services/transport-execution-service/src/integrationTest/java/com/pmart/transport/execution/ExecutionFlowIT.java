package com.pmart.transport.execution;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.transport.execution.application.TripService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * 운행: 만들기(같은 본문 그대로·충돌) → 입차 → 상차(미상차 예외) → 출차(cdc-outbound 출발 검증·planning 알림) → 정차
 * 도착·인계(일치·불일치)·출발 → 복귀(점유 해제) · 권한(출발 센터·정차 MFC) · 출발 검증 불명 → 재시도 · 지연 플래그 · 시뮬레이터 명령. 외부는
 * WireMock, DB 는 실제 PostgreSQL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.execution.retry-interval=PT1H",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(
      name = "outbound",
      baseUrlProperties = "pmart.execution.cdc-outbound-base-url"),
  @ConfigureWireMock(name = "planning", baseUrlProperties = "pmart.execution.planning-base-url"),
  @ConfigureWireMock(name = "fleet", baseUrlProperties = "pmart.execution.fleet-base-url")
})
class ExecutionFlowIT extends PostgresKafkaContainerSupport {

  static final String CDC = "CDC01";
  static final String DC = TestJwt.operator("dc-1", List.of("CDC_SUPERVISOR"), List.of(CDC));
  static final String BJG =
      TestJwt.operator("m-bjg", List.of("MFC_SUPERVISOR"), List.of("MFC-BJG-01"));
  static final String BNG =
      TestJwt.operator("m-bng", List.of("MFC_SUPERVISOR"), List.of("MFC-BNG-01"));
  static final String OTHER = TestJwt.operator("x", List.of("CDC_SUPERVISOR"), List.of("CDC99"));
  static final String SERVICE = TestJwt.service("transport-planning-service");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired TripService trips;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @InjectWireMock("outbound")
  WireMockServer outbound;

  @InjectWireMock("planning")
  WireMockServer planning;

  @InjectWireMock("fleet")
  WireMockServer fleet;

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    planning.stubFor(
        WireMock.post(urlPathMatching("/api/v1/internal/transport-orders/.*/trip-updates"))
            .willReturn(aResponse().withStatus(204)));
    fleet.stubFor(
        WireMock.post(urlPathMatching("/api/v1/internal/reservations/.*/releases"))
            .willReturn(okJson("{\"state\":\"RELEASED\"}")));
  }

  record Res(int status, JsonNode body) {}

  Res call(String token, String path, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token);
                  }
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", token == null ? "pmart-cdc-simulation" : "it");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), parse(e.getBody()));
  }

  Res read(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), parse(e.getBody()));
  }

  JsonNode parse(String body) {
    return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
  }

  Map<String, Object> trip(String id, int version, LocalDate ship, Instant dep) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("tripId", id);
    m.put("orderVersion", version);
    m.put("cdcId", CDC);
    m.put("shipDate", ship.toString());
    m.put("routeId", "RT-04");
    m.put("tempClass", "AMBIENT");
    m.put("vehicleId", "VH-5T-04A");
    m.put("plateNo", "12가0001");
    m.put("driverId", "DR-04A");
    m.put("driverName", "기사 04A");
    m.put("reservationId", "FRS-" + id);
    m.put("dockCode", "CDC01-A-6-90-01-0-1");
    m.put("dockReservationId", "DKR-" + id);
    m.put("plannedLoadingStartAt", dep.minus(30, ChronoUnit.MINUTES).toString());
    m.put("plannedDepartureAt", dep.toString());
    m.put("plannedReturnAt", dep.plus(8, ChronoUnit.HOURS).toString());
    m.put("returnMinutes", 130);
    m.put(
        "stops",
        List.of(
            Map.of(
                "seq",
                1,
                "mfcId",
                "MFC-BJG-01",
                "plannedArrivalAt",
                dep.plus(120, ChronoUnit.MINUTES).toString(),
                "legMinutes",
                120,
                "trfIds",
                List.of("TRF-A", "TRF-B"),
                "trfNumbers",
                List.of("T000001", "T000002")),
            Map.of(
                "seq",
                2,
                "mfcId",
                "MFC-BNG-01",
                "plannedArrivalAt",
                dep.plus(170, ChronoUnit.MINUTES).toString(),
                "legMinutes",
                20,
                "trfIds",
                List.of("TRF-C"))));
    return m;
  }

  @Test
  void tripFromCheckInToCompletion() {
    // 출고일을 먼 미래로 두어 07:30 마감이 지연 판정에 끼지 않게
    LocalDate ship = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3);
    Instant dep = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    String id = "TO-T" + UUID.randomUUID().toString().substring(0, 6);
    String t = "/trips/" + id;

    // 1) 만들기 — 같은 본문 그대로, 낮은 버전 다른 본문 409
    Res c = call(SERVICE, "/internal/trips", trip(id, 3, ship, dep));
    assertThat(c.status()).as(c.body().toString()).isEqualTo(200);
    assertThat(c.body().path("state").asString()).isEqualTo("SCHEDULED");
    assertThat(c.body().path("dockCode").asString()).isEqualTo("CDC01-A-6-90-01-0-1"); // 0.3.0 DK-2
    // 0.6.0 DN-3·R-52 — TRF 화면 번호 사본(planning 이 준 값, 안 준 정차는 빈 문자열)
    assertThat(c.body().path("stops").get(0).path("trfNumbers").toString())
        .isEqualTo("[\"T000001\",\"T000002\"]");
    assertThat(c.body().path("stops").get(1).path("trfNumbers").toString()).isEqualTo("[\"\"]");
    for (JsonNode x : c.body().path("transfers")) {
      if ("TRF-A".equals(x.path("trfId").asString())) {
        assertThat(x.path("trfNumber").asString()).isEqualTo("T000001");
      }
    }
    assertThat(call(SERVICE, "/internal/trips", trip(id, 3, ship, dep)).status()).isEqualTo(200);
    Map<String, Object> other = trip(id, 2, ship, dep);
    other.put("vehicleId", "VH-OTHER");
    assertThat(call(SERVICE, "/internal/trips", other).body().path("code").asString())
        .isEqualTo("TRIP_CONFLICT");
    assertThat(read(OTHER, t).status()).isEqualTo(403);
    assertThat(read(BNG, t).status()).isEqualTo(200); // 정차 MFC 는 본다

    // 2) 입차 → 상차(TRF-B 미상차)
    Map<String, Object> load =
        Map.of(
            "transfers",
            List.of(
                Map.of("trfId", "TRF-A", "lpns", List.of("B1", "B2"), "rtCount", 1),
                Map.of("trfId", "TRF-C", "lpns", List.of("B3"), "rtCount", 1)));
    assertThat(call(DC, t + "/loading-completions", load).status()).isEqualTo(409); // 입차 전
    assertThat(call(BJG, t + "/check-ins", Map.of()).status()).isEqualTo(403);
    assertThat(
            call(DC, t + "/check-ins", Map.of("location", "칠곡 DC 5층 도크"))
                .body()
                .path("state")
                .asString())
        .isEqualTo("CHECKED_IN");
    assertThat(
            call(
                    DC,
                    t + "/loading-completions",
                    Map.of("transfers", List.of(Map.of("trfId", "TRF-ZZ", "lpns", List.of("B9")))))
                .body()
                .path("code")
                .asString())
        .isEqualTo("UNKNOWN_TRF");
    Res l = call(DC, t + "/loading-completions", load);
    assertThat(l.body().path("state").asString()).as(l.body().toString()).isEqualTo("LOADED");
    assertThat(l.body().path("boxCount").asInt()).isEqualTo(3);
    assertThat(l.body().path("incidents").toString()).contains("NOT_LOADED", "TRF-B");
    assertThat(call(DC, t + "/stops/1/arrivals", Map.of()).status()).isEqualTo(409); // 출차 전

    // 3) 출차 → cdc-outbound 출발 검증(실은 TRF 만)·planning 알림
    outbound.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/departure-validations"))
            .willReturn(okJson("{\"overallResult\":\"MATCHED\",\"departureNumber\":\"S123456\"}")));
    Res d = call(DC, t + "/departures", Map.of("temperatureC", 18.5));
    assertThat(d.body().path("state").asString()).as(d.body().toString()).isEqualTo("DEPARTED");
    String depId = d.body().path("departureId").asString();
    assertThat(depId).startsWith("DEP-");
    assertThat(d.body().path("outboundLinkage").asString()).isEqualTo("ACKNOWLEDGED");
    assertThat(d.body().path("outboundResult").asString()).isEqualTo("MATCHED");
    assertThat(d.body().path("departureNumber").asString()).isEqualTo("S123456"); // 0.6.0
    var sent =
        outbound.findAll(
            postRequestedFor(urlPathEqualTo("/api/v1/internal/departure-validations")));
    JsonNode body = parse(sent.get(0).getBodyAsString());
    assertThat(sent.get(0).getHeader("Idempotency-Key")).isEqualTo(depId);
    assertThat(body.path("source").asString()).isEqualTo("REAL");
    assertThat(body.path("trfCompositions").findValuesAsString("trfId"))
        .containsExactly("TRF-A", "TRF-C");
    assertThat(body.path("trfCompositions").get(0).path("mfcId").asString())
        .isEqualTo("MFC-BJG-01");
    assertThat(call(DC, t + "/departures", Map.of()).body().path("departureId").asString())
        .isEqualTo(depId);
    assertThat(
            call(SERVICE, "/internal/trips/" + id + "/cancellations", Map.of("reason", "x"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("ALREADY_DEPARTED");
    assertThat(read(SERVICE, "/internal/departures/" + depId).body().path("trfCompositions").size())
        .isEqualTo(2);

    // 4) 정차 1 — 도착·인계 일치·출발
    assertThat(call(BNG, t + "/stops/2/arrivals", Map.of()).body().path("code").asString())
        .isEqualTo("PREVIOUS_STOP_OPEN");
    Res a1 = call(BJG, t + "/stops/1/arrivals", Map.of());
    assertThat(a1.body().path("stops").get(0).path("state").asString()).isEqualTo("ARRIVED");
    assertThat(a1.body().path("stops").get(0).path("delayed").asBoolean()).isFalse();
    // TE-1(0.4.0): 처음 도착 = StopArrived 하나(tms.trip.v1), trfIds = 상차한 TRF 만(TRF-B 미상차는 뺌)
    assertThat(call(BJG, t + "/stops/1/arrivals", Map.of()).status()).isEqualTo(200);
    var arrived =
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'StopArrived' AND topic = 'tms.trip.v1'"
                + " AND aggregateid = ?",
            String.class,
            id);
    assertThat(arrived).hasSize(1);
    JsonNode sa = mapper.readTree(arrived.getFirst()).path("payload");
    assertThat(sa.path("mfcId").asString()).isEqualTo("MFC-BJG-01");
    assertThat(sa.path("sequence").asInt()).isEqualTo(1);
    assertThat(sa.path("stopId").asString()).isEqualTo(id + "-1");
    assertThat(sa.path("trfIds").toString()).isEqualTo("[\"TRF-A\"]");
    assertThat(call(DC, t + "/physical-ends", Map.of()).body().path("code").asString())
        .isEqualTo("STOPS_OPEN");
    assertThat(call(BJG, t + "/stops/1/departures", Map.of()).body().path("code").asString())
        .isEqualTo("HANDOVER_REQUIRED");
    Map<String, Object> ho1 =
        Map.of(
            "receiverName",
            "부산진 담당",
            "transfers",
            List.of(Map.of("trfId", "TRF-A", "receivedLpns", List.of("B2", "B1"))));
    assertThat(call(BNG, t + "/stops/1/handovers", ho1).status()).isEqualTo(403);
    Res h1 = call(BJG, t + "/stops/1/handovers", ho1);
    assertThat(h1.body().path("stops").get(0).path("handover").path("result").asString())
        .as(h1.body().toString())
        .isEqualTo("MATCHED");
    // R-29(0.5.0): 인계 = 정차 자동 출발(마지막 정차가 아니라 운행은 그대로)
    assertThat(h1.body().path("stops").get(0).path("state").asString()).isEqualTo("DEPARTED");
    assertThat(h1.body().path("state").asString()).isEqualTo("DEPARTED");
    assertThat(call(BJG, t + "/stops/1/handovers", ho1).status()).isEqualTo(200);
    assertThat(
            call(
                    BJG,
                    t + "/stops/1/handovers",
                    Map.of(
                        "transfers",
                        List.of(Map.of("trfId", "TRF-A", "receivedLpns", List.of("B1")))))
                .body()
                .path("code")
                .asString())
        .isEqualTo("HANDOVER_RECORDED");
    assertThat(
            call(BJG, t + "/stops/1/departures", Map.of())
                .body()
                .path("stops")
                .get(0)
                .path("state")
                .asString())
        .isEqualTo("DEPARTED");

    // 5) 정차 2 — 시뮬레이터 명령으로 도착, 인계 불일치(초과 BX) → 예외
    assertThat(
            call(null, "/internal/trips/" + id + "/commands/stops-2-arrivals", Map.of()).status())
        .isEqualTo(200);
    Res h2 =
        call(
            BNG,
            t + "/stops/2/handovers",
            Map.of(
                "transfers",
                List.of(Map.of("trfId", "TRF-C", "receivedLpns", List.of("B3", "BX")))));
    JsonNode s2 = h2.body().path("stops").get(1).path("handover");
    assertThat(s2.path("result").asString()).isEqualTo("MISMATCHED");
    assertThat(s2.path("extraLpns").toString()).contains("BX");
    assertThat(h2.body().path("incidents").toString()).contains("QTY_MISMATCH");
    // R-29: 마지막 정차 인계 = 정차 출발 + 운행 자동 COMPLETED, 차량 점유는 복귀 시간 뒤라 아직 안 풂
    assertThat(h2.body().path("stops").get(1).path("state").asString()).isEqualTo("DEPARTED");
    assertThat(h2.body().path("state").asString()).as(h2.body().toString()).isEqualTo("COMPLETED");
    trips.retry();
    assertThat(
            fleet.findAll(
                postRequestedFor(
                    urlPathEqualTo("/api/v1/internal/reservations/FRS-" + id + "/releases"))))
        .isEmpty();

    // 6) DC 복귀 등록 → 점유 바로 해제(이미 COMPLETED), planning 에 인계된 TRF
    Res e = call(DC, t + "/physical-ends", Map.of());
    assertThat(e.body().path("state").asString()).as(e.body().toString()).isEqualTo("COMPLETED");
    assertThat(
            fleet.findAll(
                postRequestedFor(
                    urlPathEqualTo("/api/v1/internal/reservations/FRS-" + id + "/releases"))))
        .hasSize(1);
    var ups =
        planning.findAll(
            postRequestedFor(
                urlPathEqualTo("/api/v1/internal/transport-orders/" + id + "/trip-updates")));
    JsonNode last = parse(ups.get(ups.size() - 1).getBodyAsString());
    assertThat(last.path("tripState").asString()).isEqualTo("COMPLETED");
    assertThat(last.path("handedOverTrfIds").toString())
        .contains("TRF-A", "TRF-C")
        .doesNotContain("TRF-B");

    // 7) 예외 등록·해결, 목록
    Res inc = call(DC, t + "/incidents", Map.of("type", "DELAY", "description", "고속도로 정체"));
    String incId = null;
    for (JsonNode x : inc.body().path("incidents")) {
      if ("DELAY".equals(x.path("type").asString())) {
        incId = x.path("incidentId").asString();
      }
    }
    assertThat(incId).isNotNull();
    assertThat(
            call(DC, t + "/incidents/" + incId + "/resolutions", Map.of("resolution", "도착함"))
                .body()
                .path("incidents")
                .toString())
        .contains("RESOLVED");
    JsonNode list = read(DC, "/cdcs/" + CDC + "/trips?shipDate=" + ship).body();
    assertThat(list.path("items").findValuesAsString("tripId")).contains(id);
    assertThat(read(OTHER, "/cdcs/" + CDC + "/trips").status()).isEqualTo(403);
    assertThat(
            read(BNG, "/mfcs/MFC-BNG-01/inbound-trips")
                .body()
                .path("items")
                .findValuesAsString("tripId"))
        .contains(id);
    for (JsonNode x : read(BNG, "/mfcs/MFC-BNG-01/inbound-trips").body().path("items")) {
      if (id.equals(x.path("tripId").asString())) {
        assertThat(x.path("departureNumber").asString()).isEqualTo("S123456");
      }
    }
  }

  @Test
  void lateTripIsDelayedAndOutboundRetried() {
    LocalDate ship = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3);
    Instant dep =
        Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS); // 2시간 늦음
    String id = "TO-L" + UUID.randomUUID().toString().substring(0, 6);
    assertThat(call(SERVICE, "/internal/trips", trip(id, 1, ship, dep)).status()).isEqualTo(200);
    JsonNode v = read(DC, "/trips/" + id).body();
    assertThat(v.path("delayed").asBoolean()).isTrue();
    assertThat(
            read(DC, "/cdcs/" + CDC + "/trips?delayed=true")
                .body()
                .path("items")
                .findValuesAsString("tripId"))
        .contains(id);

    // 시뮬레이터 명령(토큰 없음) — 출차 source 기본 SIMULATED, 출발 검증 503 → UNKNOWN_RESULT → 재시도로 ACKNOWLEDGED
    String cmd = "/internal/trips/" + id + "/commands/";
    call(null, cmd + "check-ins", Map.of());
    call(
        null,
        cmd + "loading-completions",
        Map.of(
            "transfers",
            List.of(
                Map.of("trfId", "TRF-A", "lpns", List.of("L1")),
                Map.of("trfId", "TRF-C", "lpns", List.of("L2")))));
    outbound.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/departure-validations"))
            .atPriority(1)
            .willReturn(aResponse().withStatus(503)));
    Res d = call(null, cmd + "departures", Map.of());
    assertThat(d.body().path("state").asString()).as(d.body().toString()).isEqualTo("DEPARTED");
    assertThat(d.body().path("outboundLinkage").asString()).isEqualTo("UNKNOWN_RESULT");
    outbound.resetMappings();
    outbound.stubFor(
        WireMock.post(urlPathEqualTo("/api/v1/internal/departure-validations"))
            .willReturn(okJson("{\"overallResult\":\"PARTIALLY_MATCHED\"}")));
    trips.retry();
    JsonNode after = read(DC, "/trips/" + id).body();
    assertThat(after.path("outboundLinkage").asString()).isEqualTo("ACKNOWLEDGED");
    assertThat(after.path("outboundResult").asString()).isEqualTo("PARTIALLY_MATCHED");
    var sent =
        outbound.findAll(
            postRequestedFor(urlPathEqualTo("/api/v1/internal/departure-validations")));
    assertThat(parse(sent.get(sent.size() - 1).getBodyAsString()).path("source").asString())
        .isEqualTo("SIMULATED");

    // R-29 배포 전 자료: 정차는 다 (수동) 출발했는데 운행은 DEPARTED → 주기 작업이 종료
    jdbc.update(
        "UPDATE trip_stop SET state = 'DEPARTED', departed_at = now() WHERE trip_id = ?", id);
    trips.retry();
    JsonNode done = read(DC, "/trips/" + id).body();
    assertThat(done.path("state").asString()).as(done.toString()).isEqualTo("COMPLETED");
    assertThat(done.path("stops").findValuesAsString("state")).containsOnly("DEPARTED");
  }
}
