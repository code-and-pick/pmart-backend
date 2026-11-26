package com.pmart.cdc.quality;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.cdc.quality.application.DisposalService;
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
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 품질 보류·해제·폐기(0.2.0): 보류(줄마다 재고 HOLD) → 해제 요청 → 관리자 결정(작업자·본인 안 됨) → 재고 RELEASE → 폐기 요청(남은 보류만) →
 * 결정 → 실행(증빙·승인 잔량, 재고 DISPOSE) → 불명 뒤 재시도로 완료 → 보류 CLOSED. 본사 기준 이상이면 승인 번호 필요.
 * cdc-inventory·pricing· approval 은 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.messaging.outbox.delete-after-insert=false",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.quality.approval-ref.mode=VERIFY",
      "pmart.quality.retry-interval=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(
      name = "inventory",
      baseUrlProperties = "pmart.quality.cdc-inventory-base-url"),
  @ConfigureWireMock(
      name = "pricing",
      baseUrlProperties = "pmart.quality.approval-ref.pricing-base-url"),
  @ConfigureWireMock(
      name = "approval",
      baseUrlProperties = "pmart.quality.approval-ref.approval-base-url")
})
class QualityFlowIT extends PostgresKafkaContainerSupport {

  static final String CENTER = "CDC01";
  static final String WORKER = TestJwt.operator("qw-1", List.of("CDC_WORKER"), List.of(CENTER));
  static final String MANAGER = TestJwt.operator("qm-1", List.of("CDC_MANAGER"), List.of(CENTER));
  static final String MANAGER2 = TestJwt.operator("qm-2", List.of("CDC_MANAGER"), List.of(CENTER));
  static final String OTHER = TestJwt.operator("qo-9", List.of("CDC_MANAGER"), List.of("CDC02"));
  static final String SHELF = "CDC01-A-5-61-01-1-1";
  static final String POSTINGS = "/api/v1/internal/quality-postings";

  @LocalServerPort int port;

  @InjectWireMock("inventory")
  WireMockServer inventory;

  @InjectWireMock("pricing")
  WireMockServer pricing;

