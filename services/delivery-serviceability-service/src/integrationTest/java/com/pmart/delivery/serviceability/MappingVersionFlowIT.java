package com.pmart.delivery.serviceability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
 * 권역 매핑: 운영자 초안 → ApprovalDecided(SERVICEABILITY, Kafka) → 시험 주소 검증 →
 * 발효(ServiceabilityVersionApplied) → 좌표 판정. 실제 PostgreSQL·Kafka.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.serviceability.kakao.base-url=${kakao.url}",
      "pmart.serviceability.kakao.rest-api-key=it-key"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "kakao", baseUrlProperties = "kakao.url"))
class MappingVersionFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  static final String OPS = TestJwt.operator("ops-1", List.of("OPS"), List.of());
  static final String CUSTOMER = TestJwt.customer("CU-1");
  static final String GUEST = TestJwt.token("GST-1", "GUEST", List.of(), List.of(), "it");

  @InjectWireMock("kakao")
  WireMockServer kakao;

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    // 카카오 로컬: 질의마다 응답을 나눈다(좌표는 [x=경도, y=위도] 문자열)
    address("IN-ROAD", doc("서울 서초구 안쪽로 1", "06600", 127.02, 37.50));
    address("OUT-ROAD", doc("서울 종로구 바깥로 1", "03000", 127.02, 37.60));
    address("NONE-ROAD");
    address("TWO-ROAD", doc("A로 1", "1", 127.02, 37.50), doc("A로 1", "2", 127.02, 37.60));
    address("SAME-ROAD", doc("B로 1", "1", 127.02, 37.50), doc("B로 1", "1", 127.02, 37.50));
    kakaoFails("DOWN-ROAD", 500);
    kakaoFails("QUOTA-ROAD", 429);
    kakao.stubFor(
        WireMock.get(urlPathEqualTo("/v2/local/geo/coord2address.json"))
            .willReturn(
                json(
                    200,
                    Map.of(
                        "documents",
                        List.of(
                            Map.of(
                                "road_address",
                                Map.of("address_name", "서울 서초구 안쪽로 1", "zone_no", "06600"),
                                "address",
                                Map.of("address_name", "서울 서초구 안쪽동 1-1")))))));
  }

  static Map<String, Object> doc(String road, String zoneNo, double x, double y) {
    return Map.of(
        "x", String.valueOf(x),
        "y", String.valueOf(y),
        "road_address", Map.of("address_name", road, "zone_no", zoneNo),
        "address", Map.of("address_name", road + " 지번"));
  }

  @SafeVarargs
  final void address(String query, Map<String, Object>... docs) {
    kakao.stubFor(
        WireMock.get(urlPathEqualTo("/v2/local/search/address.json"))
            .withQueryParam("query", equalTo(query))
            .willReturn(json(200, Map.of("documents", List.of(docs)))));
  }

  void kakaoFails(String query, int status) {
    kakao.stubFor(
        WireMock.get(urlPathEqualTo("/v2/local/search/address.json"))
            .withQueryParam("query", equalTo(query))
            .willReturn(json(status, Map.of("errorType", "x", "message", "x"))));
  }

  com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, Object body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
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
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(
        e.getStatusCode().value(), mapper.readTree(e.getBody() == null ? "{}" : e.getBody()));
  }

  static List<List<Double>> box(double x0, double y0, double x1, double y1) {
    return List.of(
        List.of(x0, y0), List.of(x1, y0), List.of(x1, y1), List.of(x0, y1), List.of(x0, y0));
  }

  static Map<String, Object> zone(
      String id, String mfc, double x0, double y0, double x1, double y1) {
    return Map.of(
        "zoneId", id,
        "mfcId", mfc,
        "name", id,
        "geometry", Map.of("type", "Polygon", "coordinates", List.of(box(x0, y0, x1, y1))));
  }

  void decide(JsonNode approvalRequest, String decision, String hashOverride) {
    String approvalId = "AP-" + UUID.randomUUID();
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put(
        "draftHash",
        hashOverride != null
            ? hashOverride
            : approvalRequest
                .path("lines")
                .path(0)
                .path("attributes")
                .path("draftHash")
                .asString());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", approvalId);
    payload.put("type", "SERVICEABILITY");
    payload.put("decision", decision);
    payload.put(
        "targetRef",
        Map.of(
            "targetType", "ServiceabilityVersion",
            "targetId", approvalRequest.path("targetRef").asString(),
            "targetService", "delivery-serviceability-service"));
    payload.put(
        "lines", List.of(Map.of("lineId", "l1", "decision", decision, "attributes", attrs)));
    payload.put("approverId", "lead-1");
    payload.put("dossierVersion", 2);
    String json =
        EventEnvelopeFixtures.envelope("ApprovalDecided")
            .from("approval-service")
            .aggregate("Approval", approvalId, 2)
            .payload(payload)
            .buildJson();
    kafka.send("platform.approval.v1", approvalId, json).join();
  }

  String awaitState(int version, String expected) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    String s = null;
    while (Instant.now().isBefore(until)) {
      s =
          jdbc.queryForObject(
              "SELECT state FROM mapping_version WHERE version = ?", String.class, version);
      if (expected.equals(s)) {
        return s;
      }
      Thread.sleep(200);
    }
    return s;
  }

  /** 판정은 roadAddress 를 변환한 좌표로 한다. pin(lat·lng)은 고객 지도 핀이라 판정에 쓰이지 않아야 한다(ADR-0023). */
  Res check(String road, Double pinLat, Double pinLng) {
    Map<String, Object> address = new LinkedHashMap<>();
    if (road != null) {
      address.put("roadAddress", road);
    }
    if (pinLat != null) {
      address.put("lat", pinLat);
      address.put("lng", pinLng);
    }
    return post(
        CUSTOMER, "/serviceability-checks", Map.of("address", address, "purpose", "ADDRESS_BOOK"));
  }

  Res testAddress(String token, double lat, double lng) {
    return post(
        token,
        "/serviceability-checks",
        Map.of(
            "address",
            Map.of("roadAddress", "시험", "lat", lat, "lng", lng),
            "purpose",
            "TEST_ADDRESS"));
  }

  @Test
  void draftApprovalActivationAndChecks() throws Exception {
    // 발효 버전 없음 → CONFIG_REQUIRED (시험 순서와 무관하게 시작 시점에만 확인)
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM mapping_version WHERE state = 'ACTIVE'", Integer.class)
        == 0) {
      assertThat(check("IN-ROAD", null, null).status()).isEqualTo(422);
      // 주소 검색이 시간 초과(읽기 3초)보다 느려도 발효 버전이 없으면 검색 전에 422 — 예전에는 검색을 먼저 해
      // UNKNOWN(200)이 나와 부하가 큰 전체 시험에서 이 단계가 가끔 실패했다(2026-11-03)
      kakao.stubFor(
          WireMock.get(urlPathEqualTo("/v2/local/search/address.json"))
              .withQueryParam("query", equalTo("SLOW-ROAD"))
              .willReturn(json(200, Map.of("documents", List.of())).withFixedDelay(4_000)));
      assertThat(check("SLOW-ROAD", null, null).status()).isEqualTo(422);
      kakao.verify(
          0,
          WireMock.getRequestedFor(urlPathEqualTo("/v2/local/search/address.json"))
              .withQueryParam("query", equalTo("SLOW-ROAD")));
    }
    // 고객은 초안을 만들 수 없다
    Map<String, Object> draft =
        Map.of(
            "zones", List.of(zone("Z-SEOCHO", "MFC-BSN-01", 127.00, 37.48, 127.04, 37.52)),
            "changeSummary", "서초 1개 권역",
            "testAddresses",
                List.of(
                    Map.of(
                        "label",
                        "안",
                        "lat",
                        37.50,
                        "lng",
                        127.02,
                        "expectedResult",
                        "SERVICEABLE",
                        "expectedMfcId",
                        "MFC-BSN-01"),
                    Map.of(
                        "label",
                        "밖",
                        "lat",
                        37.60,
                        "lng",
                        127.02,
                        "expectedResult",
                        "OUT_OF_AREA")));
    assertThat(post(CUSTOMER, "/serviceability-versions", draft).status()).isEqualTo(403);

    Res created = post(OPS, "/serviceability-versions", draft);
    assertThat(created.status()).isEqualTo(201);
    int version = created.body().path("version").path("version").asInt();
    JsonNode approval = created.body().path("approvalRequest");
    assertThat(approval.path("type").asString()).isEqualTo("SERVICEABILITY");
    assertThat(approval.path("targetRef").asString()).isEqualTo("serviceabilityVersion:" + version);

    // 승인 범위 불일치(다른 해시) → 발효하지 않음
    Res other = post(OPS, "/serviceability-versions", draft);
    int otherVersion = other.body().path("version").path("version").asInt();
    decide(other.body().path("approvalRequest"), "APPROVED", "deadbeef");
    assertThat(awaitState(otherVersion, "REJECTED")).isEqualTo("REJECTED");

    // 승인 → 시험 주소 통과 → 발효
    decide(approval, "APPROVED", null);
    assertThat(awaitState(version, "ACTIVE")).isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE type = 'ServiceabilityVersionApplied'"
                    + " AND topic = 'delivery.serviceability.v1'",
                Integer.class))
        .isGreaterThanOrEqualTo(1);
    assertThat(
            get(OPS, "/serviceability-versions/" + version)
                .body()
                .path("testAddressesVerified")
                .asInt())
        .isEqualTo(2);

    // 판정
    // 핀은 권역 밖이어도 도로명 좌표(안쪽)로 판정한다
    Res inside = check("IN-ROAD", 37.60, 127.02);
    assertThat(inside.status()).isEqualTo(200);
    assertThat(inside.body().path("result").asString()).isEqualTo("SERVICEABLE");
    assertThat(inside.body().path("mfcId").asString()).isEqualTo("MFC-BSN-01");
    assertThat(inside.body().path("mappingVersion").asInt()).isEqualTo(version);

    // 배송 가능 조건: 발효 권역의 MFC 만, 확정값만(나머지 NOT_SET)
    Res conditions = get(CUSTOMER, "/delivery-conditions?mfcId=MFC-BSN-01");
    assertThat(conditions.status()).isEqualTo(200);
    assertThat(conditions.body().path("deliveryModes").toString())
        .isEqualTo("[\"SINGLE\",\"BUNDLED\",\"SCHEDULED\"]");
    assertThat(conditions.body().path("maxWeightG").path("value").asInt()).isEqualTo(10000);
    assertThat(conditions.body().path("maxVolumeL").path("state").asString()).isEqualTo("NOT_SET");
    assertThat(conditions.body().path("timeRestriction").path("end").asString()).isEqualTo("08:00");
    assertThat(
            get(
                    TestJwt.token("GST-1", "GUEST", List.of(), List.of(), "it"),
                    "/delivery-conditions?mfcId=MFC-BSN-01")
                .status())
        .as("비회원도 조회")
        .isEqualTo(200);
    assertThat(get(CUSTOMER, "/delivery-conditions?mfcId=MFC-NONE").status()).isEqualTo(404);
    // 핀이 권역 안이어도 도로명 좌표(밖)로 판정한다
    assertThat(check("OUT-ROAD", 37.50, 127.02).body().path("result").asString())
        .isEqualTo("OUT_OF_AREA");
    // 비회원(토큰 없음)도 판정 가능(계약 security: [])
    var anonymous =
        http.post()
            .uri("/serviceability-checks")
            .headers(
                h -> {
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-service");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("address", Map.of("roadAddress", "IN-ROAD"), "purpose", "ADDRESS_BOOK"))
            .retrieve()
            .toEntity(String.class);
    assertThat(anonymous.getStatusCode().value()).isEqualTo(200);
    assertThat(mapper.readTree(anonymous.getBody()).path("result").asString())
        .isEqualTo("SERVICEABLE");
    Res noGeo = check(null, 37.50, 127.02);
    assertThat(noGeo.body().path("result").asString()).isEqualTo("NEEDS_INPUT");
    assertThat(noGeo.body().path("missingFields").path(0).asString()).isEqualTo("roadAddress");
    // 0건·서로 다른 여러 곳은 임의로 고르지 않는다. 같은 좌표 중복은 하나로 본다
    assertThat(check("NONE-ROAD", 37.50, 127.02).body().path("result").asString())
        .isEqualTo("NEEDS_INPUT");
    assertThat(check("TWO-ROAD", null, null).body().path("result").asString())
        .isEqualTo("NEEDS_INPUT");
    assertThat(check("SAME-ROAD", null, null).body().path("result").asString())
        .isEqualTo("SERVICEABLE");
    // 주소 검색 장애·사용량 소진 → 판정 불명(가능·불가를 추측하지 않는다)
    assertThat(check("DOWN-ROAD", 37.50, 127.02).body().path("result").asString())
        .isEqualTo("UNKNOWN");
    assertThat(check("QUOTA-ROAD", 37.50, 127.02).body().path("result").asString())
        .isEqualTo("UNKNOWN");
    // 판정용 변환은 정확 일치, 키는 KakaoAK 헤더
    kakao.verify(
        getRequestedFor(urlPathEqualTo("/v2/local/search/address.json"))
            .withQueryParam("query", equalTo("IN-ROAD"))
            .withQueryParam("analyze_type", equalTo("exact"))
            .withHeader("Authorization", equalTo("KakaoAK it-key")));
    // 시험 좌표(TEST_ADDRESS)는 운영자만 요청 좌표로 판정
    assertThat(testAddress(CUSTOMER, 37.50, 127.02).status()).isEqualTo(403);
    Res opsTest = testAddress(OPS, 37.50, 127.02);
    assertThat(opsTest.status()).isEqualTo(200);
    assertThat(opsTest.body().path("result").asString()).isEqualTo("SERVICEABLE");
    // 재조회
    assertThat(
            get(CUSTOMER, "/serviceability-checks/" + inside.body().path("checkId").asString())
                .body()
                .path("result")
                .asString())
        .isEqualTo("SERVICEABLE");

    // 시험 주소 기대와 다르면 발효하지 않는다(기존 발효 버전 유지)
    Map<String, Object> wrong =
        Map.of(
            "zones", List.of(zone("Z-SEOCHO", "MFC-BSN-01", 127.00, 37.48, 127.04, 37.52)),
            "testAddresses",
                List.of(Map.of("lat", 37.60, "lng", 127.02, "expectedResult", "SERVICEABLE")));
    Res w = post(OPS, "/serviceability-versions", wrong);
    int wv = w.body().path("version").path("version").asInt();
    decide(w.body().path("approvalRequest"), "APPROVED", null);
    assertThat(awaitState(wv, "REJECTED")).isEqualTo("REJECTED");
    assertThat(check("IN-ROAD", null, null).body().path("mappingVersion").asInt())
        .isEqualTo(version);

    // 잘못된 경계(닫히지 않은 링)는 초안 단계에서 거절
    Map<String, Object> bad =
        Map.of(
            "zones",
            List.of(
                Map.of(
                    "zoneId", "Z-BAD",
                    "mfcId", "MFC-BSN-01",
                    "geometry",
                        Map.of(
                            "type",
                            "Polygon",
                            "coordinates",
                            List.of(
                                List.of(
                                    List.of(127.0, 37.0),
                                    List.of(127.1, 37.0),
                                    List.of(127.1, 37.1),
                                    List.of(127.0, 37.1)))))));
    assertThat(post(OPS, "/serviceability-versions", bad).status()).isEqualTo(400);
  }

  @Test
  void addressLookupsForCustomersAndGuests() {
    // 주소 → 좌표(화면용 유사 검색). 비회원도 된다
    Res found = get(GUEST, "/address-geocodes?query=IN-ROAD");
    assertThat(found.status()).isEqualTo(200);
    JsonNode first = found.body().path("results").path(0);
    assertThat(first.path("roadAddress").asString()).isEqualTo("서울 서초구 안쪽로 1");
    assertThat(first.path("zoneNo").asString()).isEqualTo("06600");
    assertThat(first.path("lat").asDouble()).isEqualTo(37.50);
    assertThat(first.path("lng").asDouble()).isEqualTo(127.02);
    kakao.verify(
        getRequestedFor(urlPathEqualTo("/v2/local/search/address.json"))
            .withQueryParam("analyze_type", equalTo("similar")));
    assertThat(get(CUSTOMER, "/address-geocodes?query=IN-ROAD").status()).isEqualTo(200);

    // 0건은 200 + 빈 목록, 장애·사용량 소진과 구분한다
    Res none = get(CUSTOMER, "/address-geocodes?query=NONE-ROAD");
    assertThat(none.status()).isEqualTo(200);
    assertThat(none.body().path("results").size()).isZero();
    Res down = get(CUSTOMER, "/address-geocodes?query=DOWN-ROAD");
    assertThat(down.status()).isEqualTo(503);
    assertThat(down.body().path("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    Res quota = get(CUSTOMER, "/address-geocodes?query=QUOTA-ROAD");
    assertThat(quota.status()).isEqualTo(429);
    assertThat(quota.body().path("code").asString()).isEqualTo("QUOTA_EXCEEDED");
    assertThat(get(CUSTOMER, "/address-geocodes?query=").status()).isEqualTo(400);

    // 배차용 내부 조회(2026-11-13): 토큰 없이 내부 호출, 주소는 본문(추적 기록에 안 남게 — H3), 판정과 같은 정확
    // 일치(analyze_type=exact)
    var internal =
        http.post()
            .uri("/internal/address-geocodes")
            .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header("X-Source-System", "delivery-dispatch-service")
            .body(Map.of("query", "IN-ROAD"))
            .retrieve()
            .toEntity(String.class);
    assertThat(internal.getStatusCode().value()).isEqualTo(200);
    assertThat(mapper.readTree(internal.getBody()).path("results").path(0).path("lat").asDouble())
        .isEqualTo(37.50);
    kakao.verify(
        getRequestedFor(urlPathEqualTo("/v2/local/search/address.json"))
            .withQueryParam("query", equalTo("IN-ROAD"))
            .withQueryParam("analyze_type", equalTo("exact")));

    // 좌표 → 주소(핀 위치 표시)
    Res reverse = get(GUEST, "/address-reverse-geocodes?lat=37.5&lng=127.02");
    assertThat(reverse.status()).isEqualTo(200);
    assertThat(reverse.body().path("results").path(0).path("roadAddress").asString())
        .isEqualTo("서울 서초구 안쪽로 1");
    assertThat(reverse.body().path("results").path(0).path("jibunAddress").asString())
        .isEqualTo("서울 서초구 안쪽동 1-1");
    kakao.verify(
        getRequestedFor(urlPathEqualTo("/v2/local/geo/coord2address.json"))
            .withQueryParam("x", equalTo("127.02"))
            .withQueryParam("y", equalTo("37.5")));
    assertThat(get(CUSTOMER, "/address-reverse-geocodes?lat=91&lng=127").status()).isEqualTo(400);

    // 토큰 없이는 안 된다(무료 사용량 보호)
    var anonymous =
        http.get().uri("/address-geocodes?query=IN-ROAD").retrieve().toEntity(String.class);
    assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
  }
}
