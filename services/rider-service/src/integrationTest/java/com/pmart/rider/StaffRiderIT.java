package com.pmart.rider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 0.4.0 직원 라이더 찾기·상세·원문 열람(BOD-3·6, ADR-0062)과 RIDER_OPS 계정 제한(BOD-1). 실제 PostgreSQL·Kafka,
 * audit-service 는 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "audit",
        baseUrlProperties = {"pmart.rider.audit-base-url"}))
class StaffRiderIT extends PostgresKafkaContainerSupport {

  static final String AUDIT_PATH = "/api/v1/internal/pii-access-logs";
  static final String CS = TestJwt.operator("cs-1", List.of("CS_AGENT"), List.of());
  static final String OPS = TestJwt.operator("ro-1", List.of("RIDER_OPS"), List.of());
  static final String SUPERVISOR =
      TestJwt.operator("sv-1", List.of("MFC_SUPERVISOR"), List.of("MFC-IT-1"));

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("audit")
  WireMockServer audit;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    audit.resetAll();
  }

  @Test
  void staffFindsRiderByIdOrExactPhoneWithMaskedValues() {
    String rider = "RD-st-" + UUID.randomUUID();
    String phone = phone();
    assertThat(signup(rider, phone).getStatusCode().value()).isEqualTo(201);
    String hyphenated =
        phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);

    JsonNode byPhone = json(get(CS, "/riders?phone=" + hyphenated));
    assertThat(byPhone.path("items")).hasSize(1);
    JsonNode row = byPhone.path("items").get(0);
    assertThat(row.path("riderId").asString()).isEqualTo(rider);
    assertThat(row.path("nameMasked").asString()).isEqualTo("김**더");
    assertThat(row.path("phoneMasked").asString()).doesNotContain(phone.substring(3, 7));
    assertThat(row.has("phone")).isFalse();
    assertThat(row.has("name")).isFalse();
    assertThat(byPhone.path("hasNext").asBoolean()).isFalse();
    assertThat(row.path("signupStatus").asString())
        .isEqualTo("APPROVED"); // 정책 전까지 자동 승인(2026-11-17)

    assertThat(json(get(OPS, "/riders?riderId=" + rider)).path("items")).hasSize(1);
    // 부분 번호·이름으로는 찾지 않는다
    assertThat(get(OPS, "/riders?phone=" + phone.substring(3)).getStatusCode().value())
        .isEqualTo(400);
    assertThat(get(OPS, "/riders?sort=NAME").getStatusCode().value()).isEqualTo(400);
    // 권한 — 센터 관리자·라이더는 403
    assertThat(get(SUPERVISOR, "/riders?riderId=" + rider).getStatusCode().value()).isEqualTo(403);
    assertThat(get(TestJwt.rider(rider), "/riders?riderId=" + rider).getStatusCode().value())
        .isEqualTo(403);

    JsonNode detail = json(get(OPS, "/riders/" + rider));
    assertThat(detail.path("version").asInt()).isZero();
    assertThat(detail.path("nameMasked").asString()).isEqualTo("김**더");
    assertThat(detail.path("deliveryAreas").get(0).path("mfcId").asString()).isEqualTo("MFC-IT-1");
    assertThat(detail.has("bankAccountMasked")).isFalse();
    assertThat(detail.has("residence")).isFalse();
    assertThat(detail.path("signupStatus").asString()).isEqualTo("APPROVED");
    assertThat(detail.path("signupDecidedBy").asString()).isEqualTo("AUTO");
    // 대시보드 라이더 수(2026-11-17)
    JsonNode stats = json(get(OPS, "/riders/stats?mfcId=MFC-IT-1"));
    assertThat(stats.path("total").asInt()).isPositive();
    assertThat(stats.path("joinedToday").asInt()).isPositive();
    assertThat(stats.path("byRestriction").path("NORMAL").asInt()).isPositive();
    assertThat(json(get(OPS, "/riders/stats?mfcId=MFC-NONE")).path("total").asInt()).isZero();
    assertThat(get(SUPERVISOR, "/riders/stats").getStatusCode().value()).isEqualTo(403);
    assertThat(get(OPS, "/riders/RD-none-" + UUID.randomUUID()).getStatusCode().value())
        .isEqualTo(404);
    // /riders/me 는 그대로 라이더 본인 API
    assertThat(json(get(TestJwt.rider(rider), "/riders/me")).path("riderId").asString())
        .isEqualTo(rider);
  }

  /** 쪽 넘김 — 2쪽부터 한 명씩 빠지던 것(건너뛸 수를 size+1 로 계산, PC 1 #17 검토). */
  @Test
  void staffSearchPagesWithoutSkippingRiders() {
    List<String> mine = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String rider = "RD-pg-" + UUID.randomUUID();
      assertThat(signup(rider, phone(), "WALK").getStatusCode().value()).isEqualTo(201);
      mine.add(rider);
    }
    List<String> all = ids(json(get(OPS, "/riders?vehicleType=WALK&size=50")));
    assertThat(all).containsAll(mine);

    List<String> paged = new java.util.ArrayList<>();
    for (int page = 0; ; page++) {
      JsonNode p = json(get(OPS, "/riders?vehicleType=WALK&size=2&page=" + page));
      assertThat(p.path("page").asInt()).isEqualTo(page);
      paged.addAll(ids(p));
      if (!p.path("hasNext").asBoolean()) {
        break;
      }
      assertThat(p.path("items")).hasSize(2);
    }
    assertThat(paged).containsExactlyElementsOf(all);
  }

  @Test
  void riderOpsRestrictsWithVersionFromLookups() {
    String rider = "RD-st-" + UUID.randomUUID();
    assertThat(signup(rider, phone()).getStatusCode().value()).isEqualTo(201);

    JsonNode history = json(get(OPS, "/riders/" + rider + "/restrictions"));
    assertThat(history.path("current").asString()).isEqualTo("NORMAL");
    int version = history.path("version").asInt();
    assertThat(version).isEqualTo(json(get(OPS, "/riders/" + rider)).path("version").asInt());

    ResponseEntity<String> restricted =
        http.post()
            .uri("/riders/{id}/restrictions", rider)
            .headers(
                h -> {
                  h.setBearerAuth(OPS);
                  h.set("Idempotency-Key", "r-" + rider);
                  h.set("X-Source-System", "backoffice");
                  h.set("X-Expected-Version", String.valueOf(version));
                })
            .body(Map.of("restriction", "DISPATCH_RESTRICTED", "reason", "컴플레인 확인"))
            .retrieve()
            .toEntity(String.class);
    assertThat(restricted.getStatusCode().value()).isEqualTo(200);

    assertThat(
            json(get(CS, "/riders?restriction=DISPATCH_RESTRICTED&riderId=" + rider)).path("items"))
        .hasSize(1);
    assertThat(json(get(CS, "/riders?restriction=NORMAL&riderId=" + rider)).path("items"))
        .isEmpty();
    assertThat(json(get(OPS, "/riders/" + rider + "/restrictions")).path("version").asInt())
        .isEqualTo(version + 1);
    // 상담원은 제한을 다루지 않는다
    assertThat(get(CS, "/riders/" + rider + "/restrictions").getStatusCode().value())
        .isEqualTo(403);
    assertThat(get(OPS, "/riders/restriction-reviews").getStatusCode().value()).isEqualTo(200);
  }

  @Test
  void revealRecordsAuditFirstAndGivesNothingWhenAuditFails() {
    String rider = "RD-st-" + UUID.randomUUID();
    String phone = phone();
    assertThat(signup(rider, phone).getStatusCode().value()).isEqualTo(201);

    // 사유가 짧으면 기록도 원문도 없다
    assertThat(reveal(OPS, rider, "k1", Map.of("reason", " ")).getStatusCode().value())
        .isEqualTo(400);
    audit.verify(0, postRequestedFor(urlPathEqualTo(AUDIT_PATH)));

    audit.stubFor(
        WireMock.post(urlPathEqualTo(AUDIT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"piiAccessLogId\":\"PAL-1\",\"recordedAt\":\"2026-11-15T01:02:03Z\"}")));
    ResponseEntity<String> both = reveal(CS, rider, "k2", Map.of("reason", "라이더 컴플레인 확인 — 연락"));
    assertThat(both.getStatusCode().value()).isEqualTo(200);
    JsonNode b = json(both.getBody());
    assertThat(b.path("piiAccessLogId").asString()).isEqualTo("PAL-1");
    assertThat(b.path("name").asString()).isEqualTo("김라이더");
    assertThat(b.path("phone").asString()).isEqualTo(phone);
    audit.verify(
        1,
        postRequestedFor(urlPathEqualTo(AUDIT_PATH))
            .withHeader("X-Source-System", equalTo("rider-service"))
            .withRequestBody(matchingJsonPath("$.subjectType", equalTo("RIDER")))
            .withRequestBody(matchingJsonPath("$.subjectRef", equalTo(rider)))
            .withRequestBody(matchingJsonPath("$.caseRef", equalTo("RIDER:" + rider)))
            .withRequestBody(matchingJsonPath("$.actor.actorId", equalTo("cs-1")))
            .withRequestBody(matchingJsonPath("$.fields[0]", equalTo("NAME")))
            .withRequestBody(matchingJsonPath("$.fields[1]", equalTo("PHONE"))));

    JsonNode phoneOnly =
        json(
            reveal(OPS, rider, "k3", Map.of("reason", "연락처 확인", "fields", List.of("PHONE")))
                .getBody());
    assertThat(phoneOnly.path("phone").asString()).isEqualTo(phone);
    assertThat(phoneOnly.path("name").isNull() || phoneOnly.path("name").isMissingNode()).isTrue();

    // audit 이 기록하지 못하면 원문도 없다
    audit.resetAll();
    audit.stubFor(
        WireMock.post(urlPathEqualTo(AUDIT_PATH)).willReturn(aResponse().withStatus(503)));
    ResponseEntity<String> down = reveal(OPS, rider, "k4", Map.of("reason", "연락처 확인"));
    assertThat(down.getStatusCode().value()).isEqualTo(503);
    assertThat(down.getBody()).doesNotContain(phone);

    // 센터 관리자는 열람 못 함(기록 요청도 없음)
    audit.resetAll();
    assertThat(reveal(SUPERVISOR, rider, "k5", Map.of("reason", "연락처 확인")).getStatusCode().value())
        .isEqualTo(403);
    audit.verify(0, postRequestedFor(urlPathEqualTo(AUDIT_PATH)));
  }

  private ResponseEntity<String> reveal(
      String token, String rider, String key, Map<String, Object> body) {
    return http.post()
        .uri("/riders/{id}/pii-reveals", rider)
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "backoffice");
            })
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> get(String token, String uri) {
    return http.get()
        .uri(uri)
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> signup(String rider, String phone) {
    return signup(rider, phone, "MOTORCYCLE");
  }

  private ResponseEntity<String> signup(String rider, String phone, String vehicleType) {
    return http.post()
        .uri("/riders/signups")
        .headers(
            h -> {
              h.setBearerAuth(TestJwt.rider(rider));
              h.set("Idempotency-Key", "k-" + rider);
              h.set("X-Source-System", "rider-app");
            })
        .body(
            Map.of(
                "phone",
                phone,
                "name",
                "김라이더",
                "vehicleType",
                vehicleType,
                "deliveryAreas",
                List.of(Map.of("mfcId", "MFC-IT-1")),
                "consents",
                List.of(Map.of("consentType", "TERMS", "version", "2026-11")),
                // 오토바이·자동차·킥보드는 면허 필수(2026-11-16) — 라이더마다 고정된 시험 번호
                "licenseNumber",
                String.format(
                    "%012d", Math.floorMod(rider.hashCode(), 1_000_000_000L) * 1000L + 7)))
        .retrieve()
        .toEntity(String.class);
  }

  private static String phone() {
    return "010"
        + String.format(
            "%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
  }

  private static List<String> ids(JsonNode page) {
    List<String> out = new java.util.ArrayList<>();
    page.path("items").forEach(n -> out.add(n.path("riderId").asString()));
    return out;
  }

  private JsonNode json(ResponseEntity<String> r) {
    return json(r.getBody());
  }

  private JsonNode json(String body) {
    return mapper.readTree(body == null ? "{}" : body);
  }
}
