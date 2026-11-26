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
  @ConfigureWireMock(name = "location", baseUrlProperties = "pmart.outbound.cdc-location-base-url"),
  @ConfigureWireMock(name = "product", baseUrlProperties = "pmart.outbound.product-base-url"),
  @ConfigureWireMock(name = "quality", baseUrlProperties = "pmart.outbound.cdc-quality-base-url")
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
  @Autowired com.pmart.cdc.outbound.application.ShortageService shortages;
  @Autowired com.pmart.cdc.outbound.application.PickMoveService pickMoves;
  @Autowired com.pmart.cdc.outbound.application.ShortfallService shortfalls;
  @Autowired com.pmart.common.messaging.EventEnvelopeCodec codec;
  @Autowired org.springframework.transaction.support.TransactionTemplate txTemplate;

  @InjectWireMock("inventory")
  WireMockServer inventory;

  @InjectWireMock("location")
  WireMockServer location;

  @InjectWireMock("product")
  WireMockServer product;

  @InjectWireMock("quality")
  WireMockServer quality;

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    // 0.24.0 운영 제한 확인 기본 — 막지 않음
    quality.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/api/v1/internal/operation-restriction-checks"))
            .atPriority(10)
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"restricted\":false,\"matches\":[]}")));
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
    // M-3(0.16.0) 직원 출발 준비 — 내부와 같은 내용, 다른 센터 TRF 는 404
    JsonNode staffRd = get(OPS, "/cdcs/" + CENTER + "/transfers/" + trf1 + "/readiness").body();
    assertThat(staffRd.path("readiness").asString()).isEqualTo("NOT_STARTED");
    assertThat(staffRd.path("blockers").toString()).contains("NOT_IN_WAVE");
    assertThat(get(OPS, "/cdcs/" + CENTER + "/transfers/TRF-NONE/readiness").status())
        .isEqualTo(404);
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

    // 2-1) DC 백오피스 직원 조회(0.11.0, 2026-11-25) — TRF 목록·상세·그날 출고 집계, 다른 센터 403
    JsonNode trfList =
        get(OPS, "/cdcs/" + CENTER + "/transfers?state=PARTIALLY_ALLOCATED&size=200").body();
    assertThat(trfList.path("items").findValuesAsString("trfId")).contains(trf1);
    assertThat(trfList.path("items").findValuesAsString("state"))
        .containsOnly("PARTIALLY_ALLOCATED");
    JsonNode detail = get(OPS, "/cdcs/" + CENTER + "/transfers/" + trf1).body();
    assertThat(detail.path("approvedQty").asInt()).isEqualTo(10);
    assertThat(detail.path("lines").size()).isEqualTo(2);
    assertThat(detail.path("lines").get(0).path("approvedQty").asInt()).isEqualTo(6);
    assertThat(get(OPS, "/cdcs/" + CENTER + "/transfers/TRF-NONE").status()).isEqualTo(404);
    String day =
        java.time.LocalDate.ofInstant(Instant.parse(DUE), java.time.ZoneId.of("Asia/Seoul"))
            .toString();
    JsonNode sum = get(OPS, "/cdcs/" + CENTER + "/outbound-summary?date=" + day).body();
    assertThat(sum.path("transferCount").asInt()).isGreaterThanOrEqualTo(3);
    assertThat(sum.path("transfersByState").findValuesAsString("key"))
        .contains("PARTIALLY_ALLOCATED", "ALLOCATION_FAILED", "ALLOCATED");
    assertThat(sum.path("approvedQty").asInt()).isGreaterThanOrEqualTo(14);
    // M-4(0.18.0) MFC 별 합
    assertThat(sum.path("byMfc").findValuesAsString("mfcId")).contains("MFC-BJG-01");
    assertThat(
            get(
                    TestJwt.operator("ops-x", List.of("CDC_SUPERVISOR"), List.of("CDC99")),
                    "/cdcs/" + CENTER + "/transfers")
                .status())
        .isEqualTo(403);

    // 3) Wave 생성 — 확보 안 된 TRF·이미 Wave 에 든 TRF 거절
    assertThat(
            post(OPS, "/waves", Map.of("cdcId", CENTER, "trfIds", List.of(trf2)))
                .body()
                .path("code")
                .asString())
        .isEqualTo("INVALID_STATE");
    Res wave = post(OPS, "/waves", Map.of("cdcId", CENTER, "trfIds", List.of(trf1)));
    assertThat(wave.status()).as(wave.body().toString()).isEqualTo(201);
    // W-1(0.15.0): 센터 Wave 목록 — 최신 순, state 거르기, 다른 센터 403
    JsonNode waveList = get(OPS, "/cdcs/" + CENTER + "/waves?state=PLANNED&size=200").body();
    assertThat(waveList.path("items").findValuesAsString("waveId"))
        .contains(wave.body().path("waveId").asString());
    assertThat(waveList.path("items").findValuesAsString("state")).containsOnly("PLANNED");
    assertThat(waveList.path("items").get(0).path("trfIds").isArray()).isTrue();
    assertThat(
            get(
                    TestJwt.operator("ops-x", List.of("CDC_SUPERVISOR"), List.of("CDC99")),
                    "/cdcs/" + CENTER + "/waves")
                .status())
        .isEqualTo(403);
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
    // M-1(0.18.0) 작업자 위치 — 피킹 실적 마지막 줄 위치가 남고, 위치 스캔으로 바뀐다(본인만)
    JsonNode board = get(OPS, "/cdcs/CDC01/workers").body();
    JsonNode me = null;
    for (JsonNode wk : board.path("workers")) {
      if ("w1".equals(wk.path("workerId").asString())) {
        me = wk;
      }
    }
    assertThat(me).isNotNull();
    assertThat(me.path("locationCode").asString()).isEqualTo(line1.path("locationCode").asString());
    assertThat(me.path("source").asString()).isEqualTo("PICK_RESULT");
    assertThat(me.path("locationState").asString()).isEqualTo("FRESH");
    assertThat(
            post(
                    W1,
                    "/cdcs/CDC01/workers/w1/location-scans",
                    Map.of("locationCode", "CDC01-C-7-02-01-1-1"))
                .status())
        .isEqualTo(204);
    assertThat(
            post(
                    W2,
                    "/cdcs/CDC01/workers/w1/location-scans",
                    Map.of("locationCode", "CDC01-C-7-02"))
                .status())
        .isEqualTo(403);
    JsonNode floor7 = get(OPS, "/cdcs/CDC01/workers?floor=7").body();
    assertThat(floor7.path("workers").findValuesAsString("workerId")).contains("w1");
    assertThat(floor7.path("byFloor").toString()).contains("\"key\":\"7\"");

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
    // 0.19.0: Wave 없이 센터 전체 작업, 결품 관리(부족 후속 — 이 TRF 줄)
    assertThat(
            get(OPS, "/cdcs/CDC01/tasks?size=200")
                .body()
                .path("items")
                .findValuesAsString("taskId"))
        .contains(t1, t2);
    JsonNode shorts = get(OPS, "/cdcs/CDC01/shortages?size=200").body();
    assertThat(shorts.path("items").findValuesAsString("trfId")).contains(trf1);
    assertThat(shorts.path("items").get(0).path("shortQty").asInt()).isPositive();
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
    // 0.19.0 LPN 관리 — 이 TRF 의 BOX 와 내용
    JsonNode lpns = get(OPS, "/cdcs/CDC01/lpns?trfId=" + trf1 + "&unitType=BOX").body();
    assertThat(lpns.path("items").size()).isPositive();
    assertThat(lpns.path("items").findValuesAsString("unitType")).containsOnly("BOX");
    assertThat(lpns.path("items").get(0).path("contents").size()).isPositive();
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
    // 0.26.0 transport-feed — 기한 구간의 TRF 에 출발 준비의 온도대·BOX 수, R/T 수(이 RT 하나)
    Instant due = Instant.parse(DUE);
    JsonNode feed =
        get(
                SERVICE,
                "/internal/transport-feed?cdcId=CDC01&dueFrom="
                    + due.minusSeconds(60)
                    + "&dueTo="
                    + due.plusSeconds(60))
            .body();
    JsonNode mine = null;
    for (JsonNode x : feed.path("items")) {
      if (trf1.equals(x.path("trfId").asString())) {
        mine = x;
      }
    }
    assertThat(mine).as(feed.toString()).isNotNull();
    assertThat(mine.path("boxCount").asInt()).isEqualTo(2);
    assertThat(mine.path("rtCount").asInt()).isEqualTo(1);
    assertThat(mine.path("tempClasses")).isEqualTo(rdInsp.path("temperatureZones"));
    assertThat(mine.path("approvedQty").asInt()).isPositive();
    assertThat(feed.path("truncated").asBoolean()).isFalse();
    // 인수 조건의 온도대 — 검품 BOX 전에도 feed 가 그 온도대를 준다(본문 지문 밖이라 같은 본문 재전송은 그대로)
    String trfT = "TRF-T-" + UUID.randomUUID().toString().substring(0, 6);
    Map<String, Object> withTemp = trf(trfT, 2, 2);
    withTemp.put("conditions", List.of(Map.of("kind", "TEMPERATURE_ZONE", "value", "CHILLED")));
    assertThat(post(SERVICE, "/internal/transfer-intakes", withTemp).status()).isEqualTo(202);
    assertThat(post(SERVICE, "/internal/transfer-intakes", withTemp).status()).isEqualTo(202);
    JsonNode feed2 =
        get(
                SERVICE,
                "/internal/transport-feed?cdcId=CDC01&dueFrom="
                    + due.minusSeconds(60)
                    + "&dueTo="
                    + due.plusSeconds(60))
            .body();
    JsonNode tempTrf = null;
    for (JsonNode x : feed2.path("items")) {
      if (trfT.equals(x.path("trfId").asString())) {
        tempTrf = x;
      }
    }
    assertThat(tempTrf).as(feed2.toString()).isNotNull();
    assertThat(tempTrf.path("tempClasses").toString()).isEqualTo("[\"CHILLED\"]");
    assertThat(tempTrf.path("boxCount").asInt()).isZero();
    // 0.27.0 KP-2 — 날짜 범위 출고 집계(그날 값 = outbound-summary), 31일 넘으면 400
    String dueDay =
        java.time.LocalDate.ofInstant(due, java.time.ZoneId.of("Asia/Seoul")).toString();
    JsonNode daily =
        get(OPS, "/cdcs/" + CENTER + "/outbound-summary/daily?from=" + dueDay + "&to=" + dueDay)
            .body();
    assertThat(daily.path("items").size()).isEqualTo(1);
    assertThat(daily.path("items").get(0).path("approvedQty").asInt())
        .isEqualTo(
            get(OPS, "/cdcs/" + CENTER + "/outbound-summary?date=" + dueDay)
                .body()
                .path("approvedQty")
                .asInt());
    assertThat(
            get(OPS, "/cdcs/" + CENTER + "/outbound-summary/daily?from=2026-01-01&to=2026-03-01")
                .status())
        .isEqualTo(400);
    // 0.27.0 WF-1 — 작업자 실적(끝난 작업의 담당자별): w1 은 DONE 작업 하나·부족 없음, w2 는 PARTIAL·부족 1
    String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
    JsonNode tp = get(OPS, "/cdcs/" + CENTER + "/worker-throughput?from=" + today).body();
    JsonNode w1row = null;
    JsonNode w2row = null;
    for (JsonNode x : tp.path("items")) {
      if ("w1".equals(x.path("workerId").asString())) {
        w1row = x;
      }
      if ("w2".equals(x.path("workerId").asString())) {
        w2row = x;
      }
    }
    assertThat(w1row).as(tp.toString()).isNotNull();
    assertThat(w1row.path("tasks").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(w1row.path("pickedQty").asInt()).isPositive();
    assertThat(w1row.path("firstStartedAt").isNull()).isFalse();
    assertThat(w2row).as(tp.toString()).isNotNull();
    assertThat(w2row.path("shortLines").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(
            get(
                    TestJwt.operator("ops-y", List.of("CDC_SUPERVISOR"), List.of("CDC99")),
                    "/cdcs/" + CENTER + "/worker-throughput")
                .status())
        .isEqualTo(403);
    assertThat(
            get(
                    SERVICE,
                    "/internal/transport-feed?cdcId=CDC01&dueFrom="
                        + due
                        + "&dueTo="
                        + due.minusSeconds(1))
                .status())
        .isEqualTo(400);
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
    // 0.24.0 운영 제한 확인이 안 되면 TOUT 하지 않고 UNKNOWN_RESULT — 같은 본문 재전송이 다시 확인
    var down =
        quality.stubFor(
            com.github.tomakehurst.wiremock.client.WireMock.post(
                    urlPathEqualTo("/api/v1/internal/operation-restriction-checks"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(503)));
    Res u = post(SERVICE, "/internal/departure-validations", depBody);
    assertThat(u.body().path("transfers").get(0).path("postingStatus").asString())
        .as(u.body().toString())
        .isEqualTo("UNKNOWN_RESULT");
    quality.removeStub(down);
    Res v = post(SERVICE, "/internal/departure-validations", depBody);
    assertThat(v.status()).as(v.body().toString()).isEqualTo(200);
    assertThat(v.body().path("overallResult").asString()).isEqualTo("PARTIALLY_MATCHED");
    JsonNode t1v = v.body().path("transfers").get(0);
    assertThat(t1v.path("result").asString()).isEqualTo("MATCHED");
    assertThat(t1v.path("postingStatus").asString()).isEqualTo("APPLIED");
    assertThat(t1v.path("commitId").asString()).isEqualTo("CMT-9");
    assertThat(t1v.path("departedQty").asInt()).isEqualTo(7);
    assertThat(v.body().path("transfers").get(1).path("result").asString()).isEqualTo("MISMATCHED");
    // M-5(0.25.0) 사건 기록 — 인수·Wave·작업·출발 검증이 최신 순으로, types 거르기, since ≥ until 400
    JsonNode ev = get(OPS, "/cdcs/" + CENTER + "/events?limit=500").body();
    assertThat(ev.path("items").findValuesAsString("type"))
        .as(ev.toString())
        .contains(
            "TRF_RECEIVED",
            "WAVE_CREATED",
            "WAVE_RELEASED",
            "TASK_COMPLETED",
            "DEPARTURE_VALIDATED");
    assertThat(ev.path("items").get(0).path("at").asString())
        .isGreaterThanOrEqualTo(
            ev.path("items").get(ev.path("items").size() - 1).path("at").asString());
    JsonNode only =
        get(OPS, "/cdcs/" + CENTER + "/events?types=DEPARTURE_VALIDATED&limit=500").body();
    assertThat(only.path("items").findValuesAsString("type")).containsOnly("DEPARTURE_VALIDATED");
    assertThat(only.path("items").findValuesAsString("refId")).contains(dep);
    assertThat(
            get(
                    OPS,
                    "/cdcs/"
                        + CENTER
                        + "/events?since=2030-01-01T00:00:00Z&until=2029-01-01T00:00:00Z")
                .status())
        .isEqualTo(400);
    // M-3(0.16.0) 직원 출발 검증 목록 — 결과로 거르기, 줄은 내부와 같은 모양
    JsonNode deps =
        get(OPS, "/cdcs/" + CENTER + "/departures?result=PARTIALLY_MATCHED&size=200").body();
    assertThat(deps.path("items").findValuesAsString("departureId")).contains(dep);
    assertThat(deps.path("items").findValuesAsString("overallResult"))
        .containsOnly("PARTIALLY_MATCHED");
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

  /**
   * DPS 통로 작업(0.10.0, R-20): DPS 위치 통로 61·62 에 걸친 TRF 하나 → 통로마다 작업(aisle), 다음 작업 받기 aisles 거르기 — 자기
   * 통로만 받고, 없는 통로면 409. CDC05.
   */
  @Test
  void wavesSplitDpsPerAisleAndFilterByAisle() {
    String c5 = "CDC05";
    String ops5 = TestJwt.operator("ops-5", List.of("CDC_MANAGER"), List.of(c5));
    String w5 = TestJwt.operator("w5", List.of("CDC_WORKER"), List.of(c5));
    String trf = "TRF-" + UUID.randomUUID();
    String alc = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    String a61 = "CDC05-A-5-61-01-1-1";
    String a62 = "CDC05-A-5-62-01-1-1";
    stubAllocate(
        trf,
        200,
        "{\"allocationId\":\""
            + alc
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":2},{\"sku\":\"E-00000002\",\"allocatedQty\":3}]}");
    stubAllocationLots(
        alc,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
            + a61
            + "\",\"qty\":2}]},{\"sku\":\"E-00000002\",\"lots\":[{\"lot\":\"L2\",\"locationCode\":\""
            + a62
            + "\",\"qty\":3}]}");
    location.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/api/v1/internal/location-lookups"))
            .withRequestBody(matchingJsonPath("$.centerId", equalTo(c5)))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"locationCode\":\""
                            + a61
                            + "\",\"pickMethod\":\"DPS\"},{\"locationCode\":\""
                            + a62
                            + "\",\"pickMethod\":\"DPS\"}],\"missing\":[]}")));
    assertThat(
            post(SERVICE, "/internal/transfer-intakes", trfAt(trf, c5, "MFC-BJG-01", DUE, 2, 3))
                .status())
        .isEqualTo(202);
    String waveId =
        post(ops5, "/waves", Map.of("cdcId", c5, "trfIds", List.of(trf)))
            .body()
            .path("waveId")
            .asString();
    assertThat(post(ops5, "/waves/" + waveId + "/releases", null).status()).isEqualTo(202);
    JsonNode items = get(ops5, "/cdcs/" + c5 + "/tasks?waveId=" + waveId).body().path("items");
    assertThat(items.size()).isEqualTo(2);
    for (JsonNode t : items) {
      assertThat(t.path("pickMode").asString()).isEqualTo("DISCRETE");
      assertThat(t.path("pickMethod").asString()).isEqualTo("DPS");
      assertThat(t.path("trfId").asString()).isEqualTo(trf);
      assertThat(t.path("lines").size()).isEqualTo(1);
    }
    assertThat(
            post(w5, "/cdcs/" + c5 + "/workers/w5/next-tasks", Map.of("aisles", List.of("70")))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NO_TASK_AVAILABLE");
    Res mine = post(w5, "/cdcs/" + c5 + "/workers/w5/next-tasks", Map.of("aisles", List.of("62")));
    assertThat(mine.status()).as(mine.body().toString()).isEqualTo(201);
    assertThat(mine.body().path("aisle").asString()).isEqualTo("62");
    assertThat(mine.body().path("lines").get(0).path("locationCode").asString()).isEqualTo(a62);
  }

  /**
   * 작업 조작(0.20.0, V13): 보류하면 다음 작업 받기에서 빠지고 재개하면 돌아온다. 우선순위가 높은 작업을 먼저 받는다. 재배정은 시작 전만, 다른 작업을 든
   * 작업자에겐 409 WORKER_BUSY. 이력이 남고, 다른 센터는 403. CDC10.
   */
  @Test
  void staffAdjustsTasks() {
    String c = "CDC10";
    String ops = TestJwt.operator("ops-10", List.of("CDC_SUPERVISOR"), List.of(c));
    String wa = TestJwt.operator("w10a", List.of("CDC_WORKER"), List.of(c));
    String trf = "TRF-" + UUID.randomUUID();
    String alc = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    String a61 = "CDC10-A-5-61-01-1-1";
    String a62 = "CDC10-A-5-62-01-1-1";
    stubAllocate(
        trf,
        200,
        "{\"allocationId\":\""
            + alc
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":2},{\"sku\":\"E-00000002\",\"allocatedQty\":3}]}");
    stubAllocationLots(
        alc,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
            + a61
            + "\",\"qty\":2}]},{\"sku\":\"E-00000002\",\"lots\":[{\"lot\":\"L2\",\"locationCode\":\""
            + a62
            + "\",\"qty\":3}]}");
    location.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/api/v1/internal/location-lookups"))
            .withRequestBody(matchingJsonPath("$.centerId", equalTo(c)))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"locationCode\":\""
                            + a61
                            + "\",\"pickMethod\":\"DPS\"},{\"locationCode\":\""
                            + a62
                            + "\",\"pickMethod\":\"DPS\"}],\"missing\":[]}")));
    assertThat(
            post(SERVICE, "/internal/transfer-intakes", trfAt(trf, c, "MFC-BJG-01", DUE, 2, 3))
                .status())
        .isEqualTo(202);
    String waveId =
        post(ops, "/waves", Map.of("cdcId", c, "trfIds", List.of(trf)))
            .body()
            .path("waveId")
            .asString();
    assertThat(post(ops, "/waves/" + waveId + "/releases", null).status()).isEqualTo(202);
    String base = "/cdcs/" + c + "/tasks/";
    String t61 = null;
    String t62 = null;
    for (JsonNode t : get(ops, "/cdcs/" + c + "/tasks?waveId=" + waveId).body().path("items")) {
      assertThat(t.path("priority").asInt()).isZero();
      if ("61".equals(t.path("aisle").asString())) {
        t61 = t.path("taskId").asString();
      } else {
        t62 = t.path("taskId").asString();
      }
    }
    assertThat(t61).isNotNull();
    assertThat(t62).isNotNull();

    // 화면 번호·상품 이름(0.21.0, V14) — 번호로 찾기는 글자·대소문자 무관
    product.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/api/v1/internal/skus"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"sku\":\"E-00000001\",\"productId\":\"PRD-1\",\"version\":3}],\"missing\":[\"E-00000002\"]}")));
    product.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                urlPathEqualTo("/api/v1/internal/products/PRD-1/versions/3"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"productId\":\"PRD-1\",\"internalName\":\"사과 1입\"}")));
    JsonNode task61 = get(ops, base + t61).body();
    String kNo = task61.path("taskNumber").asString();
    assertThat(kNo).matches("K[0-9]{5,}");
    assertThat(task61.path("waveNumber").asString()).matches("W[0-9]{4,}");
    String tNo = task61.path("trfNumber").asString();
    assertThat(tNo).matches("T[0-9]{6,}");
    assertThat(task61.path("lines").get(0).path("productName").asString()).isEqualTo("사과 1입");
    JsonNode byNo =
        get(ops, "/cdcs/" + c + "/tasks?number=" + kNo.substring(1)).body().path("items");
    assertThat(byNo.size()).isEqualTo(1);
    assertThat(byNo.get(0).path("taskId").asString()).isEqualTo(t61);
    assertThat(get(ops, "/cdcs/" + c + "/tasks?number=abc").body().path("items").size()).isZero();
    JsonNode trfs =
        get(ops, "/cdcs/" + c + "/transfers?number=" + tNo.toLowerCase(java.util.Locale.ROOT))
            .body()
            .path("items");
    assertThat(trfs.size()).isEqualTo(1);
    assertThat(trfs.get(0).path("trfNumber").asString()).isEqualTo(tNo);
    assertThat(trfs.get(0).path("waveNumber").asString())
        .isEqualTo(task61.path("waveNumber").asString());
    JsonNode detail = get(ops, "/cdcs/" + c + "/transfers/" + trf).body();
    assertThat(detail.path("trfNumber").asString()).isEqualTo(tNo);
    assertThat(detail.path("lines").findValuesAsString("productName")).contains("사과 1입");
    JsonNode wv =
        get(ops, "/cdcs/" + c + "/waves?number=" + task61.path("waveNumber").asString())
            .body()
            .path("items");
    assertThat(wv.size()).isEqualTo(1);
    assertThat(wv.get(0).path("trfNumbers").get(0).asString()).isEqualTo(tNo);

    // 보류 → 다음 작업 받기에서 빠짐 → 재개
    Res held = post(ops, base + t61 + "/holds", Map.of("reason", "통로 막힘"));
    assertThat(held.status()).as(held.body().toString()).isEqualTo(200);
    assertThat(held.body().path("state").asString()).isEqualTo("HELD");
    assertThat(
            post(wa, "/cdcs/" + c + "/workers/w10a/next-tasks", Map.of("aisles", List.of("61")))
                .body()
                .path("code")
                .asString())
        .isEqualTo("NO_TASK_AVAILABLE");
    assertThat(post(ops, base + t61 + "/holds", null).body().path("code").asString())
        .isEqualTo("INVALID_STATE");
    assertThat(post(ops, base + t61 + "/resumptions", null).body().path("state").asString())
        .isEqualTo("CREATED");

    // 우선순위: 62 를 올리면 거르기 없이 받아도 62 가 먼저
    assertThat(post(ops, base + t62 + "/priority-changes", Map.of("priority", 101)).status())
        .isEqualTo(400);
    Res pr = post(ops, base + t62 + "/priority-changes", Map.of("priority", 50));
    assertThat(pr.body().path("priority").asInt()).as(pr.body().toString()).isEqualTo(50);
    Res mine = post(wa, "/cdcs/" + c + "/workers/w10a/next-tasks", Map.of());
    assertThat(mine.status()).as(mine.body().toString()).isEqualTo(201);
    assertThat(mine.body().path("taskId").asString()).isEqualTo(t62);

    // 재배정: 작업을 든 작업자에겐 막힘, 다른 작업자는 ACCEPTED, 비우면 대기열로
    assertThat(
            post(ops, base + t61 + "/reassignments", Map.of("workerId", "w10a"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("WORKER_BUSY");
    Res ra = post(ops, base + t61 + "/reassignments", Map.of("workerId", "w10b", "reason", "지원"));
    assertThat(ra.body().path("state").asString()).as(ra.body().toString()).isEqualTo("ACCEPTED");
    assertThat(ra.body().path("assigneeId").asString()).isEqualTo("w10b");
    Res back = post(ops, base + t61 + "/reassignments", Map.of());
    assertThat(back.body().path("state").asString()).isEqualTo("CREATED");
    assertThat(
            back.body().path("assigneeId").isMissingNode()
                || back.body().path("assigneeId").isNull())
        .isTrue();

    // 이력(최신 순)·다른 센터
    JsonNode hist = get(ops, base + t61 + "/adjustments").body().path("items");
    assertThat(hist.findValuesAsString("action"))
        .containsExactly("REASSIGN", "REASSIGN", "RESUME", "HOLD");
    assertThat(hist.get(3).path("reason").asString()).isEqualTo("통로 막힘");
    assertThat(hist.get(3).path("actorId").asString()).isEqualTo("ops-10");
    String other = TestJwt.operator("ops-x", List.of("CDC_SUPERVISOR"), List.of("CDC99"));
    assertThat(post(other, base + t61 + "/holds", null).status()).isEqualTo(403);
    assertThat(get(other, base + t61 + "/adjustments").status()).isEqualTo(403);
  }

  /**
   * Wave 조작(0.22.0, V15): 나누기·합치기(PLANNED 만)·보류(릴리스·다음 작업 받기 막음)·재개·취소(시작·실적 없을 때 — TRF 는 다시 편성
   * 가능)·이력. CDC11.
   */
  @Test
  void staffAdjustsWaves() {
    String c = "CDC11";
    String ops = TestJwt.operator("ops-11", List.of("CDC_SUPERVISOR"), List.of(c));
    String wk = TestJwt.operator("w11", List.of("CDC_WORKER"), List.of(c));
    List<String> trfs = new java.util.ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String trf = "TRF-" + UUID.randomUUID();
      String alc = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
      stubAllocate(
          trf,
          200,
          "{\"allocationId\":\""
              + alc
              + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":2},{\"sku\":\"E-00000002\",\"allocatedQty\":3}]}");
      stubAllocationLots(
          alc,
          "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\"CDC11-A-5-61-01-1-1\",\"qty\":2}]},"
              + "{\"sku\":\"E-00000002\",\"lots\":[{\"lot\":\"L2\",\"locationCode\":\"CDC11-A-5-62-01-1-1\",\"qty\":3}]}");
      assertThat(
              post(SERVICE, "/internal/transfer-intakes", trfAt(trf, c, "MFC-BJG-01", DUE, 2, 3))
                  .status())
          .isEqualTo(202);
      trfs.add(trf);
    }
    String w1 =
        post(ops, "/waves", Map.of("cdcId", c, "trfIds", List.of(trfs.get(0), trfs.get(1))))
            .body()
            .path("waveId")
            .asString();
    String w2 =
        post(ops, "/waves", Map.of("cdcId", c, "trfIds", List.of(trfs.get(2))))
            .body()
            .path("waveId")
            .asString();

    // 나누기 → 새 Wave 에 B
    Res sp = post(ops, "/waves/" + w1 + "/splits", Map.of("trfIds", List.of(trfs.get(1))));
    assertThat(sp.status()).as(sp.body().toString()).isEqualTo(201);
    String w3 = sp.body().path("waveId").asString();
    assertThat(sp.body().path("trfIds").get(0).asString()).isEqualTo(trfs.get(1));
    assertThat(
            post(ops, "/waves/" + w1 + "/splits", Map.of("trfIds", List.of(trfs.get(0))))
                .body()
                .path("code")
                .asString())
        .isEqualTo("INVALID_STATE"); // 다 옮기면 나누기가 아님

    // 합치기 → w1 = A + C, w2 CANCELLED
    Res mg = post(ops, "/waves/" + w1 + "/merges", Map.of("sourceWaveId", w2));
    assertThat(mg.status()).as(mg.body().toString()).isEqualTo(200);
    assertThat(mg.body().path("trfIds").size()).isEqualTo(2);
    assertThat(get(ops, "/waves/" + w2).body().path("state").asString()).isEqualTo("CANCELLED");

    // 보류 → 릴리스 막힘 → 재개 → 릴리스
    assertThat(post(ops, "/waves/" + w1 + "/holds", Map.of()).status()).isEqualTo(400);
    Res hd = post(ops, "/waves/" + w1 + "/holds", Map.of("reason", "차량 늦음"));
    assertThat(hd.body().path("hold").path("reason").asString())
        .as(hd.body().toString())
        .isEqualTo("차량 늦음");
    assertThat(post(ops, "/waves/" + w1 + "/releases", null).body().path("code").asString())
        .isEqualTo("WAVE_HELD");
    JsonNode resumed = post(ops, "/waves/" + w1 + "/resumptions", null).body().path("hold");
    assertThat(resumed.isMissingNode() || resumed.isNull()).isTrue();
    assertThat(post(ops, "/waves/" + w1 + "/releases", null).status()).isEqualTo(202);

    // 릴리스 뒤 보류 → PDA 다음 작업 받기에 안 나옴
    post(ops, "/waves/" + w1 + "/holds", Map.of("reason", "점검"));
    assertThat(
            post(wk, "/cdcs/" + c + "/workers/w11/next-tasks", Map.of())
                .body()
                .path("code")
                .asString())
        .isEqualTo("NO_TASK_AVAILABLE");
    post(ops, "/waves/" + w1 + "/resumptions", null);
    Res got = post(wk, "/cdcs/" + c + "/workers/w11/next-tasks", Map.of());
    assertThat(got.status()).as(got.body().toString()).isEqualTo(201);

    // 취소(받기만 하고 시작 안 함) → 작업 CANCELLED, TRF 다시 편성 가능
    Res cn = post(ops, "/waves/" + w1 + "/cancellations", Map.of("reason", "재편성"));
    assertThat(cn.body().path("state").asString()).as(cn.body().toString()).isEqualTo("CANCELLED");
    assertThat(
            get(ops, "/cdcs/" + c + "/tasks?waveId=" + w1)
                .body()
                .path("items")
                .findValuesAsString("state"))
        .containsOnly("CANCELLED");
    JsonNode a = get(ops, "/cdcs/" + c + "/transfers/" + trfs.get(0)).body();
    assertThat(a.path("state").asString()).isEqualTo("ALLOCATED");
    assertThat(a.path("waveId").isNull() || a.path("waveId").isMissingNode()).isTrue();
    assertThat(
            post(ops, "/waves", Map.of("cdcId", c, "trfIds", List.of(trfs.get(0), trfs.get(2))))
                .status())
        .isEqualTo(201);
    assertThat(post(ops, "/waves/" + w3 + "/cancellations", Map.of("reason", "필요 없음")).status())
        .isEqualTo(200);

    JsonNode hist = get(ops, "/waves/" + w1 + "/adjustments").body().path("items");
    assertThat(hist.findValuesAsString("action"))
        .containsExactly("CANCEL", "RESUME", "HOLD", "RESUME", "HOLD", "MERGE_IN", "SPLIT_OUT");
    String other = TestJwt.operator("ops-x", List.of("CDC_SUPERVISOR"), List.of("CDC99"));
    assertThat(post(other, "/waves/" + w3 + "/holds", Map.of("reason", "x")).status())
        .isEqualTo(403);
  }

  /**
   * 피킹 부족 재할당(0.12.0, CDC-F-018): 10 중 7 집고 3 부족 → 재고 해제(그 LOT·위치·재계수 대기) → 추가 할당 2(다른 칸) → 새 작업 2,
   * 못 찾음 1(PickShortageResolved) → 새 작업 끝나면 TRF PICKED(실피킹 9 = 7 + 2, 못 찾음 1). 추가 할당 불명이면
   * SHORTAGE_REALLOCATING 으로 남고 다시 부르면 끝난다. CDC06.
   */
  @Test
  void shortageReallocatedIntoNewTask() {
    String c6 = "CDC06";
    String ops6 = TestJwt.operator("ops-6", List.of("CDC_MANAGER"), List.of(c6));
    String w4 = TestJwt.operator("w4", List.of("CDC_WORKER"), List.of(c6));
    String trf = "TRF-" + UUID.randomUUID();
    String alc = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    String x = "CDC06-C-5-61-01-1-1";
    String y = "CDC06-C-5-61-09-1-1";
    stubAllocate(
        trf,
        200,
        "{\"allocationId\":\""
            + alc
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":10},{\"sku\":\"E-00000002\",\"allocatedQty\":0}]}");
    stubAllocationLots(
        alc,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
            + x
            + "\",\"qty\":10}]}");
    stubRelease(
        alc,
        200,
        "{\"status\":\"APPLIED\",\"requestId\":\"k\",\"resultId\":\"CMT-R\",\"appliedQty\":3}");
    String addPath = ALLOC + "/" + alc + "/additions";
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(addPath))
            .willReturn(aResponse().withStatus(503).withBody("{}"))); // 처음엔 불명
    assertThat(
            post(SERVICE, "/internal/transfer-intakes", trfAt(trf, c6, "MFC-BJG-01", DUE, 10, 1))
                .status())
        .isEqualTo(202);
    String waveId =
        post(ops6, "/waves", Map.of("cdcId", c6, "trfIds", List.of(trf)))
            .body()
            .path("waveId")
            .asString();
    assertThat(post(ops6, "/waves/" + waveId + "/releases", null).status()).isEqualTo(202);
    String task =
        post(w4, "/cdcs/" + c6 + "/workers/w4/next-tasks", Map.of())
            .body()
            .path("taskId")
            .asString();
    post(w4, "/cdcs/" + c6 + "/tasks/" + task + "/starts", null);
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("sku", "E-00000001");
    line.put("lot", "L1");
    line.put("locationCode", x);
    line.put("pickedQty", 7);
    line.put("shortQty", 3);
    line.put("shortReason", "NOT_FOUND");
    Res pr =
        post(
            w4,
            "/cdcs/" + c6 + "/tasks/" + task + "/pick-results",
            Map.of("workerId", "w4", "lines", List.of(line)));
    assertThat(pr.status()).as(pr.body().toString()).isEqualTo(202);
    // 해제는 됐고(재계수 대기·그 LOT·위치), 추가 할당은 불명 → 남음
    inventory.verify(
        postRequestedFor(urlPathEqualTo(ALLOC + "/" + alc + "/releases"))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("PICK_SHORTAGE")))
            .withRequestBody(matchingJsonPath("$.recountHold", equalTo("true")))
            .withRequestBody(matchingJsonPath("$.lines[0].locationCode", equalTo(x)))
            .withRequestBody(matchingJsonPath("$.lines[0].qty", equalTo("3"))));
    assertThat(readiness(trf).path("blockers").toString()).contains("SHORTAGE_REALLOCATING");

    // 추가 할당이 되면 — 칸 y 에 2(1 은 못 찾음)
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(addPath))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"APPLIED\",\"addedQty\":2,\"lines\":[{\"sku\":\"E-00000001\",\"requestedQty\":3,\"addedQty\":2,"
                            + "\"lots\":[{\"lot\":\"L9\",\"locationCode\":\""
                            + y
                            + "\",\"qty\":2}]}]}")));
    shortages.processOpen();
    var adds = inventory.findAll(postRequestedFor(urlPathEqualTo(addPath)));
    assertThat(adds.stream().map(r -> r.getHeader("Idempotency-Key")).distinct().toList())
        .hasSize(1)
        .allMatch(k -> k.startsWith("outbound-short-add-")); // 다시 불러도 같은 키
    assertThat(readiness(trf).path("blockers").toString()).doesNotContain("SHORTAGE_REALLOCATING");
    String ev =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'PickShortageResolved' AND aggregateid = ?",
            String.class,
            trf);
    assertThat(ev).contains("\"reallocatedQty\": 2").contains("\"unresolvedQty\": 1");
    JsonNode tasks = get(ops6, "/cdcs/" + c6 + "/tasks?waveId=" + waveId).body().path("items");
    assertThat(tasks.size()).isEqualTo(2);
    assertThat(get(SERVICE, "/internal/transfer-intakes/" + trf).body().path("state").asString())
        .isEqualTo("IN_WAVE"); // 다시 집을 작업이 생겼다
    assertThat(get(ops6, "/waves/" + waveId).body().path("state").asString())
        .isEqualTo("IN_PROGRESS");

    // 새 작업(칸 y 2) 집기 → TRF PICKED, 실피킹 9 + 못 찾음 1 = 할당 10
    Res n2 = post(w4, "/cdcs/" + c6 + "/workers/w4/next-tasks", Map.of());
    String task2 = n2.body().path("taskId").asString();
    assertThat(n2.body().path("lines").get(0).path("locationCode").asString()).isEqualTo(y);
    post(w4, "/cdcs/" + c6 + "/tasks/" + task2 + "/starts", null);
    Res pr2 =
        post(
            w4,
            "/cdcs/" + c6 + "/tasks/" + task2 + "/pick-results",
            Map.of(
                "workerId",
                "w4",
                "lines",
                List.of(
                    Map.of("sku", "E-00000001", "lot", "L9", "locationCode", y, "pickedQty", 2))));
    assertThat(pr2.status()).as(pr2.body().toString()).isEqualTo(202);
    JsonNode intake = get(SERVICE, "/internal/transfer-intakes/" + trf).body();
    assertThat(intake.path("state").asString()).isEqualTo("PICKED");
    JsonNode p0 = intake.path("progress").get(0);
    assertThat(p0.path("allocatedQty").asInt()).as(intake.toString()).isEqualTo(10); // 처음 할당 그대로
    assertThat(p0.path("pickedQty").asInt()).isEqualTo(9);
    assertThat(
            jdbc.queryForObject(
                "SELECT short_qty FROM transfer_line WHERE trf_id = ? AND sku = 'E-00000001'",
                Integer.class,
                trf))
        .isEqualTo(1); // 못 찾음
    assertThat(readiness(trf).path("readiness").asString()).isEqualTo("PICKED");
  }

  void stubAllocateV(String trfId, int version, String body) {
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(ALLOC))
            .withHeader("Idempotency-Key", equalTo("outbound-alloc-" + trfId + "-v" + version))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }

  /**
   * TRF 새 승인 버전 대체(0.13.0, 사용자 "피킹 시작 전까지"): Wave 릴리스 뒤(아무도 안 집음) v2 → 작업 CANCELLED·Wave 에서 빠짐 → 예전
   * 할당 해제(TRANSFER_CHANGED) → v2 할당. 해제 불명이면 CHANGING(VERSION_CHANGE_PENDING) — 같은 본문 재전송으로 끝남. 피킹
   * 시작 뒤 v3 는 409 PICKING_STARTED. CDC07.
   */
  @Test
  void transferNewVersionReplacesBeforePicking() {
    String c7 = "CDC07";
    String ops7 = TestJwt.operator("ops-7", List.of("CDC_MANAGER"), List.of(c7));
    String w3 = TestJwt.operator("w3b", List.of("CDC_WORKER"), List.of(c7));
    String trf = "TRF-" + UUID.randomUUID();
    String a1 = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    String a2 = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    stubAllocateV(
        trf,
        1,
        "{\"allocationId\":\""
            + a1
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":6},{\"sku\":\"E-00000002\",\"allocatedQty\":4}]}");
    stubAllocationLots(
        a1,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\"CDC07-C-5-61-01-1-1\",\"qty\":6}]},{\"sku\":\"E-00000002\",\"lots\":[{\"lot\":\"L2\",\"locationCode\":\"CDC07-C-5-61-02-1-1\",\"qty\":4}]}");
    Map<String, Object> v1 = trfAt(trf, c7, "MFC-BJG-01", DUE, 6, 4);
    assertThat(post(SERVICE, "/internal/transfer-intakes", v1).status()).isEqualTo(202);
    String wave =
        post(ops7, "/waves", Map.of("cdcId", c7, "trfIds", List.of(trf)))
            .body()
            .path("waveId")
            .asString();
    assertThat(post(ops7, "/waves/" + wave + "/releases", null).status()).isEqualTo(202);
    String task =
        get(ops7, "/cdcs/" + c7 + "/tasks?waveId=" + wave)
            .body()
            .path("items")
            .get(0)
            .path("taskId")
            .asString();

    // v2(6 → 3) — 해제 불명이면 CHANGING
    stubRelease(a1, 503, "{}");
    Map<String, Object> v2 = new LinkedHashMap<>(trfAt(trf, c7, "MFC-BJG-01", DUE, 3, 4));
    v2.put("trfVersion", 2);
    assertThat(post(SERVICE, "/internal/transfer-intakes", v2).status()).isEqualTo(202);
    JsonNode i1 = get(SERVICE, "/internal/transfer-intakes/" + trf).body();
    assertThat(i1.path("state").asString()).as(i1.toString()).isEqualTo("CHANGING");
    assertThat(i1.path("trfVersion").asInt()).isEqualTo(2);
    assertThat(readiness(trf).path("blockers").toString()).contains("VERSION_CHANGE_PENDING");
    assertThat(get(ops7, "/cdcs/" + c7 + "/tasks/" + task).body().path("state").asString())
        .isEqualTo("CANCELLED");
    assertThat(get(ops7, "/waves/" + wave).body().path("state").asString()).isEqualTo("CANCELLED");
    assertThat(
            postKey(
                    ops7,
                    "/cdcs/" + c7 + "/transfers/" + trf + "/pre-departure-cancellations",
                    UUID.randomUUID().toString(),
                    Map.of("reason", "TRANSFER_CANCELLED", "scope", "FULL"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("VERSION_CHANGE_IN_PROGRESS");

    // 같은 본문 재전송 → 같은 키로 해제 → v2 할당
    stubRelease(
        a1,
        200,
        "{\"status\":\"APPLIED\",\"requestId\":\"k\",\"resultId\":\"CMT-CH\",\"appliedQty\":10}");
    stubAllocateV(
        trf,
        2,
        "{\"allocationId\":\""
            + a2
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":3},{\"sku\":\"E-00000002\",\"allocatedQty\":4}]}");
    assertThat(post(SERVICE, "/internal/transfer-intakes", v2).status()).isEqualTo(202);
    JsonNode i2 = get(SERVICE, "/internal/transfer-intakes/" + trf).body();
    assertThat(i2.path("state").asString()).as(i2.toString()).isEqualTo("ALLOCATED");
    assertThat(i2.path("allocationId").asString()).isEqualTo(a2);
    assertThat(i2.path("progress").get(0).path("approvedQty").asInt()).isEqualTo(3);
    var rels =
        inventory.findAll(
            postRequestedFor(urlPathEqualTo(ALLOC + "/" + a1 + "/releases"))
                .withRequestBody(matchingJsonPath("$.reason", equalTo("TRANSFER_CHANGED"))));
    assertThat(rels.stream().map(r -> r.getHeader("Idempotency-Key")).distinct().toList())
        .containsExactly("outbound-change-" + trf + "-v2");
    // 낮은 버전 409
    assertThat(post(SERVICE, "/internal/transfer-intakes", v1).body().path("code").asString())
        .isEqualTo("INVALID_STATE");

    // 다시 Wave → 작업 받고 착수(피킹 시작) → v3 는 409 PICKING_STARTED
    stubAllocationLots(
        a2,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\"CDC07-C-5-61-01-1-1\",\"qty\":3}]}");
    String wave2 =
        post(ops7, "/waves", Map.of("cdcId", c7, "trfIds", List.of(trf)))
            .body()
            .path("waveId")
            .asString();
    assertThat(post(ops7, "/waves/" + wave2 + "/releases", null).status()).isEqualTo(202);
    String t2 =
        post(w3, "/cdcs/" + c7 + "/workers/w3b/next-tasks", Map.of())
            .body()
            .path("taskId")
            .asString();
    assertThat(post(w3, "/cdcs/" + c7 + "/tasks/" + t2 + "/starts", null).status()).isEqualTo(200);
    Map<String, Object> v3 = new LinkedHashMap<>(trfAt(trf, c7, "MFC-BJG-01", DUE, 2, 4));
    v3.put("trfVersion", 3);
    assertThat(post(SERVICE, "/internal/transfer-intakes", v3).body().path("code").asString())
        .isEqualTo("PICKING_STARTED");
  }

  /**
   * 피킹 재고 출고대기 자리로 옮겨 적기(0.14.0): 지역표 stagingBay 01 → 3 집으면 move-postings(PICK_MOVE, allocationId,
   * 칸 → CDC08-C-5-91-01-0-1 — R-22 부터 위치 서비스에서 같은 층·온도대 STAGING 으로 고름, 냉동 A-5-91 은 아님). 불명이면
   * PICK_MOVE_PENDING, 다시 부르면 같은 키로 끝남. CDC08.
   */
  @Test
  void pickedStockMovesToStagingSlot() {
    String c8 = "CDC08";
    String ops8 = TestJwt.operator("ops-8", List.of("CDC_MANAGER"), List.of(c8));
    String w8b = TestJwt.operator("w8b", List.of("CDC_WORKER"), List.of(c8));
    Res reg =
        post(
            ops8,
            "/cdcs/" + c8 + "/regions",
            Map.of(
                "items",
                List.of(
                    Map.of(
                        "mfcId",
                        "MFC-BJG-01",
                        "regionCode",
                        "BUSAN",
                        "regionName",
                        "부산권",
                        "stagingBay",
                        "01"))));
    assertThat(reg.status()).as(reg.body().toString()).isEqualTo(200);
    assertThat(reg.body().path("items").get(0).path("stagingBays").path("MFC-BJG-01").asString())
        .isEqualTo("01");
    String trf = "TRF-" + UUID.randomUUID();
    String alc = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    String loc = "CDC08-C-5-61-01-1-1";
    // R-22: 출고대기 자리는 위치 서비스에서 같은 층·온도대 91 STAGING 으로 고른다
    location.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo("/api/v1/internal/location-lookups"))
            .atPriority(1)
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.containing("CDC08-C-5-91-01-0-1"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"items\":[{\"locationCode\":\"CDC08-C-5-61-01-1-1\",\"locationType\":\"PICK_FACE\","
                            + "\"state\":\"ACTIVE\",\"temperatureZone\":\"AMBIENT\"},"
                            + "{\"locationCode\":\"CDC08-C-5-91-01-0-1\",\"locationType\":\"STAGING\","
                            + "\"state\":\"ACTIVE\",\"temperatureZone\":\"AMBIENT\"},"
                            + "{\"locationCode\":\"CDC08-A-5-91-01-0-1\",\"locationType\":\"STAGING\","
                            + "\"state\":\"ACTIVE\",\"temperatureZone\":\"FROZEN\"}],\"missing\":[]}")));
    stubAllocate(
        trf,
        200,
        "{\"allocationId\":\""
            + alc
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":3},{\"sku\":\"E-00000002\",\"allocatedQty\":0}]}");
    stubAllocationLots(
        alc,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
            + loc
            + "\",\"qty\":3}]}");
    String movePath = "/api/v1/internal/move-postings";
    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(movePath))
            .withRequestBody(matchingJsonPath("$.cdcId", equalTo(c8)))
            .willReturn(aResponse().withStatus(503).withBody("{}")));
    assertThat(
            post(SERVICE, "/internal/transfer-intakes", trfAt(trf, c8, "MFC-BJG-01", DUE, 3, 1))
                .status())
        .isEqualTo(202);
    String wave =
        post(ops8, "/waves", Map.of("cdcId", c8, "trfIds", List.of(trf)))
            .body()
            .path("waveId")
            .asString();
    assertThat(post(ops8, "/waves/" + wave + "/releases", null).status()).isEqualTo(202);
    String task =
        post(w8b, "/cdcs/" + c8 + "/workers/w8b/next-tasks", Map.of())
            .body()
            .path("taskId")
            .asString();
    post(w8b, "/cdcs/" + c8 + "/tasks/" + task + "/starts", null);
    Res pr =
        post(
            w8b,
            "/cdcs/" + c8 + "/tasks/" + task + "/pick-results",
            Map.of(
                "workerId",
                "w8b",
                "lines",
                List.of(
                    Map.of(
                        "sku", "E-00000001", "lot", "L1", "locationCode", loc, "pickedQty", 3))));
    assertThat(pr.status()).as(pr.body().toString()).isEqualTo(202);
    assertThat(readiness(trf).path("blockers").toString()).contains("PICK_MOVE_PENDING");

    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(movePath))
            .withRequestBody(matchingJsonPath("$.cdcId", equalTo(c8)))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"APPLIED\",\"requestId\":\"k\",\"resultId\":\"CMT-M\",\"appliedQty\":3}")));
    pickMoves.processOpen();
    assertThat(readiness(trf).path("blockers").toString()).doesNotContain("PICK_MOVE_PENDING");
    var moves =
        inventory.findAll(
            postRequestedFor(urlPathEqualTo(movePath))
                .withRequestBody(matchingJsonPath("$.cdcId", equalTo(c8)))
                .withRequestBody(matchingJsonPath("$.moveKind", equalTo("PICK_MOVE")))
                .withRequestBody(matchingJsonPath("$.lines[0].fromLocationCode", equalTo(loc)))
                .withRequestBody(
                    matchingJsonPath("$.lines[0].toLocationCode", equalTo("CDC08-C-5-91-01-0-1")))
                .withRequestBody(matchingJsonPath("$.lines[0].allocationId", equalTo(alc)))
                .withRequestBody(matchingJsonPath("$.lines[0].qty", equalTo("3"))));
    assertThat(moves).hasSizeGreaterThanOrEqualTo(2);
    assertThat(moves.stream().map(r -> r.getHeader("Idempotency-Key")).distinct().toList())
        .hasSize(1)
        .allMatch(k -> k.startsWith("outbound-pickmove-"));
  }

  /**
   * 실사 부족 받기(0.18.0): 아직 안 집은 작업 줄(칸 x 5)에 AllocationShortfall 2 → 줄 부족 2(COUNT_LOSS)·TRF 줄 부족 2·후속
   * RELEASED → 추가 할당 2(칸 y) → 새 작업·PickShortageResolved(2, 0)·TRF 줄 부족 0. 소비자와 같이 트랜잭션 안에서 부른다.
   * CDC09.
   */
  @Test
  void shortfallFromCountReallocates() {
    String c9 = "CDC09";
    String ops9 = TestJwt.operator("ops-9", List.of("CDC_MANAGER"), List.of(c9));
    String trf = "TRF-" + UUID.randomUUID();
    String alc = "ALC-" + UUID.randomUUID().toString().substring(0, 8);
    String x = "CDC09-C-5-61-01-1-1";
    String y = "CDC09-C-5-61-05-1-1";
    stubAllocate(
        trf,
        200,
        "{\"allocationId\":\""
            + alc
            + "\",\"state\":\"ALLOCATED\",\"lines\":[{\"sku\":\"E-00000001\",\"allocatedQty\":5},{\"sku\":\"E-00000002\",\"allocatedQty\":0}]}");
    stubAllocationLots(
        alc,
        "{\"sku\":\"E-00000001\",\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
            + x
            + "\",\"qty\":5}]}");
    assertThat(
            post(SERVICE, "/internal/transfer-intakes", trfAt(trf, c9, "MFC-BJG-01", DUE, 5, 1))
                .status())
        .isEqualTo(202);
    String wave =
        post(ops9, "/waves", Map.of("cdcId", c9, "trfIds", List.of(trf)))
            .body()
            .path("waveId")
            .asString();
    assertThat(post(ops9, "/waves/" + wave + "/releases", null).status()).isEqualTo(202);
    String task =
        get(ops9, "/cdcs/" + c9 + "/tasks?waveId=" + wave)
            .body()
            .path("items")
            .get(0)
            .path("taskId")
            .asString();

    Map<String, Object> p = new LinkedHashMap<>();
    p.put("cdcId", c9);
    p.put("trfId", trf);
    p.put("allocationId", alc);
    p.put("sku", "E-00000001");
    p.put("lot", "L1");
    p.put("locationCode", x);
    p.put("qty", 2);
    p.put("reason", "COUNT_LOSS");
    p.put("sourceRef", "CNT-1");
    var env =
        codec.create(
            "cdc-inventory-service",
            "AllocationShortfall",
            1,
            "Allocation",
            alc,
            1,
            null,
            com.pmart.common.messaging.EventEnvelope.EventSource.REAL,
            p);
    txTemplate.executeWithoutResult(s -> shortfalls.apply(env));
    JsonNode t1 = get(ops9, "/cdcs/" + c9 + "/tasks/" + task).body();
    assertThat(t1.path("lines").get(0).path("shortQty").asInt()).as(t1.toString()).isEqualTo(2);
    assertThat(t1.path("lines").get(0).path("shortReason").asString()).isEqualTo("COUNT_LOSS");
    assertThat(t1.path("state").asString()).isEqualTo("CREATED"); // 3 이 남음
    assertThat(readiness(trf).path("blockers").toString()).contains("SHORTAGE_REALLOCATING");

    inventory.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(
                urlPathEqualTo(ALLOC + "/" + alc + "/additions"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"APPLIED\",\"addedQty\":2,\"lines\":[{\"sku\":\"E-00000001\",\"requestedQty\":2,\"addedQty\":2,"
                            + "\"lots\":[{\"lot\":\"L1\",\"locationCode\":\""
                            + y
                            + "\",\"qty\":2}]}]}")));
    shortages.processOpen();
    // 해제는 재고가 이미 했다 — 부족 해제 호출 없음
    assertThat(
            inventory.findAll(
                postRequestedFor(urlPathEqualTo(ALLOC + "/" + alc + "/releases"))
                    .withRequestBody(matchingJsonPath("$.reason", equalTo("PICK_SHORTAGE")))))
        .isEmpty();
    JsonNode items = get(ops9, "/cdcs/" + c9 + "/tasks?waveId=" + wave).body().path("items");
    assertThat(items.size()).isEqualTo(2);
    String ev =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE type = 'PickShortageResolved' AND aggregateid = ?",
            String.class,
            trf);
    assertThat(ev).contains("\"reallocatedQty\": 2").contains("\"unresolvedQty\": 0");
    assertThat(
            jdbc.queryForObject(
                "SELECT short_qty FROM transfer_line WHERE trf_id = ? AND sku = 'E-00000001'",
                Integer.class,
                trf))
        .isZero();

    // 0.23.0 리콜 — 출고대기 91 칸의 RECALL 은 재할당하지 않고 막는다(RECALL_HOLD), 작업 줄은 그대로
    Map<String, Object> rc = new LinkedHashMap<>(p);
    rc.put("locationCode", "CDC09-C-5-91-01-0-1");
    rc.put("qty", 1);
    rc.put("reason", "RECALL");
    rc.put("sourceRef", "QH-RECALL-1");
    var renv =
        codec.create(
            "cdc-inventory-service",
            "AllocationShortfall",
            1,
            "Allocation",
            alc,
            2,
            null,
            com.pmart.common.messaging.EventEnvelope.EventSource.REAL,
            rc);
    int before = get(ops9, "/cdcs/" + c9 + "/tasks?waveId=" + wave).body().path("items").size();
    txTemplate.executeWithoutResult(s -> shortfalls.apply(renv));
    assertThat(readiness(trf).path("blockers").toString()).contains("RECALL_HOLD");
    assertThat(get(ops9, "/cdcs/" + c9 + "/tasks?waveId=" + wave).body().path("items").size())
        .isEqualTo(before);
    assertThat(
            jdbc.queryForObject(
                "SELECT qty FROM recall_hold WHERE trf_id = ? AND source_ref = 'QH-RECALL-1'",
                Integer.class,
                trf))
        .isEqualTo(1);
  }

  JsonNode readiness(String trf) {
    Res r = get(SERVICE, "/internal/outbound-readiness?trfId=" + trf);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    return r.body();
  }

  int releases() {
    return inventory
        .findAll(
            postRequestedFor(urlPathEqualTo(ALLOC + "/ALC-1/releases"))
                .withRequestBody(matchingJsonPath("$.reason", equalTo("RESIDUAL_CLOSED"))))
        .size(); // 부족 해제(PICK_SHORTAGE, 0.12.0)는 세지 않는다
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
