package com.pmart.cases;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import java.util.function.Predicate;
import java.util.function.Supplier;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * R-16(ADR-0086, 사용자 2026-11-24): 고객 NOT_RECEIVED 문의 + 전달 완료 고객 배송 → 오배송 접수 건 자동·대화 선택 안내 → 고객·상담원이
 * 고르거나 5시간 뒤 자동 REFUND → MisdeliveryConfirmed(fault RIDER, source CUSTOMER_INQUIRY). 배송 판단은 배송
 * 사본(실제 Kafka).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.cases.order-service-base-url=${order.url}",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.cases.misdelivery-auto-initial-delay=PT1H",
      "pmart.test.context=misdelivery-inquiry"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "order", baseUrlProperties = "order.url")})
class MisdeliveryInquiryIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired com.pmart.cases.application.MisdeliveryInquiryService misdelivery;

  @InjectWireMock("order")
  WireMockServer order;

  record Res(int status, JsonNode body) {}

  Res send(String method, String token, String path, @Nullable Object body) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
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

  static <T> T await(Supplier<T> s, Predicate<T> ok) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    T v = s.get();
    while (!ok.test(v) && Instant.now().isBefore(until)) {
      Thread.sleep(200);
      v = s.get();
    }
    return v;
  }

  void publish(String type, String deliveryId, Map<String, Object> payload) {
    kafka
        .send(
            "delivery.delivery.v1",
            deliveryId,
            EventEnvelopeFixtures.envelope(type)
                .from(
                    "DeliveryCreated".equals(type)
                        ? "delivery-dispatch-service"
                        : "delivery-execution-service")
                .aggregate("Delivery", deliveryId, "DeliveryCreated".equals(type) ? 1 : 5)
                .payload(payload)
                .buildJson())
        .join();
  }

  /** 주문 + 배송(완료면 DeliveryCompleted 도) — 사본이 찰 때까지 기다린다. */
  String delivery(String orderId, String type, boolean completed) throws Exception {
    String d = "DLV-M-" + UUID.randomUUID().toString().substring(0, 8);
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/" + orderId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"orderId\":\"" + orderId + "\",\"status\":\"COMPLETED\"}")));
    Map<String, Object> created = new LinkedHashMap<>();
    created.put("deliveryId", d);
    created.put("orderId", orderId);
    created.put("shipmentId", "SHP-" + d);
    created.put("type", type);
    created.put("mfcId", "MFC-M1");
    created.put("dropAddressRef", "addr:1");
    publish("DeliveryCreated", d, created);
    if (completed) {
      Map<String, Object> done = new LinkedHashMap<>();
      done.put("deliveryId", d);
      done.put("orderId", orderId);
      done.put("riderId", "RD-M1");
      done.put("lines", List.of(Map.of("orderLineId", "L1", "qty", 1)));
      done.put("method", "CONTACTLESS");
      done.put("evidenceRef", Map.of("kind", "PHOTO", "ref", "ph-1"));
      done.put("deliveredAt", Instant.now().minusSeconds(600).toString());
      publish("DeliveryCompleted", d, done);
    }
    await(
        () ->
            jdbc.queryForList(
                "SELECT completed_at FROM case_delivery_copy WHERE delivery_id = ?"
                    + (completed
                        ? " AND completed_at IS NOT NULL AND delivery_type IS NOT NULL"
                        : ""),
                d),
        r -> !r.isEmpty());
    return d;
  }

  Res notReceived(String token, String orderId, @Nullable String deliveryId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("category", "DELIVERY");
    body.put("orderId", orderId);
    body.put("body", "상품을 받지 못했어요");
    body.put("deliveryIssue", "NOT_RECEIVED");
    if (deliveryId != null) {
      body.put("deliveryId", deliveryId);
    }
    return send("POST", token, "/inquiries", body);
  }

  JsonNode confirmed(String caseId) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'MisdeliveryConfirmed' AND aggregateid = ?",
            String.class,
            caseId);
    assertThat(rows).as("MisdeliveryConfirmed 하나").hasSize(1);
    return mapper.readTree(rows.get(0)).path("payload");
  }

  @Test
  void customerChoosesInChatAfterDeliveredOrderAndConfirmedOnce() throws Exception {
    String me = "CU-" + UUID.randomUUID().toString().substring(0, 8);
    String meTok = TestJwt.customer(me);
    String orderId = "ORD-M-" + UUID.randomUUID().toString().substring(0, 8);
    String d = delivery(orderId, "FORWARD", true);

    // 앱이 주문만 보내도 서버가 전달 완료 고객 배송을 고른다(R-16 Q2)
    Res created = notReceived(meTok, orderId, null);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String id = created.body().path("inquiryId").asString();
    JsonNode md = created.body().path("misdelivery");
    assertThat(md.path("state").asString()).isEqualTo("AWAITING_CHOICE");
    assertThat(md.path("deliveryId").asString()).isEqualTo(d);
    assertThat(md.path("options").toString()).contains("RESHIP", "REFUND");
    Instant deadline = Instant.parse(md.path("deadlineAt").asString());
    assertThat(Duration.between(Instant.now(), deadline).toMinutes()).isBetween(290L, 301L);
    assertThat(created.body().path("deliveryId").asString()).isEqualTo(d);
    assertThat(created.body().path("replies")).as("상세 replies 는 답변·라이더만").isEmpty();
    String caseId = md.path("caseId").asString();
    Map<String, Object> c =
        jdbc.queryForMap(
            "SELECT case_type, subject_ref, refs::text AS refs FROM case_record WHERE case_id = ?",
            caseId);
    assertThat(c.get("case_type")).isEqualTo("MISDELIVERY");
    assertThat(c.get("subject_ref")).isEqualTo(d);
    assertThat((String) c.get("refs")).contains("CUSTOMER_INQUIRY", id, "RD-M1");

    // 대화: 시스템 선택 안내
    Res msgs = send("GET", meTok, "/inquiries/" + id + "/messages", null);
    assertThat(msgs.status()).as(msgs.body().toString()).isEqualTo(200);
    JsonNode first = msgs.body().path("items").path(0);
    assertThat(first.path("senderType").asString()).isEqualTo("SYSTEM");
    assertThat(first.path("systemKind").asString()).isEqualTo("MISDELIVERY_CHOICE_REQUEST");
    assertThat(first.path("senderId").isString()).isFalse();

    // 목록 줄
    Res list = send("GET", meTok, "/inquiries", null);
    JsonNode row = null;
    for (JsonNode i : list.body().path("items")) {
      if (id.equals(i.path("inquiryId").asString())) {
        row = i;
      }
    }
    assertThat(row).isNotNull();
    assertThat(row.path("misdeliveryState").asString()).isEqualTo("AWAITING_CHOICE");
    assertThat(row.path("misdeliveryDeadlineAt").isString()).isTrue();

    // 남은 고를 수 없다, 고객이 고른다(한 번만)
    String path = "/inquiries/" + id + "/misdelivery-choices";
    assertThat(
            send("POST", TestJwt.customer("CU-x"), path, Map.of("resolution", "REFUND")).status())
        .isEqualTo(404);
    assertThat(send("POST", meTok, path, Map.of("resolution", "LATER")).status()).isEqualTo(400);
    Res chose = send("POST", meTok, path, Map.of("resolution", "RESHIP"));
    assertThat(chose.status()).as(chose.body().toString()).isEqualTo(200);
    assertThat(chose.body().path("misdelivery").path("state").asString()).isEqualTo("CHOSEN");
    assertThat(chose.body().path("misdelivery").path("resolution").asString()).isEqualTo("RESHIP");
    assertThat(chose.body().path("misdelivery").path("chosenByType").asString())
        .isEqualTo("CUSTOMER");
    assertThat(chose.body().path("misdelivery").path("autoResolved").asBoolean()).isFalse();
    Res again = send("POST", meTok, path, Map.of("resolution", "REFUND"));
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.body().path("code").asString()).isEqualTo("MISDELIVERY_ALREADY_CHOSEN");

    JsonNode ev = confirmed(caseId);
    assertThat(ev.path("fault").asString()).isEqualTo("RIDER");
    assertThat(ev.path("resolution").asString()).isEqualTo("RESHIP");
    assertThat(ev.path("source").asString()).isEqualTo("CUSTOMER_INQUIRY");
    assertThat(ev.path("inquiryId").asString()).isEqualTo(id);
    assertThat(ev.path("orderId").asString()).isEqualTo(orderId);
    assertThat(ev.path("deliveryId").asString()).isEqualTo(d);
    assertThat(ev.path("riderId").asString()).isEqualTo("RD-M1");
    assertThat(ev.path("autoResolved").asBoolean()).isFalse();
    assertThat(ev.path("confirmedBy").asString()).isEqualTo(me);

    // 고객이 이어 쓰기 — 상태 RECEIVED, 대화에 CUSTOMER·고른 결과 안내
    Res mine = send("POST", meTok, "/inquiries/" + id + "/messages", Map.of("body", "언제 와요?"));
    assertThat(mine.status()).as(mine.body().toString()).isEqualTo(201);
    assertThat(mine.body().path("senderType").asString()).isEqualTo("CUSTOMER");
    JsonNode all = send("GET", meTok, "/inquiries/" + id + "/messages", null).body().path("items");
    assertThat(all.toString()).contains("MISDELIVERY_CHOSEN", "CUSTOMER");
    assertThat(send("GET", meTok, "/inquiries/" + id, null).body().path("replies")).isEmpty();
  }

  @Test
  void autoRefundAfterDeadlineStaffMayChooseAndUndeliveredStaysAgentInquiry() throws Exception {
    String me = "CU-" + UUID.randomUUID().toString().substring(0, 8);
    String meTok = TestJwt.customer(me);
    String agent = TestJwt.operator("cs-m1", List.of("CS_AGENT"), List.of());

    // ① 기한이 지나면 자동 REFUND
    String o1 = "ORD-M-" + UUID.randomUUID().toString().substring(0, 8);
    String d1 = delivery(o1, "RESHIPMENT", true);
    Res a = notReceived(meTok, o1, d1);
    String id1 = a.body().path("inquiryId").asString();
    String case1 = a.body().path("misdelivery").path("caseId").asString();
    jdbc.update(
        "UPDATE inquiry_misdelivery SET deadline_at = now() - interval '1 minute' WHERE inquiry_id = ?",
        id1);
    Res late =
        send(
            "POST",
            meTok,
            "/inquiries/" + id1 + "/misdelivery-choices",
            Map.of("resolution", "RESHIP"));
    assertThat(late.status()).isEqualTo(409);
    assertThat(late.body().path("code").asString()).isEqualTo("MISDELIVERY_CHOICE_EXPIRED");
    assertThat(misdelivery.autoResolveDue()).isGreaterThanOrEqualTo(1);
    JsonNode auto = send("GET", meTok, "/inquiries/" + id1, null).body().path("misdelivery");
    assertThat(auto.path("resolution").asString()).isEqualTo("REFUND");
    assertThat(auto.path("chosenByType").asString()).isEqualTo("AUTO");
    assertThat(auto.path("autoResolved").asBoolean()).isTrue();
    JsonNode ev = confirmed(case1);
    assertThat(ev.path("autoResolved").asBoolean()).isTrue();
    assertThat(ev.path("resolution").asString()).isEqualTo("REFUND");
    assertThat(ev.path("confirmedBy").asString()).isEqualTo("case-service");

    // ② 상담원이 대신 — 사유 필수, 사유는 직원에게만
    String o2 = "ORD-M-" + UUID.randomUUID().toString().substring(0, 8);
    String d2 = delivery(o2, "FORWARD", true);
    String id2 = notReceived(meTok, o2, d2).body().path("inquiryId").asString();
    String p2 = "/inquiries/" + id2 + "/misdelivery-choices";
    assertThat(send("POST", agent, p2, Map.of("resolution", "REFUND")).status()).isEqualTo(400);
    Res staff = send("POST", agent, p2, Map.of("resolution", "REFUND", "reason", "전화로 환불 원하심"));
    assertThat(staff.status()).as(staff.body().toString()).isEqualTo(200);
    assertThat(staff.body().path("misdelivery").path("chosenByType").asString()).isEqualTo("STAFF");
    assertThat(staff.body().path("misdelivery").path("chosenBy").asString()).isEqualTo("cs-m1");
    assertThat(staff.body().path("misdelivery").path("chosenReason").asString())
        .isEqualTo("전화로 환불 원하심");
    JsonNode forCustomer = send("GET", meTok, "/inquiries/" + id2, null).body().path("misdelivery");
    assertThat(forCustomer.path("chosenReason").isString()).as("사유는 직원에게만").isFalse();
    assertThat(forCustomer.path("chosenBy").isString()).isFalse();

    // ③ 전달 전 배송 — 지금처럼 상담원 문의(오배송 건 없음)
    String o3 = "ORD-M-" + UUID.randomUUID().toString().substring(0, 8);
    String d3 = delivery(o3, "FORWARD", false);
    Res plain = notReceived(meTok, o3, d3);
    assertThat(plain.status()).isEqualTo(201);
    assertThat(plain.body().path("misdelivery").isObject()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM case_record WHERE case_type = 'MISDELIVERY' AND subject_ref = ?",
                Integer.class,
                d3))
        .isZero();
  }
}
