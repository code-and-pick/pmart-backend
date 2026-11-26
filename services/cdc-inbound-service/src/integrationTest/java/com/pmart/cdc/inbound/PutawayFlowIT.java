package com.pmart.cdc.inbound;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.cdc.inbound.application.ReplenishmentService;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 적치·보충(0.2.0): 적치 지시(검사·공간 예약 → DISPATCHED) → 설비 다음 작업 받기 → 일부·전량 결과(재고 이동 증가분·예약 소비) → 자동 보충(최소
 * 이하 → 박스 입수 배수) → 실패 결과(남은 예약 해제). cdc-inventory·cdc-location 은 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.inbound.replenishment-interval=PT1H",
      "pmart.inbound.retry-interval=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(
      name = "inventory",
      baseUrlProperties = "pmart.inbound.cdc-inventory-base-url"),
  @ConfigureWireMock(name = "location", baseUrlProperties = "pmart.inbound.cdc-location-base-url")
})
class PutawayFlowIT extends PostgresKafkaContainerSupport {

  static final String CENTER = "CDC01";
  static final String MANAGER = TestJwt.operator("im-1", List.of("CDC_MANAGER"), List.of(CENTER));
  static final String SERVICE = TestJwt.service("cdc-simulator");
  static final String RACK = "CDC01-A-5-01-01-2-1";
  static final String SHELF = "CDC01-A-5-61-01-1-1";
  static final String STAGING = "CDC01-A-5-91-01-0-1";
  static final String SKU = "E-00000011";

  @LocalServerPort int port;

  @InjectWireMock("inventory")
  WireMockServer inventory;

  @InjectWireMock("location")
  WireMockServer location;

  @Autowired ObjectMapper mapper;
  @Autowired ReplenishmentService replenishment;

  record Res(int status, JsonNode body) {}

  Res send(String token, String path, String key, Object body) {
    var e =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build()
            .post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  if (key != null) {
                    h.set("Idempotency-Key", key);
                  }
                  h.set("X-Source-System", "cdc-simulator");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    String b = e.getBody();
    return new Res(e.getStatusCode().value(), mapper.readTree(b == null || b.isBlank() ? "{}" : b));
  }

  Res read(String token, String path) {
    var e =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build()
            .get()
            .uri(path)
            .headers(h -> h.setBearerAuth(token))
            .retrieve()
            .toEntity(String.class);
    String b = e.getBody();
    return new Res(e.getStatusCode().value(), mapper.readTree(b == null || b.isBlank() ? "{}" : b));
  }

  static String key() {
    return UUID.randomUUID().toString();
  }

  void stubs(int shelfQty) {
    inventory.stubFor(
        get(urlPathEqualTo("/api/v1/internal/snapshots"))
            .willReturn(
                okJson(
                    "{\"items\":[{\"sku\":\""
                        + SKU
                        + "\",\"lots\":[{\"lot\":\"L1\",\"expiresAt\":\"2026-12-30\",\"locations\":["
                        + "{\"locationCode\":\""
                        + RACK
                        + "\",\"qualityState\":\"RELEASED\",\"onHand\":48,\"reserved\":0},"
                        + "{\"locationCode\":\""
                        + SHELF
                        + "\",\"qualityState\":\"RELEASED\",\"onHand\":"
                        + shelfQty
                        + ",\"reserved\":0}]}]}]}")));
    inventory.stubFor(
        get(urlPathEqualTo("/api/v1/internal/skus/" + SKU))
            .willReturn(
                okJson(
                    "{\"sku\":\""
                        + SKU
                        + "\",\"storageType\":\"AMBIENT\",\"logistics\":{\"unitsPerBox\":12}}")));
    inventory.stubFor(
        post(urlPathEqualTo("/api/v1/internal/move-postings"))
            .willReturn(
                okJson("{\"status\":\"APPLIED\",\"resultId\":\"CMT-1\",\"appliedQty\":1}")));
    location.stubFor(
        post(urlPathEqualTo("/api/v1/internal/location-lookups"))
            .willReturn(
                okJson(
                    "{\"items\":["
                        + "{\"locationCode\":\""
                        + RACK
                        + "\",\"locationType\":\"RESERVE\",\"state\":\"ACTIVE\",\"temperatureZone\":\"AMBIENT\"},"
                        + "{\"locationCode\":\""
                        + SHELF
                        + "\",\"locationType\":\"PICK_FACE\",\"state\":\"ACTIVE\",\"temperatureZone\":\"AMBIENT\"},"
                        + "{\"locationCode\":\""
                        + STAGING
                        + "\",\"locationType\":\"STAGING\",\"state\":\"ACTIVE\",\"temperatureZone\":\"AMBIENT\"}"
                        + "],\"missing\":[]}")));
    location.stubFor(
        get(urlPathEqualTo("/api/v1/internal/pick-faces"))
            .willReturn(
                okJson(
                    "{\"truncated\":false,\"items\":[{\"centerId\":\"CDC01\",\"locationCode\":\""
                        + SHELF
                        + "\",\"temperatureZone\":\"AMBIENT\",\"maxQty\":80,\"pickFaceMinQty\":10,\"pickFaceTargetQty\":40}]}")));
    location.stubFor(
        post(urlPathEqualTo("/api/v1/internal/space-reservations"))
            .willReturn(okJson("{\"reservationId\":\"SR-1\",\"state\":\"RESERVED\"}")));
    location.stubFor(
        post(urlPathMatching("/api/v1/internal/space-reservations/.*/releases"))
            .willReturn(okJson("{\"reservationId\":\"SR-1\",\"state\":\"PARTIALLY_CONSUMED\"}")));
  }

