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
import org.junit.jupiter.api.Test;
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
@EnableWireMock(
    @ConfigureWireMock(
        name = "inventory",
        baseUrlProperties = "pmart.outbound.cdc-inventory-base-url"))
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

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
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
    assertThat(post(OPS, "/loading-results", load).status()).isEqualTo(202);
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

    // 같은 본문 다시 → TOUT 은 다시 안 부르고 불명이던 잔여 해제만 다시(DONE), 다른 본문 → 409
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
    assertThat(post(SERVICE, "/internal/departure-validations", depBody).status()).isEqualTo(200);
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
