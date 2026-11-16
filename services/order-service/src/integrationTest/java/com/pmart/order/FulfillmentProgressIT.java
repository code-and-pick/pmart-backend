package com.pmart.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwtConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * O-1(2026-11-16): MFC 포장 인계(PackageHandedOver) → 인계 수량·IN_DELIVERY, 배송 전달 완료(DeliveryCompleted) →
 * 전달 수량·COMPLETED·OrderCompleted. 같은 업무 키 재발행은 한 번, 역순(전달 먼저)도 받는다, 수량이 맞지 않으면 반영하지 않고 MISMATCH.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=progress-test",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import(TestJwtConfiguration.class)
class FulfillmentProgressIT extends PostgresKafkaContainerSupport {

  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @org.springframework.boot.test.web.server.LocalServerPort int port;

  /**
   * 오배송 재발송(2026-11-16, ADR-0052 결정 4·5): 다 전달된 주문에 RESHIPMENT → 미이행·재발송 +q(상태 그대로), 재고 없는 품목은 이어서
   * SHORTAGE(취소 — 환불 근거), 재발송 상품의 인계·전달은 대사 불일치 없이 반영되고 보낼 수량을 넘으면 MISMATCH.
   */
  @Test
  void reshipmentReopensDeliverableAndShortageCancelsOnlyMissingLine() throws Exception {
    String orderId = order("PREPARING", 3, 2);
    String deliveryId = "DLV-" + UUID.randomUUID();
    handover(
        orderId,
        "HO-" + UUID.randomUUID(),
        deliveryId,
        List.of(line(orderId + "-L1", 3), line(orderId + "-L2", 2)));
    delivered(orderId, deliveryId, List.of(line(orderId + "-L1", 3), line(orderId + "-L2", 2)));
    await(() -> "COMPLETED".equals(status(orderId)));

    String caseId = "CASE-" + UUID.randomUUID();
    var r =
        change(
            orderId,
            "rs-" + caseId,
            Map.of(
                "reason",
                "RESHIPMENT",
                "sourceRef",
                caseId,
                "changes",
                List.of(line2(orderId + "-L1", 3), line2(orderId + "-L2", 2))));
    assertThat(r.getStatusCode().value()).isEqualTo(200);
    assertThat(qty(orderId + "-L1", "unfulfilled_qty")).isEqualTo(3);
    assertThat(qty(orderId + "-L1", "reshipped_qty")).isEqualTo(3);
    assertThat(status(orderId)).isEqualTo("COMPLETED"); // 뒤로 가지 않음
    // 같은 키 재생, 한도 넘음 409
    assertThat(
            change(
                    orderId,
                    "rs-" + caseId,
                    Map.of(
                        "reason",
                        "RESHIPMENT",
                        "sourceRef",
                        caseId,
                        "changes",
                        List.of(line2(orderId + "-L1", 3), line2(orderId + "-L2", 2))))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(qty(orderId + "-L1", "reshipped_qty")).isEqualTo(3);
    assertThat(
            change(
                    orderId,
                    "rs2-" + caseId,
                    Map.of("reason", "RESHIPMENT", "changes", List.of(line2(orderId + "-L1", 1))))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    // L2 재고 없음 → SHORTAGE(재발송 안 함 — 환불 근거)
    assertThat(
            change(
                    orderId,
                    "sh-" + caseId,
                    Map.of(
                        "reason",
                        "SHORTAGE",
                        "sourceRef",
                        caseId,
                        "changes",
                        List.of(line2(orderId + "-L2", 2))))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(qty(orderId + "-L2", "cancelled_before_ship_qty")).isEqualTo(2);
    assertThat(status(orderId)).isEqualTo("COMPLETED");

    // 재발송 상품 인계·전달 — 대사 불일치 없이
    String reDelivery = "DLV-" + UUID.randomUUID();
    handover(orderId, "HO-" + UUID.randomUUID(), reDelivery, List.of(line(orderId + "-L1", 3)));
    delivered(orderId, reDelivery, List.of(line(orderId + "-L1", 3)));
    await(() -> qty(orderId + "-L1", "delivered_qty") == 6);
    assertThat(qty(orderId + "-L1", "unfulfilled_qty")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM order_fulfillment_fact WHERE order_id = ? AND state ="
                    + " 'MISMATCH'",
                Integer.class,
                orderId))
        .isZero();
    assertThat(status(orderId)).isEqualTo("COMPLETED");

    // 오배송 다시 배송(ADR-0065) 완료는 수량에 다시 세지 않는다 — MISMATCH 도 남기지 않는다
    String redelivery = "DLV-" + UUID.randomUUID();
    deliveredWithPurpose(
        orderId, redelivery, List.of(line(orderId + "-L1", 3)), "MISDELIVERY_REDELIVERY");
    Thread.sleep(2000);
    assertThat(qty(orderId + "-L1", "delivered_qty")).isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM order_fulfillment_fact WHERE source_ref = ?",
                Integer.class,
                redelivery))
        .isZero();
  }