  @Autowired ObjectMapper mapper;
  @Autowired DisposalService disposals;
  @Autowired com.pmart.cdc.quality.application.RecallService recalls;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    inventory.resetAll();
    inventory.stubFor(
        post(urlPathEqualTo(POSTINGS))
            .willReturn(
                okJson("{\"status\":\"APPLIED\",\"resultId\":\"CMT-1\",\"appliedQty\":10}")));
    pricing.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/prices"))
            .willReturn(
                okJson(
                    "{\"items\":[{\"sku\":\"E-00000077\",\"basePrice\":1000,\"priceVersion\":1}]}")));
  }

  record Res(int status, JsonNode body) {}

  Res send(String token, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "cdc-backoffice");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  JsonNode read(String b) {
    return mapper.readTree(b == null || b.isBlank() ? "{}" : b);
  }

  static String key() {
    return UUID.randomUUID().toString();
  }

  static Map<String, Object> exact(int qty) {
    return Map.of("sku", "E-00000077", "lot", "L1", "locationCode", SHELF, "qty", qty);
  }

  @Test
  void holdReleaseDisposeFlow() {
    String base = "/cdcs/" + CENTER + "/quality-holds";
    Map<String, Object> hold =
        Map.of(
            "reason",
            "TEMPERATURE_EXCURSION",
            "sourceRef",
            "EXC-1",
            "lines",
            List.of(Map.of("sku", "E-00000077", "lot", "L1")));
    // 다른 센터는 403
    assertThat(send(OTHER, base, key(), hold).status()).isEqualTo(403);
    // 1) 보류 — 재고 HOLD 10
    String hk = key();
    Res h = send(WORKER, base, hk, hold);
    assertThat(h.status()).as(h.body().toString()).isEqualTo(201);
    String holdId = h.body().path("holdId").asString();
    assertThat(h.body().path("state").asString()).isEqualTo("APPLIED");
    assertThat(h.body().path("lines").get(0).path("heldQty").asInt()).isEqualTo(10);
    assertThat(send(WORKER, base, hk, hold).body().path("holdId").asString()).isEqualTo(holdId);
    inventory.verify(
        1,
        postRequestedFor(urlPathEqualTo(POSTINGS))
            .withHeader("Idempotency-Key", equalTo("cdcq-hold-" + holdId + "-1"))
            .withRequestBody(matchingJsonPath("$.action", equalTo("HOLD"))));

    // 2) 해제 요청 — 남은 보류 초과 422, 4 요청
    String rel = base + "/" + holdId + "/release-requests";
    Res over = send(WORKER, rel, key(), Map.of("lines", List.of(exact(11))));
    assertThat(over.status()).isEqualTo(422);
    assertThat(over.body().path("code").asString()).isEqualTo("QTY_EXCEEDS_HELD");
    Res r = send(WORKER, rel, key(), Map.of("note", "재검사 합격", "lines", List.of(exact(4))));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    String releaseId = r.body().path("releaseId").asString();
    assertThat(r.body().path("state").asString()).isEqualTo("REQUESTED");
    String dec = rel + "/" + releaseId + "/decisions";
    // 작업자는 결정 못 함(403), 관리자 승인 → 재고 RELEASE
    assertThat(send(WORKER, dec, key(), Map.of("decision", "APPROVE")).status()).isEqualTo(403);
    Res d = send(MANAGER, dec, key(), Map.of("decision", "APPROVE", "reason", "재검사"));
    assertThat(d.status()).as(d.body().toString()).isEqualTo(200);
    assertThat(d.body().path("state").asString()).isEqualTo("APPLIED");
    inventory.verify(
        postRequestedFor(urlPathEqualTo(POSTINGS))
            .withHeader("Idempotency-Key", equalTo("cdcq-rel-" + releaseId))
            .withRequestBody(matchingJsonPath("$.action", equalTo("RELEASE")))
            .withRequestBody(matchingJsonPath("$.approvalRef", equalTo("CENTER:" + releaseId))));
    JsonNode g = get(WORKER, base + "/" + holdId).body();
    assertThat(g.path("state").asString()).isEqualTo("PARTIALLY_RELEASED");
    assertThat(g.path("lines").get(0).path("releasedQty").asInt()).isEqualTo(4);
    // 본인 요청은 본인이 결정 못 함
    Res mine = send(MANAGER, rel, key(), Map.of("lines", List.of(exact(1))));
    Res self =
        send(
            MANAGER,
            rel + "/" + mine.body().path("releaseId").asString() + "/decisions",
            key(),
            Map.of("decision", "REJECT"));
    assertThat(self.status()).isEqualTo(422);
    assertThat(self.body().path("code").asString()).isEqualTo("SELF_APPROVAL");
    Res declined =
        send(
            MANAGER2,
            rel + "/" + mine.body().path("releaseId").asString() + "/decisions",
            key(),
            Map.of("decision", "REJECT", "reason", "아직"));
    assertThat(declined.body().path("state").asString()).isEqualTo("DECLINED");

    // 3) 폐기 요청 — 남은 6 만, 7 은 422
    String disp = "/cdcs/" + CENTER + "/disposal-approvals";
    Res d7 =
        send(
            WORKER,
            disp,
            key(),
            Map.of("holdId", holdId, "reason", "TEMPERATURE", "lines", List.of(exact(7))));
    assertThat(d7.status()).isEqualTo(422);
    Res dr =
        send(
            WORKER,
            disp,
            key(),
            Map.of("holdId", holdId, "reason", "TEMPERATURE", "lines", List.of(exact(6))));
    assertThat(dr.status()).as(dr.body().toString()).isEqualTo(201);
    String did = dr.body().path("disposalApprovalId").asString();
    Res da = send(MANAGER, disp + "/" + did + "/decisions", key(), Map.of("decision", "APPROVE"));
    assertThat(da.body().path("state").asString()).isEqualTo("APPROVED");
    // 실행 — 3, 승인 잔량 초과 422, 불명이면 UNKNOWN_RESULT → 재시도로 완료
    String ex = disp + "/" + did + "/executions";
    Res e1 = send(WORKER, ex, key(), Map.of("evidenceRef", "PHOTO-1", "lines", List.of(exact(3))));
    assertThat(e1.status()).as(e1.body().toString()).isEqualTo(200);
    assertThat(e1.body().path("state").asString()).isEqualTo("PARTIALLY_EXECUTED");
    assertThat(e1.body().path("lines").get(0).path("executedQty").asInt()).isEqualTo(3);
    inventory.verify(
        postRequestedFor(urlPathEqualTo(POSTINGS))
            .withRequestBody(matchingJsonPath("$.action", equalTo("DISPOSE")))
            .withRequestBody(matchingJsonPath("$.evidenceRef", equalTo("PHOTO-1"))));
    Res e4 = send(WORKER, ex, key(), Map.of("evidenceRef", "PHOTO-2", "lines", List.of(exact(4))));
    assertThat(e4.status()).isEqualTo(422);
    assertThat(e4.body().path("code").asString()).isEqualTo("QTY_EXCEEDS_APPROVED");
    inventory.stubFor(
        post(urlPathEqualTo(POSTINGS)).atPriority(1).willReturn(aResponse().withStatus(503)));
    Res e3 = send(WORKER, ex, key(), Map.of("evidenceRef", "PHOTO-3", "lines", List.of(exact(3))));
    assertThat(e3.body().path("executions").get(1).path("state").asString())
        .isEqualTo("UNKNOWN_RESULT");
    inventory.resetAll();
    inventory.stubFor(
        post(urlPathEqualTo(POSTINGS))
            .willReturn(
                okJson("{\"status\":\"APPLIED\",\"resultId\":\"CMT-2\",\"appliedQty\":3}")));
    disposals.retry();
    JsonNode done = get(WORKER, disp + "/" + did).body();
    assertThat(done.path("state").asString()).isEqualTo("COMPLETED");
    JsonNode closed = get(WORKER, base + "/" + holdId).body();
    assertThat(closed.path("state").asString()).isEqualTo("CLOSED");
    assertThat(closed.path("lines").get(0).path("disposedQty").asInt()).isEqualTo(6);
    assertThat(get(WORKER, base + "?state=CLOSED").body().path("items").toString())
        .contains(holdId);
  }

  @Test
  void releaseOverHqThresholdNeedsApprovalRef() {
    pricing.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/prices"))
            .atPriority(1)
            .willReturn(
                okJson(
                    "{\"items\":[{\"sku\":\"E-00000077\",\"basePrice\":50000,\"priceVersion\":1}]}")));
    String base = "/cdcs/" + CENTER + "/quality-holds";
    Res h =
        send(
            WORKER,
            base,
            key(),
            Map.of("reason", "MANUAL", "lines", List.of(Map.of("sku", "E-00000077", "lot", "L1"))));
    String holdId = h.body().path("holdId").asString();
    Res r =
        send(
            WORKER,
            base + "/" + holdId + "/release-requests",
            key(),
            Map.of("lines", List.of(exact(3))));
    Res need =
        send(
            MANAGER,
            base
                + "/"
                + holdId
                + "/release-requests/"
                + r.body().path("releaseId").asString()
                + "/decisions",
            key(),
            Map.of("decision", "APPROVE"));
    assertThat(need.status()).as(need.body().toString()).isEqualTo(422);
    assertThat(need.body().toString())
        .contains("APPROVAL_REQUIRED")
        .contains("CDC_STOCK_ADJUSTMENT");
  }

  /**
   * 리콜(0.3.0): 승인 번호 없으면 422 → 발령(RecallIssued) → LOT 추적의 현물 CDC 두 곳에 보류(재고 HOLD recall) → 출발 추적 ·
   * 추적 불명은 TRACING → 재시도로 ISSUED.
   */
  @Test
  void recallHoldsEveryCdcWithStock() {
    String trace =
        "{\"sku\":\"E-00000088\",\"lot\":\"RL1\",\"stock\":["
            + "{\"cdcId\":\"CDC01\",\"locationCode\":\"CDC01-A-5-61-01-1-1\",\"qualityState\":\"RELEASED\",\"onHand\":5,\"reserved\":0},"
            + "{\"cdcId\":\"CDC01\",\"locationCode\":\"CDC01-A-5-91-01-0-1\",\"qualityState\":\"RELEASED\",\"onHand\":2,\"reserved\":2},"
            + "{\"cdcId\":\"CDC02\",\"locationCode\":\"CDC02-A-5-61-01-1-1\",\"qualityState\":\"RELEASED\",\"onHand\":3,\"reserved\":0}],"
            + "\"transits\":[{\"cdcId\":\"CDC01\",\"trfId\":\"TRF-9\",\"departureId\":\"DEP-9\",\"mfcId\":\"MFC-BJG-01\","
            + "\"departedAt\":\"2026-11-26T01:00:00Z\",\"state\":\"TRANSIT_OPEN\",\"q\":4,\"received\":0,\"loss\":0,\"unreconciled\":4}]}";
    inventory.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/lot-traces")).willReturn(okJson(trace)));
    Map<String, Object> body =
        Map.of("sku", "E-00000088", "lots", List.of("RL1"), "reason", "SUPPLIER_NOTICE");
    assertThat(send(WORKER, "/cdc-recalls", key(), body).status()).isEqualTo(400); // 칸 없음
    Map<String, Object> blank = new java.util.LinkedHashMap<>(body);
    blank.put("approvalRef", " ");
    Res no = send(WORKER, "/cdc-recalls", key(), blank);
    assertThat(no.status()).isEqualTo(422);
    assertThat(no.body().path("code").asString()).isEqualTo("APPROVAL_REQUIRED");
    Map<String, Object> ok = new java.util.LinkedHashMap<>(body);
    ok.put("approvalRef", "APV-HQ-RCL-1");
    Res r = send(WORKER, "/cdc-recalls", key(), ok);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    String rid = r.body().path("recallId").asString();
    assertThat(r.body().path("state").asString()).isEqualTo("ISSUED");
    assertThat(r.body().path("centers").findValuesAsString("cdcId"))
        .containsExactlyInAnyOrder("CDC01", "CDC02");
    assertThat(r.body().path("departures").get(0).path("mfcId").asString()).isEqualTo("MFC-BJG-01");
    assertThat(r.body().path("traceFreshness").asString()).isEqualTo("CURRENT");
    inventory.verify(
        2,
        postRequestedFor(urlPathEqualTo(POSTINGS))
            .withRequestBody(matchingJsonPath("$.action", equalTo("HOLD")))
            .withRequestBody(matchingJsonPath("$.recall", equalTo("true")))
            .withRequestBody(matchingJsonPath("$.reason", equalTo("RECALL"))));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE type = 'RecallIssued' AND aggregateid = ?",
                Integer.class,
                rid))
        .isEqualTo(1);
    // 추적이 안 되면 TRACING → 재시도로 ISSUED
    inventory.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/lot-traces"))
            .atPriority(1)
            .willReturn(aResponse().withStatus(503)));
    Map<String, Object> ok2 = new java.util.LinkedHashMap<>(ok);
    ok2.put("lots", List.of("RL2"));
    Res t = send(WORKER, "/cdc-recalls", key(), ok2);
    assertThat(t.body().path("state").asString()).isEqualTo("TRACING");
    inventory.resetAll();
    inventory.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/lot-traces")).willReturn(okJson(trace)));
    inventory.stubFor(
        post(urlPathEqualTo(POSTINGS))
            .willReturn(
                okJson("{\"status\":\"APPLIED\",\"resultId\":\"CMT-3\",\"appliedQty\":7}")));
    recalls.retry();
    assertThat(
            get(WORKER, "/cdc-recalls/" + t.body().path("recallId").asString())
                .body()
                .path("state")
                .asString())
        .isEqualTo("ISSUED");
  }

  /** 운영 제한(0.4.0): 사유별 거는·푸는 사람 → 내부 확인(활동·온도대·SKU 맞을 때만) → 풀면 더는 안 막음 · 사건. */
  @Test
  void operationRestrictionsGateAndLift() {
    String base = "/cdcs/" + CENTER + "/operation-restrictions";
    Map<String, Object> cong =
        Map.of(
            "activities",
            List.of("ALLOCATION"),
            "temperatureZone",
            "CHILLED",
            "reason",
            "CONGESTION");
    // 혼잡은 관리자만
    assertThat(send(WORKER, base, key(), cong).status()).isEqualTo(403);
    Res c = send(MANAGER, base, key(), cong);
    assertThat(c.status()).as(c.body().toString()).isEqualTo(201);
    String cid = c.body().path("restrictionId").asString();
    // 본사 부과는 승인 번호 없으면 422
    Map<String, Object> hq = new java.util.LinkedHashMap<>();
    hq.put("activities", List.of("DEPARTURE"));
    hq.put("skus", List.of("E-00000099"));
    hq.put("reason", "HQ");
    assertThat(send(WORKER, base, key(), hq).status()).isEqualTo(422);
    hq.put("approvalRef", "APV-HQ-OR-1");
    Res h = send(WORKER, base, key(), hq);
    assertThat(h.status()).as(h.body().toString()).isEqualTo(201);
    String hid = h.body().path("restrictionId").asString();
    // 확인: 냉장 할당은 막힘, 상온 할당·다른 SKU 출발은 안 막힘, 그 SKU 출발은 막힘
    String chk = "/internal/operation-restriction-checks";
    String svc = TestJwt.service("cdc-inventory-service");
    assertThat(
            send(
                    svc,
                    chk,
                    key(),
                    Map.of(
                        "cdcId",
                        CENTER,
                        "activity",
                        "ALLOCATION",
                        "items",
                        List.of(Map.of("sku", "E-00000001", "temperatureZone", "CHILLED"))))
                .body()
                .path("restricted")
                .asBoolean())
        .isTrue();
    assertThat(
            send(
                    svc,
                    chk,
                    key(),
                    Map.of(
                        "cdcId",
                        CENTER,
                        "activity",
                        "ALLOCATION",
                        "items",
                        List.of(Map.of("sku", "E-00000001", "temperatureZone", "AMBIENT"))))
                .body()
                .path("restricted")
                .asBoolean())
        .isFalse();
    assertThat(
            send(
                    svc,
                    chk,
                    key(),
                    Map.of(
                        "cdcId",
                        CENTER,
                        "activity",
                        "DEPARTURE",
                        "items",
                        List.of(Map.of("sku", "E-00000002"))))
                .body()
                .path("restricted")
                .asBoolean())
        .isFalse();
    JsonNode dep =
        send(
                svc,
                chk,
                key(),
                Map.of(
                    "cdcId",
                    CENTER,
                    "activity",
                    "DEPARTURE",
                    "items",
                    List.of(Map.of("sku", "E-00000099"))))
            .body();
    assertThat(dep.path("restricted").asBoolean()).isTrue();
    assertThat(dep.path("matches").get(0).path("restrictionId").asString()).isEqualTo(hid);
    // 풀기: 본사 부과는 관리자 + 승인 번호, 혼잡은 관리자만
    assertThat(send(MANAGER, base + "/" + hid + "/lifts", key(), Map.of()).status()).isEqualTo(422);
    Res lh =
        send(MANAGER, base + "/" + hid + "/lifts", key(), Map.of("approvalRef", "APV-HQ-OR-2"));
    assertThat(lh.status()).as(lh.body().toString()).isEqualTo(200);
    assertThat(lh.body().path("state").asString()).isEqualTo("LIFTED");
    assertThat(send(MANAGER, base + "/" + cid + "/lifts", key(), Map.of("note", "혼잡 풀림")).status())
        .isEqualTo(200);
    assertThat(
            send(
                    svc,
                    chk,
                    key(),
                    Map.of(
                        "cdcId",
                        CENTER,
                        "activity",
                        "DEPARTURE",
                        "items",
                        List.of(Map.of("sku", "E-00000099"))))
                .body()
                .path("restricted")
                .asBoolean())
        .isFalse();
    assertThat(get(MANAGER, base + "?state=LIFTED").body().path("items").toString())
        .contains(hid, cid);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type IN"
                    + " ('OperationRestrictionApplied', 'OperationRestrictionLifted')",
                Integer.class,
                hid))
        .isEqualTo(2);
  }
}
