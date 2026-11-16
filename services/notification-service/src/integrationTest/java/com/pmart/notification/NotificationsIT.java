package com.pmart.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객 알림함(0.2.0): 주문 확정·준비·출발·완료·결품·취소·환불(결과가 의도보다 먼저 와도)·문의 답변이 그 고객 알림함에 한 번씩 쌓이고, 읽음·남의 알림
 * 404·운영자 403·설정 변경 검사.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.notification.order-base-url=${order.url}"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "order", baseUrlProperties = "order.url")})
class NotificationsIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

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

  void publish(String topic, String type, String aggregate, String key, Map<String, Object> p) {
    kafka
        .send(
            topic,
            key,
            EventEnvelopeFixtures.envelope(type)
                .from("test")
                .aggregate(aggregate, key, 1)
                .payload(p)
                .buildJson())
        .join();
  }

  void stubOrder(String orderId, String account) {
    order.stubFor(
        get(urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"orderId":"%1$s","accountId":"%2$s","lines":[
                          {"orderLineId":"%1$s-L1","sku":"E-00000001","productName":"사과 1kg"}]}"""
                            .formatted(orderId, account))));
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

  static Map<String, Object> m(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  /** CP-1: 정원 축소 → 영향 주문 고객마다 "배송이 늦어질 수 있어요"(도착 시간대 KST), 주문 상세로. */
  @Test
  void slotCapacityReducedTellsEachAffectedCustomer() throws Exception {
    String me = "ACC-ntf-" + UUID.randomUUID().toString().substring(0, 8);
    String other = "ACC-ntf-" + UUID.randomUUID().toString().substring(0, 8);
    String o1 = "ORD-NTF-" + UUID.randomUUID().toString().substring(0, 8);
    String o2 = "ORD-NTF-" + UUID.randomUUID().toString().substring(0, 8);
    stubOrder(o1, me);
    stubOrder(o2, other);
    publish(
        "delivery.slot.v1",
        "SlotCapacityReduced",
        "Slot",
        "MFC-1:slot",
        m(
            "mfcId",
            "MFC-1",
            "window",
            m("start", "2026-11-17T09:00:00Z", "end", "2026-11-17T10:00:00Z", "meaning", "ARRIVAL"),
            "affectedOrders",
            List.of(o1, o2),
            "reason",
            "MFC_CAPACITY_REDUCED"));
    Res mine =
        await(
            () -> send("GET", TestJwt.customer(me), "/me/notifications?size=20", null),
            r -> r.body().path("items").size() >= 1);
    JsonNode n = mine.body().path("items").get(0);
    assertThat(n.path("title").asString()).isEqualTo("배송이 늦어질 수 있어요");
    assertThat(n.path("body").asString()).contains("11월 17일 18:00~19:00 도착 예정").contains("취소");
    assertThat(n.path("destination").path("ref").asString()).isEqualTo(o1);
    Res theirs =
        await(
            () -> send("GET", TestJwt.customer(other), "/me/notifications?size=20", null),
            r -> r.body().path("items").size() >= 1);
    assertThat(theirs.body().path("items")).hasSize(1);
    assertThat(theirs.body().path("items").get(0).path("destination").path("ref").asString())
        .isEqualTo(o2);
  }

  /** N-3(ADR-0065): 오배송 다시 배송비 결제 필요 → 주문 소유자 알림함(결제 분류, 주문 상세로). */
  @Test
  void redeliveryPaymentRequiredNotifiesOrderOwner() throws Exception {
    String me = "ACC-ntf-" + UUID.randomUUID().toString().substring(0, 8);
    String orderId = "ORD-NTF-" + UUID.randomUUID().toString().substring(0, 8);
    stubOrder(orderId, me);
    String token = TestJwt.customer(me);
    String now = Instant.now().toString();
    publish(
        "delivery.delivery.v1",
        "RedeliveryPaymentRequired",
        "Delivery",
        "DLV-RD-" + orderId,
        m(
            "deliveryId",
            "DLV-RD-" + orderId,
            "orderId",
            orderId,
            "caseId",
            "CASE-" + orderId,
            "originDeliveryId",
            "DLV-" + orderId,
            "straightDistanceM",
            850,
            "fee",
            9000,
            "requiredAt",
            now));
    Res inbox =
        await(
            () -> send("GET", token, "/me/notifications?size=20", null),
            r -> r.body().path("items").size() >= 1);
    assertThat(inbox.body().path("items")).hasSize(1);
    JsonNode n = inbox.body().path("items").get(0);
    assertThat(n.path("title").asString()).isEqualTo("실제 주소로 다시 보내려면 결제가 필요해요");
    assertThat(n.path("category").asString()).isEqualTo("PAYMENT");
    assertThat(n.path("body").asString()).contains("9,000원").contains("30분");
    assertThat(n.path("destination").path("type").asString()).isEqualTo("ORDER");
    assertThat(n.path("destination").path("ref").asString()).isEqualTo(orderId);
  }

  @Test
  void customerInboxCollectsOrderJourney() throws Exception {
    String me = "ACC-ntf-" + UUID.randomUUID().toString().substring(0, 8);
    String orderId = "ORD-NTF-" + UUID.randomUUID().toString().substring(0, 8);
    String token = TestJwt.customer(me);
    stubOrder(orderId, me);
    String now = Instant.now().toString();

    publish(
        "order.order.v1",
        "OrderConfirmed",
        "Order",
        orderId,
        m(
            "orderId",
            orderId,
            "accountId",
            me,
            "totalAmount",
            32100,
            "arrivalWindow",
            m("date", "2026-11-17", "start", "13:00", "end", "14:00"),
            "confirmedAt",
            now));
    publish(
        "order.order.v1",
        "OrderPreparationStarted",
        "Order",
        orderId,
        m("orderId", orderId, "startedAt", now));
    publish(
        "order.order.v1",
        "OrderLineQuantityChanged",
        "Order",
        orderId,
        m(
            "orderId",
            orderId,
            "orderLineId",
            orderId + "-L1",
            "reason",
            "SHORTAGE",
            "scope",
            "PARTIAL",
            "changedAt",
            now));
    // 출고 전 고객 취소 줄 변경은 알리지 않는다(OrderCancelled 가 알린다)
    publish(
        "order.order.v1",
        "OrderLineQuantityChanged",
        "Order",
        orderId,
        m(
            "orderId",
            orderId,
            "orderLineId",
            orderId + "-L1",
            "reason",
            "CANCEL_BEFORE_SHIP",
            "changedAt",
            now));
    publish(
        "delivery.delivery.v1",
        "PickupCompleted",
        "Delivery",
        "DLV-" + orderId,
        m("deliveryId", "DLV-" + orderId, "orderId", orderId, "pickedUpAt", now));
    publish(
        "delivery.delivery.v1",
        "DeliveryCompleted",
        "Delivery",
        "DLV-" + orderId,
        m(
            "deliveryId",
            "DLV-" + orderId,
            "orderId",
            orderId,
            "deliveredAt",
            now,
            "lines",
            List.of(m("orderLineId", orderId + "-L1", "qty", 1))));
    // 환불: 결과가 의도보다 먼저 온다
    String intent = "RFI-" + orderId;
    publish(
        "order.payment.v1",
        "RefundResult",
        "Payment",
        intent,
        m(
            "refundIntentId",
            intent,
            "refundRequestId",
            "RR-1",
            "amount",
            5000,
            "result",
            "SUCCESS",
            "resultAt",
            now));
    publish(
        "order.claim.v1",
        "RefundIntentCreated",
        "Claim",
        "CLM-" + orderId,
        m(
            "refundIntentId",
            intent,
            "claimId",
            "CLM-" + orderId,
            "orderId",
            orderId,
            "amount",
            5000));
    // 결과 모름은 알리지 않는다
    publish(
        "order.payment.v1",
        "RefundResult",
        "Payment",
        intent,
        m(
            "refundIntentId",
            intent,
            "refundRequestId",
            "RR-2",
            "amount",
            5000,
            "result",
            "UNKNOWN"));
    publish(
        "platform.case.v1",
        "InquiryAnswered",
        "Case",
        "CASE-1",
        m("inquiryId", "INQ-" + orderId, "customerId", me, "answeredAt", now));

    Res inbox =
        await(
            () -> send("GET", token, "/me/notifications?size=20", null),
            r -> r.body().path("items").size() >= 7);
    List<String> titles =
        inbox.body().path("items").valueStream().map(n -> n.path("title").asString()).toList();
    assertThat(titles)
        .containsExactlyInAnyOrder(
            "주문이 확정되었어요",
            "상품 준비를 시작했어요",
            "일부 상품이 품절되어 취소되었어요",
            "배송이 출발했어요",
            "배송이 완료되었어요",
            "환불이 완료되었어요",
            "문의에 답변이 등록되었어요");
    assertThat(inbox.body().path("unreadCount").asInt()).isEqualTo(7);
    JsonNode refund =
        inbox
            .body()
            .path("items")
            .valueStream()
            .filter(n -> n.path("category").asString().equals("REFUND"))
            .findFirst()
            .orElseThrow();
    assertThat(refund.path("body").asString()).startsWith("5,000원을");
    assertThat(refund.path("destination").path("ref").asString()).isEqualTo("CLM-" + orderId);
    JsonNode shortage =
        inbox
            .body()
            .path("items")
            .valueStream()
            .filter(n -> n.path("title").asString().contains("품절"))
            .findFirst()
            .orElseThrow();
    assertThat(shortage.path("body").asString()).startsWith("사과 1kg의");

    // 읽음 → 안 읽은 수 6, 다시 읽어도 그대로
    String id = refund.path("notificationId").asString();
    Res read = send("POST", token, "/me/notifications/" + id + "/reads", null);
    assertThat(read.body().path("state").asString()).isEqualTo("NOTICE_READ");
    assertThat(send("POST", token, "/me/notifications/" + id + "/reads", null).status())
        .isEqualTo(200);
    assertThat(
            send("GET", token, "/me/notifications?unreadOnly=true", null)
                .body()
                .path("unreadCount")
                .asInt())
        .isEqualTo(6);
    assertThat(
            send("GET", token, "/me/notifications?category=DELIVERY", null)
                .body()
                .path("items")
                .size())
        .isEqualTo(2);

    // 남의 알림 404, 운영자 403
    assertThat(send("GET", TestJwt.customer("ACC-other"), "/me/notifications/" + id, null).status())
        .isEqualTo(404);
    assertThat(
            send(
                    "GET",
                    TestJwt.operator("op-1", List.of("OPERATOR_ADMIN"), List.of()),
                    "/me/notifications",
                    null)
                .status())
        .isEqualTo(403);

    // 수신 설정: 모두 켜짐·잠김, 끄기는 422, 앱 푸시는 아직 없음
    Res settings = send("GET", token, "/me/notification-settings", null);
    assertThat(settings.body().path("items").size()).isEqualTo(4);
    assertThat(settings.body().path("items").get(0).path("locked").asBoolean()).isTrue();
    Res off =
        send(
            "POST",
            token,
            "/me/notification-setting-changes",
            m("changes", List.of(m("category", "ORDER", "channel", "IN_APP", "enabled", false))));
    assertThat(off.body().path("code").asString()).isEqualTo("TRANSACTIONAL_CANNOT_DISABLE");
    Res push =
        send(
            "POST",
            token,
            "/me/notification-setting-changes",
            m("changes", List.of(m("category", "ORDER", "channel", "APP_PUSH", "enabled", true))));
    assertThat(push.body().path("code").asString()).isEqualTo("CHANNEL_NOT_APPROVED");

    Thread.sleep(1500);
    assertThat(send("GET", token, "/me/notifications?size=50", null).body().path("items").size())
        .isEqualTo(7);
  }
}