  private void deliveredWithPurpose(
      String orderId, String deliveryId, List<Map<String, Object>> lines, String purpose)
      throws Exception {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", deliveryId);
    p.put("orderId", orderId);
    p.put("riderId", "RD-1");
    p.put("lines", lines);
    p.put("method", "CONTACTLESS");
    p.put("purpose", purpose);
    p.put("deliveredAt", Instant.now().toString());
    kafka
        .send(
            "delivery.delivery.v1",
            deliveryId,
            com.pmart.common.test.EventEnvelopeFixtures.envelope("DeliveryCompleted")
                .from("delivery-execution-service")
                .aggregate("Delivery", deliveryId, 5)
                .payload(p)
                .buildJson())
        .get();
  }

  private org.springframework.http.ResponseEntity<String> change(
      String orderId, String key, Map<String, Object> body) {
    return org.springframework.web.client.RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api/v1")
        .defaultStatusHandler(org.springframework.http.HttpStatusCode::isError, (q, s) -> {})
        .build()
        .post()
        .uri("/internal/orders/{id}/line-quantity-changes", orderId)
        .headers(
            h -> {
              h.setBearerAuth(com.pmart.common.test.TestJwt.service("order-orchestration-service"));
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "order-orchestration-service");
            })
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private static Map<String, Object> line2(String orderLineId, int qty) {
    return Map.of("orderLineId", orderLineId, "qty", qty);
  }

