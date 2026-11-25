package com.pmart.cdc.outbound;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 출고 흐름(0.3.0): TRF 인수 → cdc-inventory 할당(WireMock) → 재전송·충돌 → 재고 거절·불명 후 재시도 → Wave 생성(PLANNED)·거절
 * → 릴리스(할당 조회로 구역·층별 PICK Task, WaveStateChanged) → PDA 다음 작업 받기(한 사람 한 작업)·착수(담당만) → 피킹 실적(초과·없는 줄
 * 거절, 부족 → PARTIAL·PickShortageReported) → Wave WORK_DONE·TRF PICKED. PostgreSQL·Kafka 실제.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.messaging.outbox.delete-after-insert=false",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(
      name = "inventory",
      baseUrlProperties = "pmart.outbound.cdc-inventory-base-url"),
  @ConfigureWireMock(name = "location", baseUrlProperties = "pmart.outbound.cdc-location-base-url")
})
@TestMethodOrder(MethodOrderer.MethodName.class) // 주 흐름의 outbox 수 세기가 취소 시나리오(CDC02) 사건과 섞이지 않게 먼저
class OutboundFlowIT extends PostgresKafkaContainerSupport {

  static final String CENTER = "CDC01";
  static final String SERVICE = TestJwt.service("replenishment-service");
  static final String OPS = TestJwt.operator("ops-1", List.of("CDC_MANAGER"), List.of(CENTER));
  static final String W1 = TestJwt.operator("w1", List.of("CDC_WORKER"), List.of(CENTER));
  static final String W2 = TestJwt.operator("w2", List.of("CDC_WORKER"), List.of(CENTER));
  static final String W3 = TestJwt.operator("w3", List.of("CDC_WORKER"), List.of(CENTER));
  static final String ALLOC = "/api/v1/internal/allocations";
  static final String DUE =
      Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("inventory")
  WireMockServer inventory;