  @Test
  void putawayClaimResultsAndReplenishment() {
    stubs(5);
    String base = "/cdcs/" + CENTER + "/putaway-tasks";
    // 1) 적치 지시 — 검사·예약 → DISPATCHED, 같은 키 같은 작업, 출발 부족 409, 출고대기 목적 409
    Map<String, Object> pt =
        Map.of(
            "sku", SKU, "lot", "L1", "fromLocationCode", RACK, "toLocationCode", SHELF, "qty", 12);
    String k = key();
    Res c = send(MANAGER, base, k, pt);
    assertThat(c.status()).as(c.body().toString()).isEqualTo(201);
    String taskId = c.body().path("taskId").asString();
    assertThat(c.body().path("state").asString()).isEqualTo("DISPATCHED");
    assertThat(c.body().path("reservationId").asString()).isEqualTo("SR-1");
    assertThat(send(MANAGER, base, k, pt).body().path("taskId").asString()).isEqualTo(taskId);
    Map<String, Object> big =
        Map.of(
            "sku", SKU, "lot", "L1", "fromLocationCode", RACK, "toLocationCode", SHELF, "qty", 40);
    Res short1 = send(MANAGER, base, key(), big); // 48 − 진행 중 12 = 36 < 40
    assertThat(short1.status()).isEqualTo(409);
    assertThat(short1.body().path("code").asString()).isEqualTo("SOURCE_SHORT");
    Map<String, Object> bad =
        Map.of(
            "sku", SKU, "lot", "L1", "fromLocationCode", RACK, "toLocationCode", STAGING, "qty", 1);
    assertThat(send(MANAGER, base, key(), bad).body().path("code").asString())
        .isEqualTo("DESTINATION_INVALID");

    // 2) 설비 다음 작업 받기 — 같은 설비는 같은 작업
    Map<String, Object> claim = Map.of("cdcId", CENTER, "deviceId", "AGV-1");
    Res got = send(SERVICE, "/internal/putaway-task-claims", key(), claim);
    assertThat(got.status()).as(got.body().toString()).isEqualTo(200);
    assertThat(got.body().path("taskId").asString()).isEqualTo(taskId);
    assertThat(got.body().path("state").asString()).isEqualTo("IN_PROGRESS");
    assertThat(
            send(SERVICE, "/internal/putaway-task-claims", key(), claim)
                .body()
                .path("taskId")
                .asString())
        .isEqualTo(taskId);

    // 3) 결과 — 일부 5(재고 이동·예약 소비), 같은 eventId 그대로, 전량인데 수량 틀림 400, 나머지 7 → COMPLETED
    Map<String, Object> r1 =
        Map.of(
            "eventId",
            "EV-1",
            "taskId",
            taskId,
            "deviceId",
            "AGV-1",
            "status",
            "PARTIAL",
            "completedQty",
            5);
    Res p = send(SERVICE, "/internal/work-results", (String) r1.get("eventId"), r1);
    assertThat(p.status()).as(p.body().toString()).isEqualTo(200);
    assertThat(p.body().path("state").asString()).isEqualTo("PARTIALLY_COMPLETED");
    assertThat(p.body().path("completedQty").asInt()).isEqualTo(5);
    inventory.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/move-postings"))
            .withHeader("Idempotency-Key", equalTo("inb-move-EV-1"))
            .withRequestBody(matchingJsonPath("$.moveKind", equalTo("PUTAWAY")))
            .withRequestBody(matchingJsonPath("$.lines[0].qty", equalTo("5"))));
    location.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/space-reservations/SR-1/releases"))
            .withRequestBody(matchingJsonPath("$.action", equalTo("CONSUME"))));
    assertThat(
            send(SERVICE, "/internal/work-results", (String) r1.get("eventId"), r1)
                .body()
                .path("completedQty")
                .asInt())
        .isEqualTo(5);
    Map<String, Object> wrong =
        Map.of(
            "eventId",
            "EV-2",
            "taskId",
            taskId,
            "deviceId",
            "AGV-1",
            "status",
            "SUCCESS",
            "completedQty",
            6);
    assertThat(
            send(SERVICE, "/internal/work-results", (String) wrong.get("eventId"), wrong).status())
        .isEqualTo(400);
    Map<String, Object> done =
        Map.of(
            "eventId",
            "EV-3",
            "taskId",
            taskId,
            "deviceId",
            "AGV-1",
            "status",
            "SUCCESS",
            "completedQty",
            7);
    Res d = send(SERVICE, "/internal/work-results", (String) done.get("eventId"), done);
    assertThat(d.body().path("state").asString()).as(d.body().toString()).isEqualTo("COMPLETED");
    assertThat(d.body().path("results").size()).isEqualTo(2);
    // 다른 설비는 결과를 못 보냄
    Map<String, Object> other =
        Map.of(
            "eventId",
            "EV-4",
            "taskId",
            taskId,
            "deviceId",
            "AGV-9",
            "status",
            "PARTIAL",
            "completedQty",
            1);
    assertThat(
            send(SERVICE, "/internal/work-results", (String) other.get("eventId"), other).status())
        .isEqualTo(409);

    // 4) 자동 보충 — 현재 5 ≤ 최소 10, 필요 35, 입수 12 → 24. 다시 훑으면 진행 중 24 → 필요 11 → 배수 때문에 0(BELOW_UNIT)
    replenishment.sweep();
    JsonNode chk =
        read(MANAGER, "/cdcs/" + CENTER + "/replenishment-checks").body().path("items").get(0);
    assertThat(chk.path("reason").asString()).as(chk.toString()).isEqualTo("CREATED");
    assertThat(chk.path("needQty").asInt()).isEqualTo(35);
    assertThat(chk.path("orderedQty").asInt()).isEqualTo(24);
    assertThat(chk.path("unitQty").asInt()).isEqualTo(12);
    String rep = chk.path("taskId").asString();
    JsonNode rt = read(MANAGER, base + "/" + rep).body();
    assertThat(rt.path("kind").asString()).isEqualTo("REPLENISHMENT");
    assertThat(rt.path("fromLocationCode").asString()).isEqualTo(RACK);
    replenishment.sweep();
    assertThat(
            read(MANAGER, "/cdcs/" + CENTER + "/replenishment-checks")
                .body()
                .path("items")
                .get(0)
                .path("reason")
                .asString())
        .isEqualTo("BELOW_UNIT");

    // 5) 보충 작업 실패 → FAILED, 남은 예약 해제
    Res g2 =
        send(
            SERVICE,
            "/internal/putaway-task-claims",
            key(),
            Map.of("cdcId", CENTER, "deviceId", "AGV-2", "kinds", List.of("REPLENISHMENT")));
    assertThat(g2.body().path("taskId").asString()).isEqualTo(rep);
    Res f =
        send(
            SERVICE,
            "/internal/work-results",
            "EV-5",
            Map.of(
                "eventId",
                "EV-5",
                "taskId",
                rep,
                "deviceId",
                "AGV-2",
                "status",
                "FAILED",
                "completedQty",
                0,
                "failureReason",
                "포크 고장"));
    assertThat(f.body().path("state").asString()).isEqualTo("FAILED");
    location.verify(
        postRequestedFor(urlPathMatching("/api/v1/internal/space-reservations/.*/releases"))
            .withHeader("Idempotency-Key", equalTo("inb-release-" + rep))
            .withRequestBody(matchingJsonPath("$.action", equalTo("RELEASE"))));
    // 받을 작업이 없으면 204
    assertThat(
            send(
                    SERVICE,
                    "/internal/putaway-task-claims",
                    key(),
                    Map.of("cdcId", CENTER, "deviceId", "AGV-3"))
                .status())
        .isEqualTo(204);
    // 목록
    assertThat(
            read(MANAGER, base + "?state=COMPLETED")
                .body()
                .path("items")
                .findValuesAsString("taskId"))
        .contains(taskId);
  }
}