  @Test
  void handoverThenDeliveryCompletesOrderOnce() throws Exception {
    String orderId = order("PREPARING", 3, 2);
    String handoverId = "HO-" + UUID.randomUUID();
    String deliveryId = "DLV-" + UUID.randomUUID();

    // 인계: L1 은 LOT 두 개로 나눠 옴. 같은 handoverId 를 사건 ID 만 바꿔 다시 보내도 한 번
    for (int i = 0; i < 2; i++) {
      handover(
          orderId,
          handoverId,
          deliveryId,
          List.of(line(orderId + "-L1", 2), line(orderId + "-L1", 1), line(orderId + "-L2", 2)));
    }
    await(() -> "IN_DELIVERY".equals(status(orderId)));
    Thread.sleep(1500);
    assertThat(qty(orderId + "-L1", "handed_over_qty")).isEqualTo(3);
    assertThat(qty(orderId + "-L1", "unfulfilled_qty")).isZero();
    assertThat(qty(orderId + "-L2", "handed_over_qty")).isEqualTo(2);
    assertThat(outbox(orderId, "OrderLineQuantityChanged")).isEqualTo(2);

    // 전달 완료(두 번 와도 한 번) → COMPLETED, OrderCompleted 한 건
    for (int i = 0; i < 2; i++) {
      delivered(orderId, deliveryId, List.of(line(orderId + "-L1", 3), line(orderId + "-L2", 2)));
    }
    await(() -> "COMPLETED".equals(status(orderId)));
    Thread.sleep(1500);
    assertThat(qty(orderId + "-L1", "delivered_qty")).isEqualTo(3);
    assertThat(qty(orderId + "-L2", "delivered_qty")).isEqualTo(2);
    assertThat(outbox(orderId, "OrderCompleted")).isEqualTo(1);
    assertThat(outbox(orderId, "OrderPartiallyDelivered")).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'OrderCompleted' AND aggregateid = ?",
                String.class,
                orderId))
        .contains(deliveryId);
    assertThat(version(orderId)).isEqualTo(3); // 1 → 인계 2 → 전달 3
  }

  @Test
  void deliveryBeforeHandoverIsKeptAndMismatchIsRecorded() throws Exception {
    String orderId = order("PREPARING", 2, 1);
    String deliveryId = "DLV-" + UUID.randomUUID();

    // 일부만 전달 → PARTIALLY_DELIVERED + OrderPartiallyDelivered
    delivered(orderId, deliveryId, List.of(line(orderId + "-L1", 2)));
    await(() -> "PARTIALLY_DELIVERED".equals(status(orderId)));
    assertThat(outbox(orderId, "OrderPartiallyDelivered")).isEqualTo(1);

    // 뒤늦은 인계는 수량만 반영, 상태는 되돌리지 않는다
    handover(orderId, "HO-" + UUID.randomUUID(), deliveryId, List.of(line(orderId + "-L1", 2)));
    await(() -> qty(orderId + "-L1", "handed_over_qty") == 2);
    assertThat(status(orderId)).isEqualTo("PARTIALLY_DELIVERED");

    // 미이행보다 많은 인계 → 반영 안 함, MISMATCH 로 남김
    String bad = "HO-" + UUID.randomUUID();
    handover(orderId, bad, deliveryId, List.of(line(orderId + "-L2", 5)));
    await(
        () ->
            "MISMATCH"
                .equals(
                    jdbc
                        .queryForList(
                            "SELECT state FROM order_fulfillment_fact WHERE kind = 'HANDOVER' AND source_ref = ?",
                            String.class,
                            bad)
                        .stream()
                        .findFirst()
                        .orElse(null)));
    assertThat(qty(orderId + "-L2", "handed_over_qty")).isZero();
    assertThat(qty(orderId + "-L2", "unfulfilled_qty")).isEqualTo(1);
  }

  // ---------- helpers ----------

  private String order(String status, int q1, int q2) {
    String orderId = "ORD-PG-" + UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO customer_order (order_id, account_id, draft_id, mfc_id, status, version,"
            + " delivery_mode, out_of_stock_preference, shopping_address_id, quote_version,"
            + " price_version, item_amount, delivery_tip_base, delivery_tip, delivery_tip_reason,"
            + " bag_fee, total_amount, first_purchase_applied, weight_g, planned_shipments,"
            + " payment_attempt_id, pg_transaction_id, approved_amount, reservation_id, confirmed_at)"
            + " VALUES (?,?,?,?,?,1,'SINGLE','CANCEL_ITEM','ADDR-1',1,1,20000,4000,4000,'BASE',100,"
            + "24100,false,1000,1,'PAY-1','PG-1',24100,'RSV-1',?)",
        orderId,
        "ACC-PG",
        "DRF-" + orderId,
        "MFC-PG",
        status,
        now);
    lineRow(orderId, 1, q1);
    lineRow(orderId, 2, q2);
    return orderId;
  }

  private void lineRow(String orderId, int no, int q) {
    jdbc.update(
        "INSERT INTO customer_order_line (order_line_id, order_id, line_no, sku, units_per_sku,"
            + " quantity, unit_price, line_amount, weight_g, original_qty, unfulfilled_qty)"
            + " VALUES (?,?,?,?,1,?,1000,?,100,?,?)",
        orderId + "-L" + no,
        orderId,
        no,
        "E-0000000" + no,
        q,
        1000L * q,
        q,
        q);
  }

  private static Map<String, Object> line(String orderLineId, int qty) {
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("orderLineId", orderLineId);
    l.put("sku", "E-00000001");
    l.put("lot", "LOT-" + qty);
    l.put("qty", qty);
    return l;
  }

  private void handover(
      String orderId, String handoverId, String deliveryId, List<Map<String, Object>> lines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId);
    p.put("shipmentId", orderId + "-S1");
    p.put("deliveryId", deliveryId);
    p.put("packageId", "PKG-" + handoverId);
    p.put("mfcId", "MFC-PG");
    p.put("riderId", "RD-PG");
    p.put("handoverId", handoverId);
    p.put("inventoryCommitId", "IC-" + handoverId);
    p.put("lines", lines);
    p.put("handedAt", Instant.now().toString());
    String json =
        EventEnvelopeFixtures.envelope("PackageHandedOver")
            .from("mfc-fulfillment-service")
            .aggregate("Package", "PKG-" + handoverId, 2)
            .payload(p)
            .buildJson();
    kafka.send("mfc.fulfillment.v1", orderId, json).join();
  }

  private void delivered(String orderId, String deliveryId, List<Map<String, Object>> lines) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", deliveryId);
    p.put("orderId", orderId);
    p.put("riderId", "RD-PG");
    p.put("lines", lines);
    p.put("method", "CONTACTLESS");
    p.put("evidenceRef", "EV-1");
    p.put("deliveredAt", Instant.now().toString());
    String json =
        EventEnvelopeFixtures.envelope("DeliveryCompleted")
            .from("delivery-execution-service")
            .aggregate("Delivery", deliveryId, 7)
            .payload(p)
            .buildJson();
    kafka.send("delivery.delivery.v1", deliveryId, json).join();
  }

  private String status(String orderId) {
    return jdbc.queryForObject(
        "SELECT status FROM customer_order WHERE order_id = ?", String.class, orderId);
  }

  private int version(String orderId) {
    Integer v =
        jdbc.queryForObject(
            "SELECT version FROM customer_order WHERE order_id = ?", Integer.class, orderId);
    return v == null ? 0 : v;
  }

  private int qty(String lineId, String column) {
    Integer v =
        jdbc.queryForObject(
            "SELECT " + column + " FROM customer_order_line WHERE order_line_id = ?",
            Integer.class,
            lineId);
    return v == null ? 0 : v;
  }

  private int outbox(String orderId, String type) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_event WHERE type = ? AND aggregateid = ?",
            Integer.class,
            type,
            orderId);
    return n == null ? 0 : n;
  }

  private static void await(Supplier<Boolean> cond) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(40));
    while (Instant.now().isBefore(until)) {
      if (Boolean.TRUE.equals(cond.get())) {
        return;
      }
      Thread.sleep(200);
    }
    assertThat(cond.get()).as("기다린 조건").isTrue();
  }
}
