package com.pmart.cases;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 라이더 컴플레인(0.3.0, service-requests case R-5 — RS-3): 상담원이 기록 → 행 + RiderComplaintRecorded(Outbox,
 * platform.case.v1). 라이더는 delivery-dispatch 배정 조회(WireMock)로 채운다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.cases.delivery-dispatch-base-url=${dispatch.url}"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "dispatch", baseUrlProperties = "dispatch.url")})
class RiderComplaintsIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;

  @InjectWireMock("dispatch")
  WireMockServer dispatch;

  record Res(int status, JsonNode body) {}

  Res send(String method, String token, String path, @Nullable Object body, String key) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-web");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange(
        (req, res) -> {
          byte[] b = res.getBody().readAllBytes();
          return new Res(
              res.getStatusCode().value(),
              b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
        });
  }

  Res get(String token, String path) {
    return send("GET", token, path, null, UUID.randomUUID().toString());
  }

  static String id(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  void assigned(String deliveryId, String riderId) {
    assigned(deliveryId, riderId, null);
  }

  /** 0.33.0 R-12: 배정 응답의 mfcId(없으면 빼고). */
  void assigned(String deliveryId, String riderId, @Nullable String mfcId) {
    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/deliveries/" + deliveryId + "/assignment"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"assignmentId\":\"AS-1\",\"deliveryId\":\""
                            + deliveryId
                            + "\",\"riderId\":\""
                            + riderId
                            + (mfcId == null ? "" : "\",\"mfcId\":\"" + mfcId)
                            + "\",\"state\":\"PICKED_UP\",\"version\":3}")));
  }

  void notFound(String deliveryId, String code) {
    dispatch.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/deliveries/" + deliveryId + "/assignment"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,\"code\":\""
                            + code
                            + "\",\"traceId\":\"t\"}")));
  }

  JsonNode envelope(String complaintId) {
    return mapper.readTree(
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ?"
                + " AND type = 'RiderComplaintRecorded'",
            String.class,
            complaintId));
  }

  @Test
  void agentRecordsComplaintWithRiderFromDispatchAndEventInOutbox() {
    String delivery = id("DLV");
    String rider = id("RD");
    assigned(delivery, rider);
    String agent = TestJwt.operator("cs-r5", List.of("CS_AGENT"), List.of());
    String key = UUID.randomUUID().toString();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("deliveryId", delivery);
    body.put("complaintType", "DAMAGE");
    body.put("orderId", "ORD-R5");
    body.put("note", "고객 010-0000-0000 이 파손 사진을 보냄");

    Res created = send("POST", agent, "/rider-complaints", body, key);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String complaintId = created.body().path("complaintId").asString();
    assertThat(complaintId).startsWith("RCP-");
    assertThat(created.body().path("riderId").asString()).isEqualTo(rider);
    assertThat(created.body().path("source").asString()).isEqualTo("CS_AGENT");
    assertThat(created.body().path("recordedBy").asString()).isEqualTo("cs-r5");
    assertThat(created.body().path("note").asString()).contains("파손");
    // 내부 경로는 토큰 없이 X-Source-System 만(ADR-0026)
    dispatch.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/internal/deliveries/" + delivery + "/assignment"))
            .withHeader("X-Source-System", equalTo("case-service"))
            .withoutHeader("Authorization"));

    // 행
    assertThat(
            jdbc.queryForObject(
                "SELECT rider_id FROM rider_complaint WHERE complaint_id = ?",
                String.class,
                complaintId))
        .isEqualTo(rider);

    // 사건 — rider-service 가 읽는 칸 그대로, 메모·고객 정보 없음
    JsonNode env = envelope(complaintId);
    assertThat(env.path("eventType").asString()).isEqualTo("RiderComplaintRecorded");
    assertThat(env.path("aggregateType").asString()).isEqualTo("Case");
    assertThat(env.path("aggregateId").asString()).isEqualTo(complaintId);
    JsonNode p = env.path("payload");
    assertThat(p.path("complaintId").asString()).isEqualTo(complaintId);
    assertThat(p.path("riderId").asString()).isEqualTo(rider);
    assertThat(p.path("deliveryId").asString()).isEqualTo(delivery);
    assertThat(p.path("orderId").asString()).isEqualTo("ORD-R5");
    assertThat(p.path("complaintType").asString()).isEqualTo("DAMAGE");
    assertThat(p.path("source").asString()).isEqualTo("CS_AGENT");
    assertThat(Instant.parse(p.path("recordedAt").asString()))
        .isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
    assertThat(p.has("caseId")).isFalse();
    assertThat(p.has("note")).isFalse();
    assertThat(env.toString()).doesNotContain("010-0000-0000");
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE aggregateid = ?", String.class, complaintId))
        .isEqualTo("platform.case.v1");

    // 같은 키 재전송 → 같은 컴플레인, 사건 하나
    Res again = send("POST", agent, "/rider-complaints", body, key);
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.body().path("complaintId").asString()).isEqualTo(complaintId);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rider_complaint WHERE delivery_id = ?",
                Integer.class,
                delivery))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE type = 'RiderComplaintRecorded'"
                    + " AND payload->'payload'->>'deliveryId' = ?",
                Integer.class,
                delivery))
        .isEqualTo(1);
    // 같은 키·다른 본문 → 409
    Map<String, Object> other = new LinkedHashMap<>(body);
    other.put("complaintType", "REPORT");
    assertThat(send("POST", agent, "/rider-complaints", other, key).status()).isEqualTo(409);

    // 상세: RIDER_OPS 도 본다
    String riderOps = TestJwt.operator("ro-1", List.of("RIDER_OPS"), List.of());
    Res detail = get(riderOps, "/rider-complaints/" + complaintId);
    assertThat(detail.status()).isEqualTo(200);
    assertThat(detail.body().path("deliveryId").asString()).isEqualTo(delivery);
    assertThat(get(riderOps, "/rider-complaints/RCP-none").status()).isEqualTo(404);
  }

  @Test
  void linksInquiryCaseWhenAgentCameFromInquiry() {
    String customer = id("CU");
    Res inquiry =
        send(
            "POST",
            TestJwt.customer(customer),
            "/inquiries",
            Map.of("category", "DELIVERY", "body", "기사님이 불친절했어요"),
            UUID.randomUUID().toString());
    assertThat(inquiry.status()).as(inquiry.body().toString()).isEqualTo(201);
    String inquiryId = inquiry.body().path("inquiryId").asString();
    String caseId = inquiry.body().path("caseId").asString();

    String delivery = id("DLV");
    assigned(delivery, id("RD"));
    String admin = TestJwt.operator("adm-1", List.of("OPERATOR_ADMIN"), List.of());
    Res created =
        send(
            "POST",
            admin,
            "/rider-complaints",
            Map.of("deliveryId", delivery, "complaintType", "REPORT", "inquiryId", inquiryId),
            UUID.randomUUID().toString());
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    assertThat(created.body().path("caseId").asString()).isEqualTo(caseId);
    assertThat(created.body().path("inquiryId").asString()).isEqualTo(inquiryId);
    JsonNode p = envelope(created.body().path("complaintId").asString()).path("payload");
    assertThat(p.path("caseId").asString()).isEqualTo(caseId);
    assertThat(p.has("orderId")).isFalse();

    // 없는 문의 → 422
    Res bad =
        send(
            "POST",
            admin,
            "/rider-complaints",
            Map.of("deliveryId", delivery, "complaintType", "REPORT", "inquiryId", "INQ-none"),
            UUID.randomUUID().toString());
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.body().path("code").asString()).isEqualTo("INQUIRY_NOT_FOUND");
  }

  @Test
  void noAssignedRiderOrNoDeliveryIs422AndNothingRecorded() {
    String agent = TestJwt.operator("cs-r5b", List.of("CS_AGENT"), List.of());
    String unassigned = id("DLV");
    notFound(unassigned, "ASSIGNMENT_NOT_FOUND");
    Res r =
        send(
            "POST",
            agent,
            "/rider-complaints",
            Map.of("deliveryId", unassigned, "complaintType", "MISDELIVERY"),
            UUID.randomUUID().toString());
    assertThat(r.status()).as(r.body().toString()).isEqualTo(422);
    assertThat(r.body().path("code").asString()).isEqualTo("RIDER_NOT_ASSIGNED");

    String missing = id("DLV");
    notFound(missing, "DELIVERY_NOT_FOUND");
    Res m =
        send(
            "POST",
            agent,
            "/rider-complaints",
            Map.of("deliveryId", missing, "complaintType", "MISDELIVERY"),
            UUID.randomUUID().toString());
    assertThat(m.status()).isEqualTo(422);
    assertThat(m.body().path("code").asString()).isEqualTo("DELIVERY_NOT_FOUND");

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM rider_complaint WHERE delivery_id IN (?, ?)",
                Integer.class,
                unassigned,
                missing))
        .isZero();
  }

  @Test
  void wrongRolesAre403() {
    String delivery = id("DLV");
    assigned(delivery, id("RD"));
    Map<String, Object> body = Map.of("deliveryId", delivery, "complaintType", "REPORT");
    String md = TestJwt.operator("md-1", List.of("MD"), List.of());
    String riderOps = TestJwt.operator("ro-2", List.of("RIDER_OPS"), List.of());
    // 기록: MD·RIDER_OPS·고객·라이더 불가
    assertThat(send("POST", md, "/rider-complaints", body, UUID.randomUUID().toString()).status())
        .isEqualTo(403);
    assertThat(
            send("POST", riderOps, "/rider-complaints", body, UUID.randomUUID().toString())
                .status())
        .isEqualTo(403);
    assertThat(
            send(
                    "POST",
                    TestJwt.customer(id("CU")),
                    "/rider-complaints",
                    body,
                    UUID.randomUUID().toString())
                .status())
        .isEqualTo(403);
    // 조회: MD 불가
    assertThat(get(md, "/rider-complaints").status()).isEqualTo(403);
    assertThat(get(md, "/rider-complaints/RCP-x").status()).isEqualTo(403);
    assertThat(get(md, "/rider-complaints/counts").status()).as("종류별 수도 목록과 같은 권한").isEqualTo(403);
    // 거절된 요청은 dispatch 를 부르지 않는다
    dispatch.verify(
        0,
        getRequestedFor(urlPathEqualTo("/api/v1/internal/deliveries/" + delivery + "/assignment")));
  }

  /** 0.33.0 R-12: 기록 때 배정 mfcId 를 센터로 저장, 목록·수를 센터로 거른다(센터 없는 기록은 빠진다). */
  @Test
  void centerFromDispatchIsStoredAndFiltersListAndCounts() {
    String agent = TestJwt.operator("cs-r12", List.of("CS_AGENT"), List.of());
    String rider = id("RD");
    String mokpo = id("MFC");
    String busan = id("MFC");
    String d1 = id("DLV");
    String d2 = id("DLV");
    String d3 = id("DLV");
    assigned(d1, rider, mokpo);
    assigned(d2, rider, busan);
    assigned(d3, rider);
    String c1 = record(agent, d1, "REPORT");
    String c2 = record(agent, d2, "DAMAGE");
    String c3 = record(agent, d3, "REPORT");

    assertThat(get(agent, "/rider-complaints/" + c1).body().path("centerId").asString())
        .isEqualTo(mokpo);
    assertThat(get(agent, "/rider-complaints/" + c3).body().path("centerId").isNull())
        .as("배정에 센터가 없으면 null")
        .isTrue();
    Res byCenter = get(agent, "/rider-complaints?riderId=" + rider + "&centerId=" + mokpo);
    assertThat(byCenter.status()).isEqualTo(200);
    assertThat(byCenter.body().path("items").findValuesAsString("complaintId")).containsExactly(c1);
    assertThat(byCenter.body().path("items").get(0).path("centerId").asString()).isEqualTo(mokpo);
    assertThat(
            get(agent, "/rider-complaints?riderId=" + rider)
                .body()
                .path("items")
                .findValuesAsString("complaintId"))
        .as("센터를 안 주면 전부")
        .containsExactly(c3, c2, c1);
    JsonNode counts =
        get(agent, "/rider-complaints/counts?riderId=" + rider + "&centerId=" + busan).body();
    java.util.Map<String, Long> byType = new java.util.HashMap<>();
    counts
        .path("counts")
        .forEach(c -> byType.put(c.path("key").asString(), c.path("count").asLong()));
    assertThat(byType).containsEntry("DAMAGE", 1L).containsEntry("REPORT", 0L);
  }

  @Test
  void listFiltersByRiderDeliveryTypeAndTime() {
    String agent = TestJwt.operator("cs-r5c", List.of("CS_AGENT"), List.of());
    String rider = id("RD");
    String d1 = id("DLV");
    String d2 = id("DLV");
    String d3 = id("DLV");
    assigned(d1, rider);
    assigned(d2, rider);
    assigned(d3, id("RD"));
    Instant before = Instant.now().minusSeconds(1);
    String c1 = record(agent, d1, "REPORT");
    String c2 = record(agent, d2, "DAMAGE");
    String c3 = record(agent, d3, "REPORT");

    Res byRider = get(agent, "/rider-complaints?riderId=" + rider);
    assertThat(byRider.status()).isEqualTo(200);
    assertThat(byRider.body().path("items").findValuesAsString("complaintId"))
        .containsExactly(c2, c1); // 최근 순
    assertThat(
            get(agent, "/rider-complaints?riderId=" + rider + "&complaintType=DAMAGE")
                .body()
                .path("items")
                .findValuesAsString("complaintId"))
        .containsExactly(c2);
    assertThat(
            get(agent, "/rider-complaints?deliveryId=" + d3)
                .body()
                .path("items")
                .findValuesAsString("complaintId"))
        .containsExactly(c3);
    // 목록에는 메모가 없다
    assertThat(byRider.body().path("items").path(0).has("note")).isFalse();

    // 시간 창: to 가 기록 전이면 비고, from 이후면 다 나온다
    assertThat(
            get(agent, "/rider-complaints?riderId=" + rider + "&to=" + before).body().path("items"))
        .isEmpty();
    assertThat(
            get(agent, "/rider-complaints?riderId=" + rider + "&from=" + before)
                .body()
                .path("items")
                .findValuesAsString("complaintId"))
        .containsExactly(c2, c1);

    // 쪽 나누기
    Res first = get(agent, "/rider-complaints?riderId=" + rider + "&size=1");
    assertThat(first.body().path("items").findValuesAsString("complaintId")).containsExactly(c2);
    String cursor = first.body().path("page").path("nextCursor").asString();
    assertThat(cursor).isEqualTo(c2);
    Res second = get(agent, "/rider-complaints?riderId=" + rider + "&size=1&cursor=" + cursor);
    assertThat(second.body().path("items").findValuesAsString("complaintId")).containsExactly(c1);
  }

  String record(String token, String deliveryId, String type) {
    Res r =
        send(
            "POST",
            token,
            "/rider-complaints",
            Map.of("deliveryId", deliveryId, "complaintType", type),
            UUID.randomUUID().toString());
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("complaintId").asString();
  }

  /** 0.26.0(웹 숫자 띠): 목록과 같은 거르기로 종류별 수 — 모든 종류를 0 까지, 종류 거르기는 무시, capped false. */
  @Test
  void countsByTypeUseListFiltersAndListEveryType() {
    String rider = id("RD");
    String agent = TestJwt.operator("cs-cnt", List.of("CS_AGENT"), List.of());
    for (String type : List.of("DAMAGE", "DAMAGE", "REPORT")) {
      String delivery = id("DLV");
      assigned(delivery, rider);
      Res r =
          send(
              "POST",
              agent,
              "/rider-complaints",
              Map.of("deliveryId", delivery, "complaintType", type),
              UUID.randomUUID().toString());
      assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    }

    Res counts = get(agent, "/rider-complaints/counts?riderId=" + rider + "&complaintType=REPORT");

    assertThat(counts.status()).as(counts.body().toString()).isEqualTo(200);
    JsonNode rows = counts.body().path("counts");
    assertThat(rows.findValuesAsString("key")).containsExactly("REPORT", "MISDELIVERY", "DAMAGE");
    assertThat(rows.get(0).path("count").asLong()).isEqualTo(1);
    assertThat(rows.get(1).path("count").asLong()).as("0 도 빠짐없이").isZero();
    assertThat(rows.get(2).path("count").asLong()).as("종류 거르기는 무시").isEqualTo(2);
    assertThat(rows.get(0).path("capped").asBoolean(true)).isFalse();
    assertThat(counts.body().path("asOf").asString()).isNotBlank();
  }
}