  @InjectWireMock("location")
  WireMockServer location;

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    // 위치 서비스 기본 — 방식 없음(모두 단건). 묶음 시나리오는 더 높은 우선순위로 덮는다.
    location.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/api/v1/internal/location-lookups"))
            .atPriority(10)
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"items\":[],\"missing\":[]}")));
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String path, Object body) {
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
            .body(body == null ? Map.of() : body)
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  JsonNode read(String body) {
    return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
  }

  static Map<String, Object> trf(String trfId, int a, int b) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("trfId", trfId);
    m.put("trfVersion", 1);
    m.put("cdcId", CENTER);
    m.put("mfcId", "MFC-BJG-01");
    m.put("approvalRef", "APV-1");
    m.put("deadlineMeaning", "ARRIVAL");
    m.put("dueAt", DUE);
    m.put(
        "lines",
        List.of(
            Map.of("sku", "E-00000001", "approvedQty", a),
            Map.of("sku", "E-00000002", "approvedQty", b)));
    return m;
  }

  void stubAllocate(String trfId, int status, String body) {
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(ALLOC))
            .withHeader("Idempotency-Key", equalTo("outbound-alloc-" + trfId + "-v1"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader(
                        "Content-Type",
                        status >= 400 ? "application/problem+json" : "application/json")
                    .withBody(body)));
  }

  int outbox(String type) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE type = ? AND topic = 'cdc.outbound.v1'",
            Integer.class,
            type);
    return n == null ? 0 : n;
  }

  @Test
  void intakeWaveTasksPicking() {
    String trf1 = "TRF-" + UUID.randomUUID();
    String trf2 = "TRF-" + UUID.randomUUID();
    String trf3 = "TRF-" + UUID.randomUUID();
    stubAllocate(
        trf1,
        200,
        """
        {"allocationId":"ALC-1","state":"PARTIAL","lines":[
          {"sku":"E-00000001","requestedQty":6,"allocatedQty":5},
          {"sku":"E-00000002","requestedQty":4,"allocatedQty":3}]}
        """);
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(ALLOC + "/ALC-1"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"allocationId":"ALC-1","state":"PARTIAL","lines":[
                          {"sku":"E-00000001","lots":[{"lot":"L1","locationCode":"CDC01-C-5-61-01-1-1","qty":5,"expiresAt":"2027-01-31"}]},
                          {"sku":"E-00000002","lots":[{"lot":"L2","locationCode":"CDC01-B-6-61-01-1-1","qty":3}]}]}
                        """)));
    stubAllocate(trf2, 422, "{\"code\":\"SKU_NOT_HANDLED\",\"detail\":\"취급 OFF\"}");
    stubAllocate(trf3, 503, "{}");

    // 1) 인수 → 할당(부분)
    Res r1 = post(SERVICE, "/internal/transfer-intakes", trf(trf1, 6, 4));
    assertThat(r1.status()).as(r1.body().toString()).isEqualTo(202);
    assertThat(r1.body().path("status").asString()).isEqualTo("ACCEPTED");
    JsonNode i1 = get(SERVICE, "/internal/transfer-intakes/" + trf1).body();
    JsonNode rd0 = readiness(trf1);
    assertThat(rd0.path("readiness").asString()).isEqualTo("NOT_STARTED");
    assertThat(rd0.path("blockers").toString()).contains("NOT_IN_WAVE");
    assertThat(get(SERVICE, "/internal/outbound-readiness?trfId=TRF-NONE").status()).isEqualTo(404);
    assertThat(i1.path("state").asString()).isEqualTo("PARTIALLY_ALLOCATED");
    assertThat(i1.path("allocationId").asString()).isEqualTo("ALC-1");
    assertThat(i1.path("progress").get(0).path("allocatedQty").asInt()).isEqualTo(5);
    // 같은 본문 재전송 → 재고를 다시 부르지 않음, 다른 본문 → 409
    assertThat(post(SERVICE, "/internal/transfer-intakes", trf(trf1, 6, 4)).status())
        .isEqualTo(202);
    inventory.verify(
        1,
        postRequestedFor(urlPathEqualTo(ALLOC))
            .withHeader("Idempotency-Key", equalTo("outbound-alloc-" + trf1 + "-v1")));
    Res conflict = post(SERVICE, "/internal/transfer-intakes", trf(trf1, 7, 4));
    assertThat(conflict.body().path("code").asString()).isEqualTo("TRANSFER_INTAKE_CONFLICT");

    // 2) 재고 거절 → ALLOCATION_FAILED, 불명 → ALLOCATING 뒤 재시도로 ALLOCATED
    post(SERVICE, "/internal/transfer-intakes", trf(trf2, 1, 1));
    assertThat(get(SERVICE, "/internal/transfer-intakes/" + trf2).body().path("state").asString())
        .isEqualTo("ALLOCATION_FAILED");
    post(SERVICE, "/internal/transfer-intakes", trf(trf3, 1, 1));
    assertThat(get(SERVICE, "/internal/transfer-intakes/" + trf3).body().path("state").asString())
        .isEqualTo("ALLOCATING");
    stubAllocate(
        trf3,
        200,
        "{\"allocationId\":\"ALC-3\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":1},{\"sku\":\"E-00000002\",\"allocatedQty\":1}]}");
    post(SERVICE, "/internal/transfer-intakes", trf(trf3, 1, 1));
    assertThat(get(SERVICE, "/internal/transfer-intakes/" + trf3).body().path("state").asString())
        .isEqualTo("ALLOCATED");

    // 3) Wave 생성 — 확보 안 된 TRF·이미 Wave 에 든 TRF 거절
    assertThat(
            post(OPS, "/waves", Map.of("cdcId", CENTER, "trfIds", List.of(trf2)))
                .body()
                .path("code")
                .asString())
        .isEqualTo("INVALID_STATE");
    Res wave = post(OPS, "/waves", Map.of("cdcId", CENTER, "trfIds", List.of(trf1)));
    assertThat(wave.status()).as(wave.body().toString()).isEqualTo(201);
    assertThat(wave.body().path("state").asString()).isEqualTo("PLANNED");
    String waveId = wave.body().path("waveId").asString();
    assertThat(
            post(OPS, "/waves", Map.of("cdcId", CENTER, "trfIds", List.of(trf1)))
                .body()
                .path("code")
                .asString())
        .isEqualTo("TRF_ALREADY_IN_WAVE");

    // 4) 릴리스 → 구역·층별 PICK Task 2개
    Res rel = post(OPS, "/waves/" + waveId + "/releases", null);
    assertThat(rel.status()).as(rel.body().toString()).isEqualTo(202);
    JsonNode w = get(OPS, "/waves/" + waveId).body();
    assertThat(w.path("state").asString()).isEqualTo("RELEASED");
    assertThat(w.path("taskSummary").path("total").asInt()).isEqualTo(2);
    assertThat(outbox("WaveStateChanged")).isEqualTo(1);

    // 5) 다음 작업 받기 — 한 사람 한 작업, 들고 있으면 같은 작업, 남은 게 없으면 409, 남의 이름 403
    Res n1 = post(W1, "/cdcs/CDC01/workers/w1/next-tasks", Map.of());
    assertThat(n1.status()).as(n1.body().toString()).isEqualTo(201);
    assertThat(n1.body().path("state").asString()).isEqualTo("ACCEPTED");
    String t1 = n1.body().path("taskId").asString();
    Res again = post(W1, "/cdcs/CDC01/workers/w1/next-tasks", Map.of());
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("taskId").asString()).isEqualTo(t1);
    Res n2 = post(W2, "/cdcs/CDC01/workers/w2/next-tasks", Map.of());
    assertThat(n2.status()).isEqualTo(201);
    String t2 = n2.body().path("taskId").asString();
    assertThat(t2).isNotEqualTo(t1);
    assertThat(
            post(W3, "/cdcs/CDC01/workers/w3/next-tasks", Map.of()).body().path("code").asString())
        .isEqualTo("NO_TASK_AVAILABLE");
    assertThat(post(W3, "/cdcs/CDC01/workers/w1/next-tasks", Map.of()).status()).isEqualTo(403);

    // 6) 착수 — 담당만
    assertThat(post(W2, "/cdcs/CDC01/tasks/" + t1 + "/starts", null).body().path("code").asString())
        .isEqualTo("NOT_ASSIGNEE");
    Res s1 = post(W1, "/cdcs/CDC01/tasks/" + t1 + "/starts", null);
    assertThat(s1.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(get(OPS, "/waves/" + waveId).body().path("state").asString())
        .isEqualTo("IN_PROGRESS");

    // 7) 피킹 실적 — 초과 409, 없는 줄 400, 다 집으면 DONE
    JsonNode line1 = s1.body().path("lines").get(0);
    Map<String, Object> over =
        Map.of(
            "workerId",
            "w1",
            "lines",
            List.of(
                Map.of(
                    "sku", line1.path("sku").asString(),
                    "lot", line1.path("lot").asString(),
                    "locationCode", line1.path("locationCode").asString(),
                    "pickedQty", line1.path("plannedQty").asInt() + 1)));
    assertThat(
            post(W1, "/cdcs/CDC01/tasks/" + t1 + "/pick-results", over)
                .body()
                .path("code")
                .asString())
        .isEqualTo("PICK_EXCEEDS_ALLOCATION");
    Map<String, Object> unknown =
        Map.of(
            "workerId",
            "w1",
            "lines",
            List.of(
                Map.of(
                    "sku",
                    "E-00000009",
                    "lot",
                    "X",
                    "locationCode",
                    "CDC01-C-5-61-09-1-1",
                    "pickedQty",
                    1)));
    assertThat(post(W1, "/cdcs/CDC01/tasks/" + t1 + "/pick-results", unknown).status())
        .isEqualTo(400);
    Map<String, Object> ok =
        Map.of(
            "workerId",
            "w1",
            "lines",
            List.of(
                Map.of(
                    "sku", line1.path("sku").asString(),
                    "lot", line1.path("lot").asString(),
                    "locationCode", line1.path("locationCode").asString(),
                    "pickedQty", line1.path("plannedQty").asInt())));
    assertThat(post(W1, "/cdcs/CDC01/tasks/" + t1 + "/pick-results", ok).status()).isEqualTo(202);
    assertThat(get(OPS, "/cdcs/CDC01/tasks/" + t1).body().path("state").asString())
        .isEqualTo("DONE");

    // 8) 두 번째 작업 — 1 부족 → PARTIAL·PickShortageReported, Wave WORK_DONE, TRF PICKED
    JsonNode line2 =
        post(W2, "/cdcs/CDC01/tasks/" + t2 + "/starts", null).body().path("lines").get(0);
    int planned = line2.path("plannedQty").asInt();
    Map<String, Object> shortPick =
        Map.of(
            "workerId",
            "w2",
            "lines",
            List.of(
                Map.of(
                    "sku",
                    line2.path("sku").asString(),
                    "lot",
                    line2.path("lot").asString(),
                    "locationCode",
                    line2.path("locationCode").asString(),
                    "pickedQty",
                    planned - 1,
                    "shortQty",
                    1,
                    "shortReason",
                    "NOT_FOUND")));
    assertThat(post(W2, "/cdcs/CDC01/tasks/" + t2 + "/pick-results", shortPick).status())
        .isEqualTo(202);
    assertThat(get(OPS, "/cdcs/CDC01/tasks/" + t2).body().path("state").asString())
        .isEqualTo("PARTIAL");
    assertThat(outbox("PickShortageReported")).isEqualTo(1);
    assertThat(get(OPS, "/waves/" + waveId).body().path("state").asString()).isEqualTo("WORK_DONE");
    JsonNode done = get(SERVICE, "/internal/transfer-intakes/" + trf1).body();
    assertThat(done.path("state").asString()).isEqualTo("PICKED");
    int picked = 0;
    for (JsonNode p : done.path("progress")) {
      picked += p.path("pickedQty").asInt();
    }
    assertThat(picked).isEqualTo(7); // 5 + (3 − 1)
    Res pickedCancel =
        postKey(
            OPS,
            "/cdcs/CDC01/transfers/" + trf1 + "/pre-departure-cancellations",
            UUID.randomUUID().toString(),
            Map.of("reason", "TRANSFER_CANCELLED", "scope", "FULL"));
    assertThat(pickedCancel.body().path("code").asString()).isEqualTo("PICKING_STARTED");
    JsonNode rdPicked = readiness(trf1);
    assertThat(rdPicked.path("readiness").asString()).isEqualTo("PICKED");
    assertThat(rdPicked.path("blockers").toString()).contains("UNSORTED_QTY");
    JsonNode list = get(OPS, "/cdcs/CDC01/tasks?waveId=" + waveId).body();
    assertThat(list.path("items").size()).isEqualTo(2);

    // 9) 분류 — 롤테이너(RT) 하나에 BOX 둘. 실피킹보다 많으면 409, 같은 LPN 다시 409
    String rt = "RT-" + UUID.randomUUID().toString().substring(0, 8);
    String b1 = "BOX-" + UUID.randomUUID().toString().substring(0, 8);
    String b2 = "BOX-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> over2 = new LinkedHashMap<>();
    over2.put("waveId", waveId);
    over2.put("trfId", trf1);
    over2.put("workerId", "w1");
    over2.put(
        "lpns",
        List.of(
            box(
                "BOX-X" + UUID.randomUUID().toString().substring(0, 6),
                null,
                "E-00000002",
                "L2",
                3)));
    assertThat(post(OPS, "/sorting-results", over2).body().path("code").asString())
        .isEqualTo("SORT_EXCEEDS_PICKED");
    Map<String, Object> rtLpn = new LinkedHashMap<>();
    rtLpn.put("lpn", rt);
    rtLpn.put("unitType", "RT");
    rtLpn.put("state", "READY");
    rtLpn.put("contents", List.of());
    Map<String, Object> sortBody = new LinkedHashMap<>();
    sortBody.put("waveId", waveId);
    sortBody.put("trfId", trf1);
    sortBody.put("workerId", "w1");
    sortBody.put(
        "lpns",
        List.of(rtLpn, box(b1, rt, "E-00000001", "L1", 5), box(b2, rt, "E-00000002", "L2", 2)));
    Res sorted = post(OPS, "/sorting-results", sortBody);
    assertThat(sorted.status()).as(sorted.body().toString()).isEqualTo(202);
    assertThat(post(OPS, "/sorting-results", sortBody).body().path("code").asString())
        .isEqualTo("LPN_ALREADY_SEALED");
    String wr = sorted.body().path("resultId").asString();
    assertThat(get(OPS, "/work-results/" + wr).body().path("kind").asString()).isEqualTo("SORTING");

    JsonNode rdSorted = readiness(trf1);
    assertThat(rdSorted.path("readiness").asString()).isEqualTo("SORTED");
    assertThat(rdSorted.path("blockers").toString()).contains("NOT_INSPECTED");
    assertThat(rdSorted.path("readyLpnCount").asInt()).isZero();

    // 10) 검품 전 상차 409, 검품(RT → BOX 둘 합격) 뒤 상차, 다른 운행 409
    Map<String, Object> load = new LinkedHashMap<>();
    load.put("trfId", trf1);
    load.put("tripId", "TRIP-1");
    load.put("vehicleId", "VH-1");
    load.put("workerId", "w1");
    load.put("action", "LOADED");
    load.put("lpns", List.of(Map.of("lpn", rt, "compartment", "AMBIENT", "sealNo", "SEAL-1")));
    assertThat(post(OPS, "/loading-results", load).body().path("code").asString())
        .isEqualTo("LPN_NOT_PASSED");
    Map<String, Object> insp = new LinkedHashMap<>();
    insp.put("trfId", trf1);
    insp.put("inspectorId", "q1");
    insp.put("items", List.of(Map.of("lpn", rt, "result", "PASSED")));
    assertThat(post(OPS, "/inspection-results", insp).status()).isEqualTo(202);
    JsonNode rdInsp = readiness(trf1);
    assertThat(rdInsp.path("readiness").asString()).as(rdInsp.toString()).isEqualTo("INSPECTED");
    assertThat(rdInsp.path("readyLpnCount").asInt()).isEqualTo(2);
    assertThat(rdInsp.path("readyQty").asInt()).isEqualTo(7);
    assertThat(rdInsp.path("blockers").size()).isZero();
    assertThat(rdInsp.path("earliestReadyAt").isNull()).isFalse();
    assertThat(post(OPS, "/loading-results", load).status()).isEqualTo(202);
    assertThat(readiness(trf1).path("readiness").asString()).isEqualTo("LOADED");
    Map<String, Object> other = new LinkedHashMap<>(load);
    other.put("tripId", "TRIP-2");
    assertThat(post(OPS, "/loading-results", other).body().path("code").asString())
        .isEqualTo("TRIP_MISMATCH");

    // 11) 출발 검증(SIMULATED) — trf1 일치(TOUT·잔여 해제·매니페스트), trf3 불일치(실은 것 없음)
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/api/v1/internal/departure-postings"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"APPLIED\",\"requestId\":\"k\",\"resultId\":\"CMT-9\",\"appliedQty\":7}")));
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo(ALLOC + "/ALC-1/releases"))
            .willReturn(aResponse().withStatus(503).withBody("{}"))); // 잔여 해제 불명 → PENDING
    String dep = "DEP-" + UUID.randomUUID();
    Map<String, Object> depBody = new LinkedHashMap<>();
    depBody.put("departureId", dep);
    depBody.put("tripId", "TRIP-1");
    depBody.put("vehicleId", "VH-1");
    depBody.put("departedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    depBody.put("source", "SIMULATED");
    depBody.put(
        "trfCompositions",
        List.of(
            Map.of(
                "trfId",
                trf1,
                "mfcId",
                "MFC-BJG-01",
                "lpns",
                List.of(b1, b2),
                "sealNos",
                List.of("SEAL-1")),
            Map.of("trfId", trf3, "lpns", List.of())));
    Res v = post(SERVICE, "/internal/departure-validations", depBody);
    assertThat(v.status()).as(v.body().toString()).isEqualTo(200);
    assertThat(v.body().path("overallResult").asString()).isEqualTo("PARTIALLY_MATCHED");
    JsonNode t1v = v.body().path("transfers").get(0);
    assertThat(t1v.path("result").asString()).isEqualTo("MATCHED");
    assertThat(t1v.path("postingStatus").asString()).isEqualTo("APPLIED");
    assertThat(t1v.path("commitId").asString()).isEqualTo("CMT-9");
    assertThat(t1v.path("departedQty").asInt()).isEqualTo(7);
    assertThat(v.body().path("transfers").get(1).path("result").asString()).isEqualTo("MISMATCHED");
    inventory.verify(
        1,
        postRequestedFor(urlPathEqualTo("/api/v1/internal/departure-postings"))
            .withRequestBody(matchingJsonPath("$.departureSource", equalTo("SIMULATED")))
            .withRequestBody(matchingJsonPath("$.toMfcId", equalTo("MFC-BJG-01")))
            .withRequestBody(matchingJsonPath("$.lines.length()", equalTo("2"))));
    int releases = releases();
    assertThat(releases).isGreaterThanOrEqualTo(1);
    inventory.verify(
        releases,
        postRequestedFor(urlPathEqualTo(ALLOC + "/ALC-1/releases"))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("RESIDUAL_CLOSED"))));
    assertThat(outbox("TruckDepartureValidated")).isEqualTo(2);
    assertThat(outbox("OutboundDiscrepancyRaised")).isEqualTo(1);
    assertThat(outbox("TransferShipmentManifestIssued")).isEqualTo(1);

    assertThat(residual(dep, trf1)).isEqualTo("PENDING");
    assertThat(t1v.path("residualStatus").asString()).isEqualTo("PENDING");

    // 같은 본문 다시 — 재고가 거절(404) 하면 FAILED 와 사유를 남긴다
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo(ALLOC + "/ALC-1/releases"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("{\"code\":\"NOT_FOUND\",\"detail\":\"할당 ALC-1 이 없습니다.\"}")));
    JsonNode failed =
        post(SERVICE, "/internal/departure-validations", depBody).body().path("transfers").get(0);
    assertThat(failed.path("residualStatus").asString()).isEqualTo("FAILED");
    assertThat(failed.path("residualReason").asString()).contains("404").contains("NOT_FOUND");
    assertThat(releases()).isEqualTo(releases + 1);
    releases = releases();

    // 같은 본문 다시 → TOUT 은 다시 안 부르고 끝나지 않은 잔여 해제만 다시(DONE, 사유 지움), 다른 본문 → 409
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo(ALLOC + "/ALC-1/releases"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"APPLIED\",\"requestId\":\"k\",\"resultId\":\"CMT-10\"}")));
    assertThat(post(SERVICE, "/internal/departure-validations", depBody).status()).isEqualTo(200);
    inventory.verify(1, postRequestedFor(urlPathEqualTo("/api/v1/internal/departure-postings")));
    assertThat(releases()).isEqualTo(releases + 1);
    assertThat(residual(dep, trf1)).isEqualTo("DONE");
    JsonNode residualDone =
        post(SERVICE, "/internal/departure-validations", depBody).body().path("transfers").get(0);
    assertThat(residualDone.path("residualStatus").asString()).isEqualTo("DONE");
    assertThat(
            residualDone.path("residualReason").isMissingNode()
                || residualDone.path("residualReason").isNull())
        .isTrue();
    assertThat(releases()).isEqualTo(releases + 1);
    Map<String, Object> changed = new LinkedHashMap<>(depBody);
    changed.put("vehicleId", "VH-9");
    assertThat(
            post(SERVICE, "/internal/departure-validations", changed)
                .body()
                .path("code")
                .asString())
        .isEqualTo("DEPARTURE_ALREADY_VALIDATED");

    // 12) 매니페스트·진행
    JsonNode man = get(SERVICE, "/internal/shipment-manifests/" + trf1).body();
    assertThat(man.path("lines").size()).isEqualTo(2);
    assertThat(man.path("lpns").size()).isEqualTo(3);
    assertThat(man.toString()).contains("2027-01-31");
    JsonNode fin = get(SERVICE, "/internal/transfer-intakes/" + trf1).body();
    assertThat(fin.path("state").asString()).isEqualTo("DEPARTED");
    int departed = 0;
    for (JsonNode p : fin.path("progress")) {
      departed += p.path("departedQty").asInt();
    }
    assertThat(departed).isEqualTo(7);
    assertThat(get(SERVICE, "/internal/shipment-manifests/" + trf3).status()).isEqualTo(404);
    JsonNode rdDep = readiness(trf1);
    assertThat(rdDep.path("readiness").asString()).isEqualTo("DEPARTED");
    assertThat(rdDep.path("readyQty").asInt()).isEqualTo(7);
  }

  Res postKey(String token, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "it");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  void stubRelease(String allocationId, int status, String body) {
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo(ALLOC + "/" + allocationId + "/releases"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader(
                        "Content-Type",
                        status >= 400 ? "application/problem+json" : "application/json")
                    .withBody(body)));
  }

  /**
   * 미출발 취소(0.7.0, 피킹 전): 미할당 FULL 바로 반영 → 릴리스 전 PARTIAL(할당 일부 해제) → 검증 거절 → Wave 릴리스 뒤 PARTIAL 거절 →
   * FULL(작업 막기 → 해제 불명 → CANCELLING, 출발 검증 거절 → 같은 본문 재전송으로 반영 → TRF·Wave CANCELLED). 다른 시험의 작업
   * 대기열과 섞이지 않게 CDC02.
   */
  @Test
  void preDepartureCancellationBeforePicking() {
    String c2 = "CDC02";
    String ops2 = TestJwt.operator("ops-2", List.of("CDC_MANAGER"), List.of(c2));
    String w9 = TestJwt.operator("w9", List.of("CDC_WORKER"), List.of(c2));
    String trfA = "TRF-" + UUID.randomUUID();
    String trfB = "TRF-" + UUID.randomUUID();
    String alc = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    stubAllocate(
        trfA,
        200,
        "{\"allocationId\":\""
            + alc
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":6},{\"sku\":\"E-00000002\",\"allocatedQty\":4}]}");
    stubAllocate(trfB, 422, "{\"code\":\"SKU_NOT_HANDLED\",\"detail\":\"취급 OFF\"}");
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(ALLOC + "/" + alc))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"allocationId\":\""
                            + alc
                            + "\",\"state\":\"ALLOCATED\",\"lines\":["
                            + "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\"CDC02-C-5-61-01-1-1\",\"qty\":4}]},"
                            + "{\"sku\":\"E-00000002\",\"lots\":[{\"lot\":\"L2\",\"locationCode\":\"CDC02-C-5-61-02-1-1\",\"qty\":4}]}]}")));
    Map<String, Object> a = new LinkedHashMap<>(trf(trfA, 6, 4));
    a.put("cdcId", c2);
    Map<String, Object> b = new LinkedHashMap<>(trf(trfB, 1, 1));
    b.put("cdcId", c2);
    assertThat(post(SERVICE, "/internal/transfer-intakes", a).status()).isEqualTo(202);
    assertThat(post(SERVICE, "/internal/transfer-intakes", b).status()).isEqualTo(202);
    String pdc = "/pre-departure-cancellations";
    String base = "/cdcs/" + c2 + "/transfers/";
    Map<String, Object> full = Map.of("reason", "TRANSPORT_UNAVAILABLE", "scope", "FULL");

    // 다른 센터 운영자 403
    assertThat(postKey(OPS, base + trfA + pdc, UUID.randomUUID().toString(), full).status())
        .isEqualTo(403);

    // 1) 미할당(ALLOCATION_FAILED) FULL — 재고 호출 없이 바로 CANCELLED
    String kb = UUID.randomUUID().toString();
    Res cb = postKey(ops2, base + trfB + pdc, kb, full);
    assertThat(cb.status()).as(cb.body().toString()).isEqualTo(202);
    assertThat(cb.body().path("status").asString()).isEqualTo("APPLIED");
    JsonNode rb = get(ops2, base + trfB + pdc + "/" + kb).body();
    assertThat(rb.path("state").asString()).isEqualTo("APPLIED");
    assertThat(rb.path("releasedQty").asInt()).isZero();
    assertThat(get(SERVICE, "/internal/transfer-intakes/" + trfB).body().path("state").asString())
        .isEqualTo("CANCELLED");

    // 2) 릴리스 전 PARTIAL — SKU1 2개 축소 → 재고 2 해제(사유 TRANSFER_CHANGED)
    stubRelease(
        alc,
        200,
        "{\"status\":\"APPLIED\",\"requestId\":\"k\",\"resultId\":\"CMT-P1\",\"appliedQty\":2}");
    Map<String, Object> partial =
        Map.of(
            "reason",
            "TRANSFER_CHANGED",
            "scope",
            "PARTIAL",
            "lines",
            List.of(Map.of("sku", "E-00000001", "reduceQty", 2)));
    String kp = UUID.randomUUID().toString();
    Res pa = postKey(ops2, base + trfA + pdc, kp, partial);
    assertThat(pa.status()).as(pa.body().toString()).isEqualTo(202);
    assertThat(pa.body().path("status").asString()).isEqualTo("APPLIED");
    inventory.verify(
        1,
        postRequestedFor(urlPathEqualTo(ALLOC + "/" + alc + "/releases"))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("TRANSFER_CHANGED")))
            .withRequestBody(matchingJsonPath("$.lines[0].sku", equalTo("E-00000001")))
            .withRequestBody(matchingJsonPath("$.lines[0].qty", equalTo("2"))));
    JsonNode ia = get(SERVICE, "/internal/transfer-intakes/" + trfA).body();
    assertThat(ia.path("state").asString()).isEqualTo("ALLOCATED");
    assertThat(ia.path("progress").get(0).path("allocatedQty").asInt()).isEqualTo(4);
    JsonNode rp = get(ops2, base + trfA + pdc + "/" + kp).body();
    assertThat(rp.path("releasedQty").asInt()).isEqualTo(2);
    assertThat(rp.path("releaseCommitId").asString()).isEqualTo("CMT-P1");
    // 같은 키·같은 본문 → 다시 부르지 않음, 승인량 넘는 축소·없는 SKU → 400
    assertThat(postKey(ops2, base + trfA + pdc, kp, partial).body().path("status").asString())
        .isEqualTo("APPLIED");
    inventory.verify(1, postRequestedFor(urlPathEqualTo(ALLOC + "/" + alc + "/releases")));
    Res over =
        postKey(
            ops2,
            base + trfA + pdc,
            UUID.randomUUID().toString(),
            Map.of(
                "reason",
                "OTHER",
                "scope",
                "PARTIAL",
                "lines",
                List.of(Map.of("sku", "E-00000001", "reduceQty", 5))));
    assertThat(over.status()).isEqualTo(400);
    assertThat(over.body().toString()).contains("REDUCE_EXCEEDS_APPROVED");
    assertThat(
            postKey(
                    ops2,
                    base + trfA + pdc,
                    UUID.randomUUID().toString(),
                    Map.of(
                        "reason",
                        "OTHER",
                        "scope",
                        "FULL",
                        "lines",
                        List.of(Map.of("sku", "E-00000001", "reduceQty", 1))))
                .status())
        .isEqualTo(400);

    // 3) Wave 릴리스 → 작업자가 하나 받음(ACCEPTED) → PARTIAL 거절
    Res wave = post(ops2, "/waves", Map.of("cdcId", c2, "trfIds", List.of(trfA)));
    assertThat(wave.status()).as(wave.body().toString()).isEqualTo(201);
    String waveId = wave.body().path("waveId").asString();
    assertThat(post(ops2, "/waves/" + waveId + "/releases", null).status()).isEqualTo(202);
    Res n = post(w9, "/cdcs/" + c2 + "/workers/w9/next-tasks", Map.of());
    assertThat(n.status()).as(n.body().toString()).isEqualTo(201);
    String held = n.body().path("taskId").asString();
    Res late = postKey(ops2, base + trfA + pdc, UUID.randomUUID().toString(), partial);
    assertThat(late.body().path("code").asString()).isEqualTo("PARTIAL_AFTER_RELEASE");

    // 4) FULL — 작업 막기 → 해제 불명(503) → UNKNOWN·CANCELLING
    stubRelease(alc, 503, "{}");
    String kf = UUID.randomUUID().toString();
    Res f1 = postKey(ops2, base + trfA + pdc, kf, full);
    assertThat(f1.status()).as(f1.body().toString()).isEqualTo(202);
    assertThat(f1.body().path("status").asString()).isEqualTo("ACCEPTED");
    assertThat(get(ops2, base + trfA + pdc + "/" + kf).body().path("state").asString())
        .isEqualTo("UNKNOWN");
    assertThat(get(SERVICE, "/internal/transfer-intakes/" + trfA).body().path("state").asString())
        .isEqualTo("CANCELLING");
    assertThat(get(ops2, "/cdcs/" + c2 + "/tasks/" + held).body().path("state").asString())
        .isEqualTo("CANCELLED");
    assertThat(post(w9, "/cdcs/" + c2 + "/tasks/" + held + "/starts", null).status())
        .isGreaterThanOrEqualTo(400);
    assertThat(
            post(w9, "/cdcs/" + c2 + "/workers/w9/next-tasks", Map.of())
                .body()
                .path("code")
                .asString())
        .isEqualTo("NO_TASK_AVAILABLE");
    assertThat(readiness(trfA).path("blockers").toString()).contains("CANCELLATION_PENDING");
    assertThat(
            postKey(ops2, base + trfA + pdc, UUID.randomUUID().toString(), full)
                .body()
                .path("code")
                .asString())
        .isEqualTo("INVALID_STATE");
    Map<String, Object> dep = new LinkedHashMap<>();
    dep.put("departureId", "DEP-" + UUID.randomUUID());
    dep.put("tripId", "TRIP-C2");
    dep.put("vehicleId", "VH-C2");
    dep.put("departedAt", Instant.now().toString());
    dep.put("source", "SIMULATED");
    dep.put("trfCompositions", List.of(Map.of("trfId", trfA, "lpns", List.of())));
    assertThat(post(SERVICE, "/internal/departure-validations", dep).body().path("code").asString())
        .isEqualTo("INVALID_STATE");

    // 5) 같은 본문 재전송 → 같은 키로 다시 해제 → APPLIED, TRF·Wave CANCELLED
    stubRelease(
        alc,
        200,
        "{\"status\":\"APPLIED\",\"requestId\":\"k\",\"resultId\":\"CMT-F1\",\"appliedQty\":8}");
    Res f2 = postKey(ops2, base + trfA + pdc, kf, full);
    assertThat(f2.body().path("status").asString()).as(f2.body().toString()).isEqualTo("APPLIED");
    var fullCalls =
        inventory.findAll(
            postRequestedFor(urlPathEqualTo(ALLOC + "/" + alc + "/releases"))
                .withRequestBody(matchingJsonPath("$.reason", equalTo("PRE_DEPARTURE_CANCEL"))));
    assertThat(fullCalls).hasSizeGreaterThanOrEqualTo(2); // 503 은 Downstream 이 한 번 더 시도
    assertThat(fullCalls.stream().map(r -> r.getHeader("Idempotency-Key")).distinct().toList())
        .hasSize(1)
        .allMatch(k -> k.startsWith("outbound-cancel-"));
    assertThat(fullCalls.get(0).getBodyAsString()).doesNotContain("\"lines\"");
    JsonNode rf = get(ops2, base + trfA + pdc + "/" + kf).body();
    assertThat(rf.path("state").asString()).isEqualTo("APPLIED");
    assertThat(rf.path("releasedQty").asInt()).isEqualTo(8);
    assertThat(rf.path("releaseCommitId").asString()).isEqualTo("CMT-F1");
    JsonNode fa = get(SERVICE, "/internal/transfer-intakes/" + trfA).body();
    assertThat(fa.path("state").asString()).isEqualTo("CANCELLED");
    assertThat(fa.path("progress").get(0).path("allocatedQty").asInt()).isZero();
    assertThat(get(ops2, "/waves/" + waveId).body().path("state").asString())
        .isEqualTo("CANCELLED");
    assertThat(readiness(trfA).path("readiness").asString()).isEqualTo("CANCELLED");
    assertThat(
            postKey(ops2, base + trfA + pdc, kf, Map.of("reason", "OTHER", "scope", "FULL"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(get(ops2, base + trfA + pdc + "/NOPE").status()).isEqualTo(404);
  }

  /**
   * 미설명 수량(0.8.0, WMS CDC-F-017): 3 을 집고 2 만 BOX 에 담은 TRF 는 실은 BOX 가 출발 구성과 맞아도 출발 검증이 막힌다 —
   * UNEXPLAINED_QTY·MISMATCHED, TOUT 없음, OutboundDiscrepancyRaised(MISSING, 기대 3·실제 2). 대기열이 섞이지 않게
   * CDC03.
   */
  @Test
  void unexplainedQtyBlocksDeparture() {
    String c3 = "CDC03";
    String ops3 = TestJwt.operator("ops-3", List.of("CDC_MANAGER"), List.of(c3));
    String w8 = TestJwt.operator("w8", List.of("CDC_WORKER"), List.of(c3));
    String trf = "TRF-" + UUID.randomUUID();
    String alc = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    stubAllocate(
        trf,
        200,
        "{\"allocationId\":\""
            + alc
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":3},{\"sku\":\"E-00000002\",\"allocatedQty\":1}]}");
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(ALLOC + "/" + alc))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"allocationId\":\""
                            + alc
                            + "\",\"state\":\"ALLOCATED\",\"lines\":["
                            + "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\"CDC03-C-5-61-01-1-1\",\"qty\":3}]},"
                            + "{\"sku\":\"E-00000002\",\"lots\":[{\"lot\":\"L2\",\"locationCode\":\"CDC03-C-5-61-02-1-1\",\"qty\":1}]}]}")));
    Map<String, Object> body = new LinkedHashMap<>(trf(trf, 3, 1));
    body.put("cdcId", c3);
    assertThat(post(SERVICE, "/internal/transfer-intakes", body).status()).isEqualTo(202);
    String waveId =
        post(ops3, "/waves", Map.of("cdcId", c3, "trfIds", List.of(trf)))
            .body()
            .path("waveId")
            .asString();
    assertThat(post(ops3, "/waves/" + waveId + "/releases", null).status()).isEqualTo(202);

    // 다 집는다(3 + 1)
    String task =
        post(w8, "/cdcs/" + c3 + "/workers/w8/next-tasks", Map.of())
            .body()
            .path("taskId")
            .asString();
    JsonNode lines =
        post(w8, "/cdcs/" + c3 + "/tasks/" + task + "/starts", null).body().path("lines");
    List<Map<String, Object>> picks = new java.util.ArrayList<>();
    for (JsonNode l : lines) {
      picks.add(
          Map.of(
              "sku", l.path("sku").asString(),
              "lot", l.path("lot").asString(),
              "locationCode", l.path("locationCode").asString(),
              "pickedQty", l.path("plannedQty").asInt()));
    }
    assertThat(picks).hasSize(2);
    Res pr =
        post(
            w8,
            "/cdcs/" + c3 + "/tasks/" + task + "/pick-results",
            Map.of("workerId", "w8", "lines", picks));
    assertThat(pr.status()).as(pr.body().toString()).isEqualTo(202);

    // SKU1 은 2 만 담는다 — 1 미설명
    String b1 = "BOX-" + UUID.randomUUID().toString().substring(0, 8);
    String b2 = "BOX-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> sort = new LinkedHashMap<>();
    sort.put("waveId", waveId);
    sort.put("trfId", trf);
    sort.put("workerId", "w8");
    sort.put(
        "lpns",
        List.of(box(b1, null, "E-00000001", "L1", 2), box(b2, null, "E-00000002", "L2", 1)));
    Res sr = post(ops3, "/sorting-results", sort);
    assertThat(sr.status()).as(sr.body().toString()).isEqualTo(202);
    Map<String, Object> insp = new LinkedHashMap<>();
    insp.put("trfId", trf);
    insp.put("inspectorId", "q3");
    insp.put(
        "items",
        List.of(Map.of("lpn", b1, "result", "PASSED"), Map.of("lpn", b2, "result", "PASSED")));
    assertThat(post(ops3, "/inspection-results", insp).status()).isEqualTo(202);
    Map<String, Object> load = new LinkedHashMap<>();
    load.put("trfId", trf);
    load.put("tripId", "TRIP-C3");
    load.put("vehicleId", "VH-C3");
    load.put("workerId", "w8");
    load.put("action", "LOADED");
    load.put(
        "lpns",
        List.of(
            Map.of("lpn", b1, "compartment", "AMBIENT"),
            Map.of("lpn", b2, "compartment", "AMBIENT")));
    Res lr = post(ops3, "/loading-results", load);
    assertThat(lr.status()).as(lr.body().toString()).isEqualTo(202);
    assertThat(readiness(trf).path("blockers").toString()).contains("UNSORTED_QTY");

    // 출발 검증 — 실린 BOX 와 구성은 맞지만 미설명 1 → 막힘
    String dep = "DEP-" + UUID.randomUUID();
    Map<String, Object> depBody = new LinkedHashMap<>();
    depBody.put("departureId", dep);
    depBody.put("tripId", "TRIP-C3");
    depBody.put("vehicleId", "VH-C3");
    depBody.put("departedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    depBody.put("source", "SIMULATED");
    depBody.put("trfCompositions", List.of(Map.of("trfId", trf, "lpns", List.of(b1, b2))));
    Res v = post(SERVICE, "/internal/departure-validations", depBody);
    assertThat(v.status()).as(v.body().toString()).isEqualTo(200);
    assertThat(v.body().path("overallResult").asString()).isEqualTo("MISMATCHED");
    assertThat(v.body().path("transfers").get(0).path("result").asString()).isEqualTo("MISMATCHED");
    JsonNode mm = v.body().path("mismatches");
    assertThat(mm.size()).as(mm.toString()).isEqualTo(1);
    assertThat(mm.get(0).path("kind").asString()).isEqualTo("UNEXPLAINED_QTY");
    assertThat(mm.get(0).path("detail").asString()).contains("E-00000001").contains("= 1");
    inventory.verify(
        0,
        postRequestedFor(urlPathEqualTo("/api/v1/internal/departure-postings"))
            .withHeader("Idempotency-Key", equalTo("outbound-tout-" + dep + "-" + trf)));
    String ev =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'OutboundDiscrepancyRaised' AND aggregateid = ?",
            String.class,
            trf);
    assertThat(ev)
        .contains("\"MISSING\"")
        .contains("\"expectedQty\": 3")
        .contains("\"actualQty\": 2");
    assertThat(get(SERVICE, "/internal/transfer-intakes/" + trf).body().path("state").asString())
        .isNotEqualTo("DEPARTED");
  }

  void stubAllocationLots(String alc, String lots) {
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(ALLOC + "/" + alc))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"allocationId\":\""
                            + alc
                            + "\",\"state\":\"ALLOCATED\",\"lines\":["
                            + lots
                            + "]}")));
  }

  Map<String, Object> trfAt(String trfId, String cdc, String mfc, String due, int a, int b) {
    Map<String, Object> m = new LinkedHashMap<>(trf(trfId, a, b));
    m.put("cdcId", cdc);
    m.put("mfcId", mfc);
    m.put("dueAt", due);
    return m;
  }

  /**
   * 묶음 피킹(0.9.0): 지역표 설정 → 통로 방식(FC·LINE, 위치 WireMock)으로 릴리스 → 지역 묶음(부산권 BJG36·BNG24 = 60)·SKU
   * 묶음(라인) 작업, trfId 비움·trfIds·shares → pickMode 로 받기 → 56 집고 4 부족 = 기한 이른 BNG 24 먼저, 부족은 늦은 BJG
   * 에서(PickShortageReported BJG 만) → 라인 작업까지 끝나야 두 TRF PICKED → 분류 ≤ 몫 실피킹 → 다른 Wave 에서 묶음 작업 안 한
   * TRF 취소 = 몫만 빠짐. CDC04.
   */
  @Test
  void regionAndLineBatchPicking() {
    String c4 = "CDC04";
    String ops4 = TestJwt.operator("ops-4", List.of("CDC_MANAGER"), List.of(c4));
    String w7 = TestJwt.operator("w7", List.of("CDC_WORKER"), List.of(c4));
    String w6 = TestJwt.operator("w6", List.of("CDC_WORKER"), List.of(c4));
    String early =
        Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    String late = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    String fc = "CDC04-E-5-61-01-1-1";
    String line = "CDC04-B-6-62-01-1-1";

    // 0) 지역표 — 같은 MFC 두 번 400, 설정 뒤 조회
    Res dupRegion =
        post(
            ops4,
            "/cdcs/" + c4 + "/regions",
            Map.of(
                "items",
                List.of(
                    Map.of("mfcId", "MFC-BJG-01", "regionCode", "BUSAN", "regionName", "부산권"),
                    Map.of("mfcId", "MFC-BJG-01", "regionCode", "HONAM", "regionName", "호남권"))));
    assertThat(dupRegion.status()).isEqualTo(400);
    Res reg =
        post(
            ops4,
            "/cdcs/" + c4 + "/regions",
            Map.of(
                "items",
                List.of(
                    Map.of("mfcId", "MFC-BJG-01", "regionCode", "BUSAN", "regionName", "부산권"),
                    Map.of("mfcId", "MFC-BNG-01", "regionCode", "BUSAN", "regionName", "부산권"))));
    assertThat(reg.status()).as(reg.body().toString()).isEqualTo(200);
    JsonNode regions = get(ops4, "/cdcs/" + c4 + "/regions").body().path("items");
    assertThat(regions.size()).isEqualTo(1);
    assertThat(regions.get(0).path("mfcIds").toString()).contains("MFC-BJG-01", "MFC-BNG-01");
    // 칠곡 처음 값(V7)
    assertThat(get(OPS, "/cdcs/CDC01/regions").body().path("items").size()).isEqualTo(4);

    // 1) 인수 — A = BJG(기한 늦음) 36·3, B = BNG(기한 이름) 24·2
    String trfA = "TRF-" + UUID.randomUUID();
    String trfB = "TRF-" + UUID.randomUUID();
    String alcA = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    String alcB = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    stubAllocate(
        trfA,
        200,
        "{\"allocationId\":\""
            + alcA
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":36},{\"sku\":\"E-00000002\",\"allocatedQty\":3}]}");
    stubAllocate(
        trfB,
        200,
        "{\"allocationId\":\""
            + alcB
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":24},{\"sku\":\"E-00000002\",\"allocatedQty\":2}]}");
    stubAllocationLots(
        alcA,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
            + fc
            + "\",\"qty\":36}]},{\"sku\":\"E-00000002\",\"lots\":[{\"lot\":\"L2\",\"locationCode\":\""
            + line
            + "\",\"qty\":3}]}");
    stubAllocationLots(
        alcB,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
            + fc
            + "\",\"qty\":24}]},{\"sku\":\"E-00000002\",\"lots\":[{\"lot\":\"L2\",\"locationCode\":\""
            + line
            + "\",\"qty\":2}]}");
    assertThat(
            post(SERVICE, "/internal/transfer-intakes", trfAt(trfA, c4, "MFC-BJG-01", late, 36, 3))
                .status())
        .isEqualTo(202);
    assertThat(
            post(SERVICE, "/internal/transfer-intakes", trfAt(trfB, c4, "MFC-BNG-01", early, 24, 2))
                .status())
        .isEqualTo(202);
    location.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/api/v1/internal/location-lookups"))
            .withRequestBody(matchingJsonPath("$.centerId", equalTo(c4)))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"locationCode\":\""
                            + fc
                            + "\",\"pickMethod\":\"FC\"},"
                            + "{\"locationCode\":\""
                            + line
                            + "\",\"pickMethod\":\"LINE\"}],\"missing\":[]}")));

    // 2) 릴리스 → 지역 묶음 1·SKU 묶음 1
    String waveId =
        post(ops4, "/waves", Map.of("cdcId", c4, "trfIds", List.of(trfA, trfB)))
            .body()
            .path("waveId")
            .asString();
    Res rel = post(ops4, "/waves/" + waveId + "/releases", null);
    assertThat(rel.status()).as(rel.body().toString()).isEqualTo(202);
    location.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/location-lookups"))
            .withHeader(
                "Idempotency-Key",
                com.github.tomakehurst.wiremock.client.WireMock.matching(
                    "cdc-outbound-location-lookup-.+")));
    JsonNode items = get(ops4, "/cdcs/" + c4 + "/tasks?waveId=" + waveId).body().path("items");
    assertThat(items.size()).isEqualTo(2);
    JsonNode region = null;
    JsonNode skuBatch = null;
    for (JsonNode t : items) {
      if ("REGION_BATCH".equals(t.path("pickMode").asString())) {
        region = t;
      } else {
        skuBatch = t;
      }
    }
    assertThat(region).isNotNull();
    assertThat(skuBatch.path("pickMode").asString()).isEqualTo("SKU_BATCH");
    assertThat(region.path("regionCode").asString()).isEqualTo("BUSAN");
    assertThat(region.path("pickMethod").asString()).isEqualTo("FC");
    assertThat(region.path("trfId").isNull() || region.path("trfId").isMissingNode()).isTrue();
    assertThat(region.path("trfIds").size()).isEqualTo(2);
    assertThat(Instant.parse(region.path("dueAt").asString())).isEqualTo(Instant.parse(early));
    JsonNode l0 = region.path("lines").get(0);
    assertThat(l0.path("plannedQty").asInt()).isEqualTo(60);
    assertThat(l0.path("shares").size()).isEqualTo(2);
    assertThat(skuBatch.path("lines").get(0).path("plannedQty").asInt()).isEqualTo(5);

    // 3) pickMode 로 받기 → 지역 묶음, 56 집고 4 부족
    Res n = post(w7, "/cdcs/" + c4 + "/workers/w7/next-tasks", Map.of("pickMode", "REGION_BATCH"));
    assertThat(n.status()).as(n.body().toString()).isEqualTo(201);
    String regionTask = n.body().path("taskId").asString();
    assertThat(regionTask).isEqualTo(region.path("taskId").asString());
    post(w7, "/cdcs/" + c4 + "/tasks/" + regionTask + "/starts", null);
    Map<String, Object> shortPick = new LinkedHashMap<>();
    shortPick.put("sku", "E-00000001");
    shortPick.put("lot", "L1");
    shortPick.put("locationCode", fc);
    shortPick.put("pickedQty", 56);
    shortPick.put("shortQty", 4);
    shortPick.put("shortReason", "NOT_FOUND");
    Res pr =
        post(
            w7,
            "/cdcs/" + c4 + "/tasks/" + regionTask + "/pick-results",
            Map.of("workerId", "w7", "lines", List.of(shortPick)));
    assertThat(pr.status()).as(pr.body().toString()).isEqualTo(202);
    JsonNode after = get(ops4, "/cdcs/" + c4 + "/tasks/" + regionTask).body();
    assertThat(after.path("state").asString()).isEqualTo("PARTIAL");
    Map<String, int[]> got = new LinkedHashMap<>();
    for (JsonNode sh : after.path("lines").get(0).path("shares")) {
      got.put(
          sh.path("trfId").asString(),
          new int[] {sh.path("pickedQty").asInt(), sh.path("shortQty").asInt()});
    }
    assertThat(got.get(trfB)).containsExactly(24, 0); // 기한 이른 BNG 먼저
    assertThat(got.get(trfA)).containsExactly(32, 4); // 부족은 늦은 BJG
    Integer shortEvents =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE type = 'PickShortageReported' AND aggregateid = ?",
            Integer.class,
            trfA);
    assertThat(shortEvents).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE type = 'PickShortageReported' AND aggregateid = ?",
                Integer.class,
                trfB))
        .isZero();
    JsonNode progA = get(SERVICE, "/internal/transfer-intakes/" + trfA).body();
    assertThat(progA.path("state").asString()).isEqualTo("IN_WAVE"); // 라인 작업이 남음

    // 4) 라인 작업(SKU 묶음) 5 다 집음 → 두 TRF PICKED·Wave WORK_DONE
    Res n2 = post(w6, "/cdcs/" + c4 + "/workers/w6/next-tasks", Map.of());
    String lineTask = n2.body().path("taskId").asString();
    assertThat(lineTask).isEqualTo(skuBatch.path("taskId").asString());
    post(w6, "/cdcs/" + c4 + "/tasks/" + lineTask + "/starts", null);
    Res pr2 =
        post(
            w6,
            "/cdcs/" + c4 + "/tasks/" + lineTask + "/pick-results",
            Map.of(
                "workerId",
                "w6",
                "lines",
                List.of(
                    Map.of(
                        "sku", "E-00000002", "lot", "L2", "locationCode", line, "pickedQty", 5))));
    assertThat(pr2.status()).as(pr2.body().toString()).isEqualTo(202);
    assertThat(get(SERVICE, "/internal/transfer-intakes/" + trfA).body().path("state").asString())
        .isEqualTo("PICKED");
    assertThat(get(SERVICE, "/internal/transfer-intakes/" + trfB).body().path("state").asString())
        .isEqualTo("PICKED");
    assertThat(get(ops4, "/waves/" + waveId).body().path("state").asString())
        .isEqualTo("WORK_DONE");
    assertThat(readiness(trfB).path("readiness").asString()).isEqualTo("PICKED");

    // 5) 분류 ≤ 몫 실피킹 — BNG 는 SKU1 24 까지
    Map<String, Object> overSort = new LinkedHashMap<>();
    overSort.put("waveId", waveId);
    overSort.put("trfId", trfB);
    overSort.put("workerId", "w6");
    overSort.put(
        "lpns",
        List.of(
            box(
                "BOX-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "E-00000001",
                "L1",
                25)));
    assertThat(post(ops4, "/sorting-results", overSort).body().path("code").asString())
        .isEqualTo("SORT_EXCEEDS_PICKED");
    Map<String, Object> sort = new LinkedHashMap<>(overSort);
    sort.put(
        "lpns",
        List.of(
            box(
                "BOX-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "E-00000001",
                "L1",
                24)));
    assertThat(post(ops4, "/sorting-results", sort).status()).isEqualTo(202);

    // 6) 다른 Wave — C(BJG)·D(BNG) 지역 묶음, 착수 전 C 전량 취소 → 작업엔 D 몫만
    String trfC = "TRF-" + UUID.randomUUID();
    String trfD = "TRF-" + UUID.randomUUID();
    String alcC = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    String alcD = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    stubAllocate(
        trfC,
        200,
        "{\"allocationId\":\""
            + alcC
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":5},{\"sku\":\"E-00000002\",\"allocatedQty\":0}]}");
    stubAllocate(
        trfD,
        200,
        "{\"allocationId\":\""
            + alcD
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":7},{\"sku\":\"E-00000002\",\"allocatedQty\":0}]}");
    stubAllocationLots(
        alcC,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
            + fc
            + "\",\"qty\":5}]}");
    stubAllocationLots(
        alcD,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
            + fc
            + "\",\"qty\":7}]}");
    post(SERVICE, "/internal/transfer-intakes", trfAt(trfC, c4, "MFC-BJG-01", early, 5, 1));
    post(SERVICE, "/internal/transfer-intakes", trfAt(trfD, c4, "MFC-BNG-01", late, 7, 1));
    String wave2 =
        post(ops4, "/waves", Map.of("cdcId", c4, "trfIds", List.of(trfC, trfD)))
            .body()
            .path("waveId")
            .asString();
    assertThat(post(ops4, "/waves/" + wave2 + "/releases", null).status()).isEqualTo(202);
    JsonNode t2 = get(ops4, "/cdcs/" + c4 + "/tasks?waveId=" + wave2).body().path("items").get(0);
    assertThat(t2.path("lines").get(0).path("plannedQty").asInt()).isEqualTo(12);
    stubRelease(
        alcC,
        200,
        "{\"status\":\"APPLIED\",\"requestId\":\"k\",\"resultId\":\"CMT-C\",\"appliedQty\":5}");
    Res cancel =
        postKey(
            ops4,
            "/cdcs/" + c4 + "/transfers/" + trfC + "/pre-departure-cancellations",
            UUID.randomUUID().toString(),
            Map.of("reason", "TRANSFER_CANCELLED", "scope", "FULL"));
    assertThat(cancel.body().path("status").asString())
        .as(cancel.body().toString())
        .isEqualTo("APPLIED");
    JsonNode t2b = get(ops4, "/cdcs/" + c4 + "/tasks/" + t2.path("taskId").asString()).body();
    assertThat(t2b.path("state").asString()).isEqualTo("CREATED");
    assertThat(t2b.path("lines").get(0).path("plannedQty").asInt()).isEqualTo(7);
    assertThat(t2b.path("trfIds").toString()).doesNotContain(trfC).contains(trfD);
    assertThat(t2b.path("dueAt").asString()).isNotEmpty();
  }

  JsonNode readiness(String trf) {
    Res r = get(SERVICE, "/internal/outbound-readiness?trfId=" + trf);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    return r.body();
  }

  int releases() {
    return inventory.findAll(postRequestedFor(urlPathEqualTo(ALLOC + "/ALC-1/releases"))).size();
  }

  String residual(String dep, String trf) {
    return jdbc.queryForObject(
        "SELECT residual_status FROM departure_transfer WHERE departure_id = ? AND trf_id = ?",
        String.class,
        dep,
        trf);
  }

  static Map<String, Object> box(String lpn, String parent, String sku, String lot, int qty) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("lpn", lpn);
    m.put("unitType", "BOX");
    m.put("state", "SEALED");
    if (parent != null) {
      m.put("parentLpn", parent);
    }
    m.put("contents", List.of(Map.of("sku", sku, "lot", lot, "qty", qty)));
    return m;
  }
}
