package com.pmart.delivery.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.delivery.dispatch.application.DispatchEngine;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * DD-1 첫 묶음: MFC 사건(mfc.fulfillment.v1) → 배송 생성(DeliveryCreated) → AI 추천 제안(OR-Tools·카카오 도로 시간) →
 * 수락 (DeliveryAssigned) → 인계(PICKED_UP). 일반 배차모드 배달리스트·반경 단계, 거절 → 재요청 → 실패, 작업 중지 → 픽업 전 취소.
 * rider·tracking· order·center·serviceability 는 WireMock 하나. 판단 주기는 시험이 직접 부른다(스케줄 PT1H).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.dispatch.tick-interval=PT1H",
      "pmart.dispatch.tick-initial-delay=PT1H",
      "pmart.dispatch.operating-start=00:00",
      "pmart.dispatch.operating-end=24:00"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "up",
        baseUrlProperties = {
          "pmart.dispatch.urls.rider",
          "pmart.dispatch.urls.tracking",
          "pmart.dispatch.urls.order",
          "pmart.dispatch.urls.center-master",
          "pmart.dispatch.urls.serviceability"
        }))
class DispatchFlowIT extends PostgresKafkaContainerSupport {

  static final String MFC_POINT = "37.4979,127.0276";

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired DispatchEngine engine;

  @InjectWireMock("up")
  WireMockServer up;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    up.resetAll();
    up.stubFor(
        get(urlPathMatching("/api/v1/internal/centers/.*"))
            .willReturn(json(Map.of("coordinates", Map.of("lat", 37.4979, "lng", 127.0276)))));
    up.stubFor(
        get(urlPathMatching("/api/v1/internal/orders/.*/delivery-destination"))
            .willReturn(
                json(
                    Map.of(
                        "orderId",
                        "x",
                        "mfcId",
                        "x",
                        "status",
                        "CONFIRMED",
                        "deliveryMode",
                        "SINGLE",
                        "roadAddress",
                        "IT-ROAD"))));
    up.stubFor(
        post(urlPathEqualTo("/api/v1/internal/address-geocodes"))
            .willReturn(
                json(
                    Map.of(
                        "results",
                        List.of(
                            Map.of(
                                "lat",
                                37.5006,
                                "lng",
                                127.0360,
                                "jibunAddress",
                                "서울 서초구 서초동 1-2"))))));
    // MFC → 고객(1,800m·400초), 라이더 → MFC(행마다 300초)
    up.stubFor(
        post(urlPathEqualTo("/api/v1/internal/distance-matrix"))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.origins[0].lat", equalTo("37.4979")))
            .withRequestBody(matchingJsonPath("$.origins[0].lng", equalTo("127.0276")))
            .willReturn(json(matrix(List.of(List.of(1800, 400))))));
    up.stubFor(
        post(urlPathEqualTo("/api/v1/internal/distance-matrix"))
            .atPriority(5)
            .willReturn(
                json(matrix(List.of(List.of(900, 300), List.of(900, 300), List.of(900, 300))))));
  }

  @Test
  void aiOfferAcceptAndHandover() {
    String mfc = "MFC-D1";
    String rider = "RD-a-" + UUID.randomUUID();
    String other = "RD-o-" + UUID.randomUUID();
    riders(mfc, rider(rider, "AI_RECOMMENDED", true));
    riderLookup(rider(rider, "AI_RECOMMENDED", true), rider(other, "NORMAL", true));
    locations(
        Map.of(rider, new double[] {37.5010, 127.0280}, other, new double[] {37.5010, 127.0280}));

    String shipment = "SHP-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    send(
        "DispatchRequested",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "mfcId",
            mfc,
            "trigger",
            "MANUAL",
            "estimatedPackMinutes",
            5,
            "estimatedReadyAt",
            Instant.now().plusSeconds(300).toString(),
            "requestedAt",
            Instant.now().toString()));
    String deliveryId = awaitDelivery(shipment);
    send(
        "ShipmentReady",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "mfcId",
            mfc,
            "readyAt",
            Instant.now().toString(),
            "packages",
            List.of(
                Map.of(
                    "packageId",
                    "PKG-1-" + shipment,
                    "bagCount",
                    2,
                    "weightG",
                    3200,
                    "alcohol",
                    false))));
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND ready_at IS NOT NULL");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM delivery WHERE shipment_id = ?", Integer.class, shipment))
        .isEqualTo(1);
    JsonNode created = outbox(deliveryId, "DeliveryCreated");
    assertThat(created.path("payload").path("shipmentId").asString()).isEqualTo(shipment);
    assertThat(created.path("payload").path("dropAddressRef").asString())
        .isEqualTo("order:" + order);

    engine.tick();

    JsonNode sent = outbox(deliveryId, "DispatchOfferSent");
    assertThat(sent.path("payload").path("riderId").asString()).isEqualTo(rider);
    assertThat(sent.path("payload").path("fee").asInt()).isEqualTo(3200); // 원문 §7.1 1,350~2,000m

    JsonNode mine =
        body(
            http.get()
                .uri("/offers")
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .toEntity(String.class));
    assertThat(mine.path("items")).hasSize(1);
    JsonNode offer = mine.path("items").get(0);
    String offerId = offer.path("offerId").asString();
    assertThat(offer.path("summary").path("dropAreaLabel").asString()).isEqualTo("서초구 서초동");
    assertThat(offer.path("summary").path("distanceKm").asDouble()).isEqualTo(1.8);
    assertThat(offer.path("summary").path("pickupEtaSeconds").asInt()).isEqualTo(300);
    assertThat(offer.path("summary").path("bagCount").asInt()).isEqualTo(2);
    assertThat(offer.path("remainingSec").asInt()).isBetween(1, 60);
    assertThat(mine.path("pollAfterSec").asInt()).isEqualTo(5);

    // 남의 제안 403, 상세를 열면 viewedAt
    assertThat(
            http.get()
                .uri("/offers/{id}", offerId)
                .header("Authorization", "Bearer " + TestJwt.rider(other))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(
            body(http.get()
                    .uri("/offers/{id}", offerId)
                    .header("Authorization", "Bearer " + TestJwt.rider(rider))
                    .retrieve()
                    .toEntity(String.class))
                .path("viewedAt")
                .isString())
        .isTrue();

    ResponseEntity<String> accept = respond(rider, offerId, "k-" + offerId, "ACCEPT");
    assertThat(body(accept).path("outcome").asString()).isEqualTo("ASSIGNED");
    assertThat(body(respond(rider, offerId, "k-" + offerId, "ACCEPT")).path("outcome").asString())
        .isEqualTo("ASSIGNED");
    JsonNode assigned = outbox(deliveryId, "DeliveryAssigned");
    assertThat(assigned.path("payload").path("riderId").asString()).isEqualTo(rider);
    assertThat(assigned.path("payload").path("assignmentVersion").asInt()).isEqualTo(1);
    // 수락 당시 금액·수단·거리(execution 수행료 기준, 2026-11-13)
    assertThat(assigned.path("payload").path("fee").isNumber()).isTrue();
    assertThat(assigned.path("payload").path("vehicleType").asString()).isEqualTo("MOTORCYCLE");
    assertThat(assigned.path("payload").path("distanceM").asInt()).isEqualTo(1800);

    // 다른 라이더가 배달리스트에서 수락 → 이미 배정
    JsonNode late =
        body(
            http.post()
                .uri("/delivery-list/{id}/acceptances", deliveryId)
                .headers(h -> riderHeaders(h, other, "l-" + deliveryId))
                .retrieve()
                .toEntity(String.class));
    assertThat(late.path("outcome").asString()).isEqualTo("LATE");
    assertThat(late.path("lateReason").asString()).isEqualTo("ALREADY_ASSIGNED");

    JsonNode view =
        body(
            http.get()
                .uri("/deliveries/{id}/dispatch", deliveryId)
                .header(
                    "Authorization", "Bearer " + TestJwt.operator("OP-1", List.of(), List.of(mfc)))
                .retrieve()
                .toEntity(String.class));
    assertThat(view.path("state").asString()).isEqualTo("ASSIGNED");
    assertThat(view.path("assignment").path("riderId").asString()).isEqualTo(rider);
    assertThat(
            http.get()
                .uri("/deliveries/{id}/dispatch", deliveryId)
                .header(
                    "Authorization",
                    "Bearer " + TestJwt.operator("OP-2", List.of(), List.of("MFC-OTHER")))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    JsonNode internal =
        body(
            http.get()
                .uri("/internal/deliveries/{id}/assignment", deliveryId)
                .header("X-Source-System", "order-orchestration-service")
                .retrieve()
                .toEntity(String.class));
    assertThat(internal.path("riderId").asString()).isEqualTo(rider);

    send(
        "PackageHandedOver",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "deliveryId",
            deliveryId,
            "packageId",
            "PKG-1-" + shipment,
            "riderId",
            rider,
            "handoverId",
            "HO-1",
            "inventoryCommitId",
            "IC-1",
            "lines",
            List.of(),
            "handedAt",
            Instant.now().toString()));
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND state = 'PICKED_UP'");

    // 전달 완료(delivery-execution) → CLOSED — 라이더 "진행 중" 수에서 빠진다(PC 1 검토 H2)
    String completed =
        EventEnvelopeFixtures.envelope("DeliveryCompleted")
            .from("delivery-execution-service")
            .aggregate("Delivery", deliveryId, 5)
            .payload(
                Map.of(
                    "deliveryId",
                    deliveryId,
                    "orderId",
                    order,
                    "riderId",
                    rider,
                    "lines",
                    List.of(Map.of("orderLineId", "L-1", "qty", 1)),
                    "method",
                    "CONTACTLESS",
                    "evidenceRef",
                    Map.of("evidenceId", "EVD-1", "kind", "PHOTO"),
                    "deliveredAt",
                    Instant.now().toString()))
            .buildJson();
    kafka.send("delivery.delivery.v1", deliveryId, completed).join();
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND state = 'CLOSED'");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM delivery WHERE rider_id = ? AND state IN ('ASSIGNED', 'PICKED_UP')",
                Integer.class,
                rider))
        .isZero();
  }

  @Test
  void normalModeRiderSeesListOnlyInsideStageRadius() {
    String mfc = "MFC-D2";
    String normal = "RD-n-" + UUID.randomUUID();
    riders(mfc, rider(normal, "NORMAL", true));
    riderLookup(rider(normal, "NORMAL", true));
    locations(Map.of(normal, new double[] {37.5110, 127.0276})); // MFC 에서 북쪽 약 1.46km
    String shipment = "SHP-" + UUID.randomUUID();
    String deliveryId = readyShipment(mfc, shipment, "PKG-2-" + shipment);

    engine.tick(); // AI 추천 후보 없음 → 1단계에 머문다
    assertThat(
            jdbc.queryForObject(
                "SELECT stage FROM dispatch_attempt WHERE delivery_id = ?",
                Integer.class,
                deliveryId))
        .isEqualTo(1);
    assertThat(listIds(normal)).doesNotContain(deliveryId); // 1km 밖

    jdbc.update(
        "UPDATE dispatch_attempt SET stage_started_at = ?, next_run_at = ? WHERE delivery_id = ?",
        ago(120),
        ago(0),
        deliveryId);
    engine.tick(); // 응답 대기만큼 머문 뒤 2단계(2km)
    assertThat(
            jdbc.queryForObject(
                "SELECT stage FROM dispatch_attempt WHERE delivery_id = ?",
                Integer.class,
                deliveryId))
        .isEqualTo(2);
    assertThat(listIds(normal)).contains(deliveryId);

    JsonNode ok =
        body(
            http.post()
                .uri("/delivery-list/{id}/acceptances", deliveryId)
                .headers(h -> riderHeaders(h, normal, "a-" + deliveryId))
                .retrieve()
                .toEntity(String.class));
    assertThat(ok.path("outcome").asString()).isEqualTo("ASSIGNED");
    assertThat(outbox(deliveryId, "DeliveryAssigned").path("payload").path("riderId").asString())
        .isEqualTo(normal);
    assertThat(
            jdbc.queryForObject(
                "SELECT source FROM dispatch_offer WHERE delivery_id = ? AND state = 'ACCEPTED'",
                String.class,
                deliveryId))
        .isEqualTo("DELIVERY_LIST");
  }

  @Test
  void rejectionRetriesSameRiderThenFails() {
    String mfc = "MFC-D3";
    String rider = "RD-r-" + UUID.randomUUID();
    riders(mfc, rider(rider, "AI_RECOMMENDED", true));
    riderLookup(rider(rider, "AI_RECOMMENDED", true));
    locations(Map.of(rider, new double[] {37.4990, 127.0280}));
    String shipment = "SHP-" + UUID.randomUUID();
    String deliveryId = readyShipment(mfc, shipment, "PKG-3-" + shipment);

    engine.tick();
    String first =
        jdbc.queryForObject(
            "SELECT offer_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
            String.class,
            deliveryId);
    JsonNode rej = body(respond(rider, first, "r1-" + first, "REJECT"));
    assertThat(rej.path("outcome").asString()).isEqualTo("REJECTED");
    assertThat(rej.path("consecutiveRejections").asInt()).isEqualTo(1);

    // 같은 회차에는 거절한 라이더를 빼고 찾는다 → 마지막 단계까지 없으면 10분 뒤 재요청
    jdbc.update(
        "UPDATE dispatch_attempt SET stage = 3, stage_started_at = ?, next_run_at = ? WHERE delivery_id = ?",
        ago(120),
        ago(0),
        deliveryId);
    engine.tick();
    Map<String, Object> a =
        jdbc.queryForMap(
            "SELECT stage, retry_count, round, next_run_at > ? AS later FROM dispatch_attempt WHERE delivery_id = ?",
            java.sql.Timestamp.from(Instant.now().plusSeconds(540)),
            deliveryId);
    assertThat(a.get("retry_count")).isEqualTo(1);
    assertThat(a.get("round")).isEqualTo(1);
    assertThat(a.get("stage")).isEqualTo(1);
    assertThat(a.get("later")).isEqualTo(true);

    // 재요청 회차에는 거절했던 라이더에게도 다시(원문 §3.5)
    jdbc.update(
        "UPDATE dispatch_attempt SET next_run_at = ?, stage_started_at = ? WHERE delivery_id = ?",
        ago(0),
        ago(0),
        deliveryId);
    engine.tick();
    String second =
        jdbc.queryForObject(
            "SELECT offer_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
            String.class,
            deliveryId);
    assertThat(second).isNotEqualTo(first);
    assertThat(
            jdbc.queryForObject(
                "SELECT rider_id FROM dispatch_offer WHERE offer_id = ?", String.class, second))
        .isEqualTo(rider);

    // 응답 없이 만료 → 마지막 재요청까지 다 썼으면 실패(거절이 있었으니 REPEATED_REJECTION) → 10분 뒤 자동으로 다시 배차 요청(M2)
    jdbc.update("UPDATE dispatch_offer SET expires_at = ? WHERE offer_id = ?", ago(1), second);
    jdbc.update(
        "UPDATE dispatch_attempt SET retry_count = 2, stage = 3 WHERE delivery_id = ?", deliveryId);
    engine.tick(); // 만료 → 다시 탐색(후보 없음, 단계에 머묾)
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM dispatch_offer WHERE offer_id = ?", String.class, second))
        .isEqualTo("EXPIRED");
    jdbc.update(
        "UPDATE dispatch_attempt SET stage_started_at = ?, next_run_at = ? WHERE delivery_id = ?",
        ago(120),
        ago(0),
        deliveryId);
    engine.tick();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, deliveryId))
        .isEqualTo("DISPATCHING");
    assertThat(outbox(deliveryId, "DispatchFailed").path("payload").path("reason").asString())
        .isEqualTo("REPEATED_REJECTION");
    Map<String, Object> next =
        jdbc.queryForMap(
            "SELECT trigger, state, next_run_at > ? AS later FROM dispatch_attempt WHERE delivery_id = ? AND"
                + " state = 'SEARCHING'",
            java.sql.Timestamp.from(Instant.now().plusSeconds(540)),
            deliveryId);
    assertThat(next.get("trigger")).isEqualTo("RETRY");
    assertThat(next.get("later")).isEqualTo(true);
  }

  @Test
  void allPackagesStoppedCancelsBeforePickup() {
    String mfc = "MFC-D4";
    riders(mfc);
    String shipment = "SHP-" + UUID.randomUUID();
    String pkg = "PKG-4-" + shipment;
    String deliveryId = readyShipment(mfc, shipment, pkg);
    String order =
        jdbc.queryForObject(
            "SELECT order_id FROM delivery WHERE delivery_id = ?", String.class, deliveryId);
    send(
        "WorkStopResult",
        order,
        Map.of(
            "orderId",
            order,
            "stopRequestId",
            "STOP-1",
            "perPackage",
            List.of(Map.of("packageId", pkg, "state", "STOPPED")),
            "decidedAt",
            Instant.now().toString()));
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND state = 'CANCELLED_BEFORE_PICKUP'");
    assertThat(
            outbox(deliveryId, "DeliveryCancelledBeforePickup")
                .path("payload")
                .path("reason")
                .asString())
        .isEqualTo("ORDER_CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM dispatch_attempt WHERE delivery_id = ?",
                String.class,
                deliveryId))
        .isEqualTo("CANCELLED");
  }

  @Test
  void bundledDeliveriesAreOfferedAsOneBundleAndAcceptedTogether() {
    String mfc = "MFC-D5";
    String rider = "RD-b-" + UUID.randomUUID();
    riders(mfc, rider(rider, "AI_RECOMMENDED", true));
    riderLookup(rider(rider, "AI_RECOMMENDED", true));
    locations(Map.of(rider, new double[] {37.4985, 127.0280}));
    String orderA = "ORD-A-" + UUID.randomUUID();
    String orderB = "ORD-B-" + UUID.randomUUID();
    bundledOrder(orderA, "IT-ROAD-A", 37.5006, 127.0360);
    bundledOrder(orderB, "IT-ROAD-B", 37.5012, 127.0372);
    // 고객 A ↔ B 120초(두 순서 모두)
    for (String q :
        List.of("37.5006,127.036;37.5012,127.0372", "37.5012,127.0372;37.5006,127.036")) {
      up.stubFor(
          post(urlPathEqualTo("/api/v1/internal/distance-matrix"))
              .atPriority(0)
              .withRequestBody(
                  matchingJsonPath("$.origins[0].lat", equalTo(q.split(";")[0].split(",")[0])))
              .withRequestBody(
                  matchingJsonPath("$.origins[1].lat", equalTo(q.split(";")[1].split(",")[0])))
              .willReturn(json(square(120))));
    }
    String a = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-5a-" + orderA, orderA);
    String b = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-5b-" + orderB, orderB);

    engine.tick();

    List<Map<String, Object>> offers =
        jdbc.queryForList(
            "SELECT offer_id, delivery_id, bundle_id, bundle_size, stop_seq, rider_id FROM dispatch_offer"
                + " WHERE delivery_id IN (?, ?) AND state = 'SENT' ORDER BY stop_seq",
            a,
            b);
    assertThat(offers).hasSize(2);
    assertThat(offers.get(0).get("bundle_id"))
        .isNotNull()
        .isEqualTo(offers.get(1).get("bundle_id"));
    assertThat(offers).allSatisfy(o -> assertThat(o.get("bundle_size")).isEqualTo(2));
    assertThat(offers).allSatisfy(o -> assertThat(o.get("rider_id")).isEqualTo(rider));
    assertThat(offers.stream().map(o -> o.get("stop_seq")).toList()).containsExactly(1, 2);

    JsonNode mine =
        body(
            http.get()
                .uri("/offers")
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .toEntity(String.class));
    assertThat(mine.path("items")).hasSize(2);
    assertThat(mine.path("items").get(0).path("bundleSize").asInt()).isEqualTo(2);

    String second = (String) offers.get(1).get("offer_id");
    JsonNode ok = body(respond(rider, second, "k-" + second, "ACCEPT"));
    assertThat(ok.path("outcome").asString()).isEqualTo("ASSIGNED");
    for (String id : List.of(a, b)) {
      Map<String, Object> d =
          jdbc.queryForMap(
              "SELECT state, rider_id, bundle_id, stop_seq FROM delivery WHERE delivery_id = ?",
              id);
      assertThat(d.get("state")).isEqualTo("ASSIGNED");
      assertThat(d.get("rider_id")).isEqualTo(rider);
      assertThat(d.get("bundle_id")).isEqualTo(offers.get(0).get("bundle_id"));
      assertThat(outbox(id, "DeliveryAssigned").path("payload").path("riderId").asString())
          .isEqualTo(rider);
    }
    JsonNode view =
        body(
            http.get()
                .uri("/internal/deliveries/{id}/assignment", b)
                .header("X-Source-System", "order-orchestration-service")
                .retrieve()
                .toEntity(String.class));
    assertThat(view.path("stopSequence").asInt())
        .isEqualTo(
            ((Number)
                    jdbc.queryForObject(
                        "SELECT stop_seq FROM delivery WHERE delivery_id = ?", Integer.class, b))
                .intValue());
  }

  @Test
  void rejectingABundleRejectsEveryDeliveryOnce() {
    String mfc = "MFC-D6";
    String rider = "RD-x-" + UUID.randomUUID();
    riders(mfc, rider(rider, "AI_RECOMMENDED", true));
    riderLookup(rider(rider, "AI_RECOMMENDED", true));
    locations(Map.of(rider, new double[] {37.4985, 127.0280}));
    String orderA = "ORD-C-" + UUID.randomUUID();
    String orderB = "ORD-D-" + UUID.randomUUID();
    bundledOrder(orderA, "IT-ROAD-C", 37.5020, 127.0300);
    bundledOrder(orderB, "IT-ROAD-D", 37.5024, 127.0310);
    for (String q : List.of("37.502,127.03;37.5024,127.031", "37.5024,127.031;37.502,127.03")) {
      up.stubFor(
          post(urlPathEqualTo("/api/v1/internal/distance-matrix"))
              .atPriority(0)
              .withRequestBody(
                  matchingJsonPath("$.origins[0].lat", equalTo(q.split(";")[0].split(",")[0])))
              .withRequestBody(
                  matchingJsonPath("$.origins[1].lat", equalTo(q.split(";")[1].split(",")[0])))
              .willReturn(json(square(90))));
    }
    String a = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-6a-" + orderA, orderA);
    String b = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-6b-" + orderB, orderB);
    engine.tick();
    String first =
        jdbc.queryForObject(
            "SELECT offer_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
            String.class,
            a);
    JsonNode rej = body(respond(rider, first, "r-" + first, "REJECT"));
    assertThat(rej.path("outcome").asString()).isEqualTo("REJECTED");
    assertThat(rej.path("consecutiveRejections").asInt()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM dispatch_offer WHERE delivery_id IN (?, ?) AND state = 'REJECTED'",
                Integer.class,
                a,
                b))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM dispatch_attempt WHERE delivery_id IN (?, ?) AND state = 'SEARCHING'",
                Integer.class,
                a,
                b))
        .isEqualTo(2);
  }

  void bundledOrder(String orderId, String road, double lat, double lng) {
    up.stubFor(
        get(urlPathEqualTo("/api/v1/internal/orders/" + orderId + "/delivery-destination"))
            .atPriority(1)
            .willReturn(
                json(
                    Map.of(
                        "orderId",
                        orderId,
                        "mfcId",
                        "x",
                        "status",
                        "CONFIRMED",
                        "deliveryMode",
                        "BUNDLED",
                        "roadAddress",
                        road))));
    up.stubFor(
        post(urlPathEqualTo("/api/v1/internal/address-geocodes"))
            .atPriority(1)
            .withRequestBody(matchingJsonPath("$.query", equalTo(road)))
            .willReturn(
                json(
                    Map.of(
                        "results",
                        List.of(Map.of("lat", lat, "lng", lng, "jibunAddress", "서울 강남구 역삼동 1"))))));
  }

  static Map<String, Object> square(int seconds) {
    return Map.of(
        "provider",
        "KAKAO_MOBILITY",
        "fallback",
        false,
        "rows",
        List.of(
            Map.of(
                "elements",
                List.of(
                    Map.of("status", "OK", "distanceM", 0, "durationSeconds", 0),
                    Map.of("status", "OK", "distanceM", 500, "durationSeconds", seconds))),
            Map.of(
                "elements",
                List.of(
                    Map.of("status", "OK", "distanceM", 500, "durationSeconds", seconds),
                    Map.of("status", "OK", "distanceM", 0, "durationSeconds", 0)))));
  }

  @Test
  void riderCancelsBeforePickupAndAnotherRiderIsOffered() {
    String mfc = "MFC-D7";
    String r1 = "RD-c1-" + UUID.randomUUID();
    String r2 = "RD-c2-" + UUID.randomUUID();
    riders(mfc, rider(r1, "AI_RECOMMENDED", true), rider(r2, "AI_RECOMMENDED", true));
    riderLookup(rider(r1, "AI_RECOMMENDED", true), rider(r2, "AI_RECOMMENDED", true));
    locations(Map.of(r1, new double[] {37.4985, 127.0280}, r2, new double[] {37.4986, 127.0281}));
    String deliveryId =
        readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-7-" + UUID.randomUUID());
    engine.tick();
    Map<String, Object> o =
        jdbc.queryForMap(
            "SELECT offer_id, rider_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
            deliveryId);
    String first = (String) o.get("rider_id");
    String other = first.equals(r1) ? r2 : r1;
    assertThat(
            body(respond(first, (String) o.get("offer_id"), "a-" + deliveryId, "ACCEPT"))
                .path("outcome")
                .asString())
        .isEqualTo("ASSIGNED");

    // 배정되지 않은 라이더는 취소할 수 없다
    assertThat(cancel(other, deliveryId, "x-" + deliveryId).getStatusCode().value()).isEqualTo(403);
    ResponseEntity<String> ok = cancel(first, deliveryId, "c-" + deliveryId);
    assertThat(ok.getStatusCode().value()).isEqualTo(202);
    assertThat(cancel(first, deliveryId, "c-" + deliveryId).getStatusCode().value())
        .isEqualTo(202); // 같은 키
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, deliveryId))
        .isEqualTo("DISPATCHING");
    JsonNode changed = outbox(deliveryId, "DeliveryAssignmentChanged").path("payload");
    assertThat(changed.path("oldRiderId").asString()).isEqualTo(first);
    assertThat(changed.path("newRiderId").isNull()).isTrue();
    assertThat(changed.path("assignmentVersion").asInt()).isEqualTo(2);
    JsonNode result =
        body(
            http.get()
                .uri("/deliveries/{id}/assignment-cancellations/{r}", deliveryId, "c-" + deliveryId)
                .header("Authorization", "Bearer " + TestJwt.rider(first))
                .retrieve()
                .toEntity(String.class));
    assertThat(result.path("state").asString()).isEqualTo("CANCELLED_BEFORE_PICKUP");
    // 결과는 요청한 본인·고객센터·그 센터 운영자만(L1) — 다른 라이더에게는 없는 것처럼
    assertThat(
            http.get()
                .uri("/deliveries/{id}/assignment-cancellations/{r}", deliveryId, "c-" + deliveryId)
                .header("Authorization", "Bearer " + TestJwt.rider(other))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            http.get()
                .uri("/deliveries/{id}/assignment-cancellations/{r}", deliveryId, "c-" + deliveryId)
                .header(
                    "Authorization",
                    "Bearer " + TestJwt.operator("OP-CS", List.of("CS_AGENT"), List.of()))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);

    // 재배차 — 취소한 라이더는 빼고 다른 라이더에게
    engine.tick();
    assertThat(
            jdbc.queryForObject(
                "SELECT rider_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
                String.class,
                deliveryId))
        .isEqualTo(other);

    // 취소한 라이더는 배달리스트에서도 이 배송을 못 받는다(L5)
    JsonNode again =
        body(
            http.post()
                .uri("/delivery-list/{id}/acceptances", deliveryId)
                .headers(h -> riderHeaders(h, first, "l-" + deliveryId))
                .retrieve()
                .toEntity(String.class));
    assertThat(again.path("outcome").asString()).isEqualTo("LATE");
    assertThat(again.path("lateReason").asString()).isEqualTo("INELIGIBLE");
    // 같은 키를 다른 경로(X-Source-System)로 다시 써도 500 이 아니라 409(L2)
    ResponseEntity<String> otherSource =
        http.post()
            .uri("/deliveries/{id}/assignment-cancellations", deliveryId)
            .headers(
                h -> {
                  h.setBearerAuth(TestJwt.operator("OP-7", List.of(), List.of(mfc)));
                  h.set("Idempotency-Key", "c-" + deliveryId);
                  h.set("X-Source-System", "mfc-backoffice");
                })
            .body(
                Map.of("requestedBy", "STORE_STAFF", "reason", "STORE_REQUEST", "reassign", false))
            .retrieve()
            .toEntity(String.class);
    assertThat(otherSource.getStatusCode().value()).isEqualTo(409);
    assertThat(body(otherSource).path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, deliveryId))
        .isEqualTo("DISPATCHING"); // 취소는 되돌려졌다(같은 트랜잭션)
    // 다른 라이더가 수락 → 제안만 받았던 첫 라이더는 누가 배정됐는지 못 본다(L4)
    String offerId =
        jdbc.queryForObject(
            "SELECT offer_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
            String.class,
            deliveryId);
    assertThat(
            body(respond(other, offerId, "a2-" + deliveryId, "ACCEPT")).path("outcome").asString())
        .isEqualTo("ASSIGNED");
    JsonNode seenByFirst =
        body(
            http.get()
                .uri("/deliveries/{id}/dispatch", deliveryId)
                .header("Authorization", "Bearer " + TestJwt.rider(first))
                .retrieve()
                .toEntity(String.class));
    assertThat(seenByFirst.path("state").asString()).isEqualTo("ASSIGNED");
    assertThat(
            seenByFirst.path("assignment").isMissingNode()
                || seenByFirst.path("assignment").isNull())
        .isTrue();
    // 배송 사건마다 aggregateVersion 이 다르다(L3 — 제안 사건이 직전 사건과 같은 버전이던 것)
    List<Long> versions =
        jdbc.queryForList(
            "SELECT (payload->>'aggregateVersion')::bigint FROM outbox_event WHERE aggregateid = ?"
                + " ORDER BY created_at",
            Long.class,
            deliveryId);
    assertThat(versions).doesNotHaveDuplicates();

    // 픽업 뒤는 지원센터로(422)
    jdbc.update(
        "UPDATE delivery SET state = 'PICKED_UP', rider_id = ? WHERE delivery_id = ?",
        other,
        deliveryId);
    ResponseEntity<String> late = cancel(other, deliveryId, "p-" + deliveryId);
    assertThat(late.getStatusCode().value()).isEqualTo(422);
    assertThat(body(late).path("code").asString()).isEqualTo("PICKED_UP_CASE_REQUIRED");
  }

  /** RS-1(사용자 2026-11-14): 라이더 계정 제한 → 픽업 전 배정은 풀고 다시 배차, 픽업한 배송은 끝까지 전달(그대로), 응답 전 제안은 LATE. */
  @Test
  void restrictedRiderLosesPrePickupAssignmentsButKeepsPickedUp() {
    String mfc = "MFC-D13";
    String r1 = "RD-rs1-" + UUID.randomUUID();
    String r2 = "RD-rs2-" + UUID.randomUUID();
    riders(mfc, rider(r1, "AI_RECOMMENDED", true));
    riderLookup(rider(r1, "AI_RECOMMENDED", true), rider(r2, "AI_RECOMMENDED", true));
    locations(Map.of(r1, new double[] {37.4985, 127.0280}, r2, new double[] {37.4986, 127.0281}));

    String assigned = acceptedBy(r1, mfc);
    String pickedUp = acceptedBy(r1, mfc);
    jdbc.update("UPDATE delivery SET state = 'PICKED_UP' WHERE delivery_id = ?", pickedUp);
    String offered = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-13-" + UUID.randomUUID());
    engine.tick();
    String openOffer =
        jdbc.queryForObject(
            "SELECT offer_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT' AND rider_id = ?",
            String.class,
            offered,
            r1);

    String json =
        EventEnvelopeFixtures.envelope("RiderRestricted")
            .from("rider-service")
            .aggregate("Rider", r1, 3)
            .payload(
                Map.of(
                    "riderId",
                    r1,
                    "restriction",
                    "DISPATCH_RESTRICTED",
                    "from",
                    Instant.now().toString(),
                    "basis",
                    "고객 신고 확인"))
            .buildJson();
    kafka.send("delivery.rider.v1", r1, json).join();
    awaitSql(
        "SELECT count(*) FROM outbox_event WHERE aggregateid = '"
            + assigned
            + "' AND type = 'DeliveryAssignmentChanged'");

    // 픽업 전 배정 → 풀고 재배차(그 라이더는 뺀다)
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, assigned))
        .isEqualTo("DISPATCHING");
    JsonNode changed = outbox(assigned, "DeliveryAssignmentChanged").path("payload");
    assertThat(changed.path("oldRiderId").asString()).isEqualTo(r1);
    assertThat(changed.path("newRiderId").isNull()).isTrue();
    assertThat(changed.path("reason").asString()).isEqualTo("RIDER_RESTRICTED");
    assertThat(changed.path("assignmentVersion").asInt()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT excluded_rider_id FROM dispatch_attempt WHERE delivery_id = ? AND trigger ="
                    + " 'REASSIGN'",
                String.class,
                assigned))
        .isEqualTo(r1);
    // 픽업한 배송은 그대로(전달까지)
    Map<String, Object> kept =
        jdbc.queryForMap("SELECT state, rider_id FROM delivery WHERE delivery_id = ?", pickedUp);
    assertThat(kept.get("state")).isEqualTo("PICKED_UP");
    assertThat(kept.get("rider_id")).isEqualTo(r1);
    assertThat(
            jdbc.queryForObject(
                "SELECT restriction FROM restricted_rider WHERE rider_id = ?", String.class, r1))
        .isEqualTo("DISPATCH_RESTRICTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type ="
                    + " 'DeliveryAssignmentChanged'",
                Integer.class,
                pickedUp))
        .isZero();
    // 응답 전 제안은 LATE — 뒤늦은 수락도 LATE
    assertThat(
            jdbc.queryForObject(
                "SELECT state || ':' || late_reason FROM dispatch_offer WHERE offer_id = ?",
                String.class,
                openOffer))
        .isEqualTo("LATE:INELIGIBLE");
    JsonNode late = body(respond(r1, openOffer, "late-" + offered, "ACCEPT"));
    assertThat(late.path("outcome").asString()).isEqualTo("LATE");
    // rider 조회가 아직 제한 전(스텁은 NORMAL)이어도 배달리스트 수락은 잠금 아래 제한 사본을 보고 막힌다 — 겹친 수락이 배정되지 않음
    JsonNode raced =
        body(
            http.post()
                .uri("/delivery-list/{id}/acceptances", offered)
                .headers(h -> riderHeaders(h, r1, "race-" + offered))
                .retrieve()
                .toEntity(String.class));
    assertThat(raced.path("outcome").asString()).isEqualTo("LATE");
    assertThat(raced.path("lateReason").asString()).isEqualTo("INELIGIBLE");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, offered))
        .isNotEqualTo("ASSIGNED");

    // 다른 라이더에게 다시 배차
    riders(mfc, rider(r2, "AI_RECOMMENDED", true));
    engine.tick();
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT rider_id FROM dispatch_offer WHERE delivery_id IN (?, ?) AND state ="
                    + " 'SENT'",
                String.class,
                assigned,
                offered))
        .containsExactly(r2);

    // 풀기(NORMAL) → 제한 사본 지움
    kafka
        .send(
            "delivery.rider.v1",
            r1,
            EventEnvelopeFixtures.envelope("RiderRestricted")
                .from("rider-service")
                .aggregate("Rider", r1, 4)
                .payload(
                    Map.of(
                        "riderId",
                        r1,
                        "restriction",
                        "NORMAL",
                        "from",
                        Instant.now().toString(),
                        "basis",
                        "소명 확인"))
                .buildJson())
        .join();
    long until = System.currentTimeMillis() + 30_000;
    while (jdbc.queryForObject(
                "SELECT count(*) FROM restricted_rider WHERE rider_id = ?", Integer.class, r1)
            > 0
        && System.currentTimeMillis() < until) {
      try {
        Thread.sleep(200);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM restricted_rider WHERE rider_id = ?", Integer.class, r1))
        .isZero();
  }

  /** 배송 하나를 만들어 그 라이더에게 제안 → 수락(ASSIGNED). 그 MFC 의 적격 라이더는 미리 그 한 명만. */
  String acceptedBy(String rider, String mfc) {
    String deliveryId =
        readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-a-" + UUID.randomUUID());
    engine.tick();
    String offerId =
        jdbc.queryForObject(
            "SELECT offer_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
            String.class,
            deliveryId);
    assertThat(
            body(respond(rider, offerId, "a-" + deliveryId, "ACCEPT")).path("outcome").asString())
        .isEqualTo("ASSIGNED");
    return deliveryId;
  }

  @Test
  void longDispatchWaitRaisesOneDelayAlert() {
    String mfc = "MFC-D8";
    riders(mfc);
    String deliveryId =
        readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-8-" + UUID.randomUUID());
    engine.tick(); // 좌표·MFC → 고객 도로 시간 준비
    // 약속(주문 확정 + 50분)이 30분 전에 지났다 — 예상 전달(지금 + 400초)이 약속 + 20분을 넘는다
    jdbc.update(
        "UPDATE delivery SET promised_at = ? WHERE delivery_id = ?", ago(30 * 60), deliveryId);
    engine.tick();
    JsonNode delayed = outbox(deliveryId, "DispatchDelayed").path("payload");
    assertThat(delayed.path("threshold").asInt()).isEqualTo(20);
    assertThat(delayed.path("delayedMinutes").asInt()).isGreaterThanOrEqualTo(36);
    engine.tick();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'DispatchDelayed'",
                Integer.class,
                deliveryId))
        .isEqualTo(1);
  }

  @Test
  void stopBeforePackingCancelsAndStopBeforeRequestBlocks() {
    // 배차 요청 뒤 피킹 전 중지(perPackage 비어 있음) — 전체인지 부분인지 모르므로 배송은 그대로(PC 1 재검토 ①),
    // 주문 전체 취소(OrderCancelled FULL)가 오면 취소, 부분 취소(PARTIAL)는 그대로
    String mfc = "MFC-D9";
    riders(mfc);
    String order = "ORD-" + UUID.randomUUID();
    String shipment = "SHP-" + UUID.randomUUID();
    send(
        "DispatchRequested",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "mfcId",
            mfc,
            "trigger",
            "MANUAL",
            "estimatedPackMinutes",
            5,
            "estimatedReadyAt",
            Instant.now().toString(),
            "requestedAt",
            Instant.now().toString()));
    String d1 = awaitDelivery(shipment);
    send(
        "WorkStopResult",
        order,
        Map.of(
            "orderId",
            order,
            "stopRequestId",
            "STOP-9",
            "perPackage",
            List.of(),
            "notStartedLines",
            List.of(Map.of("orderLineId", "L-1", "qty", 1)),
            "decidedAt",
            Instant.now().toString()));
    orderCancelled(order, "PARTIAL");
    awaitSql(
        "SELECT count(*) FROM inbox_event WHERE event_type = 'OrderCancelled' AND aggregate_id = '"
            + order
            + "'");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, d1))
        .isEqualTo("DISPATCHING"); // 부분 중지·부분 취소 — 남은 상품은 배차
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM stopped_order WHERE order_id = ?", Integer.class, order))
        .isZero();
    orderCancelled(order, "FULL");
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + d1
            + "' AND state = 'CANCELLED_BEFORE_PICKUP'");

    // 주문 전체 취소가 배차 요청보다 먼저 오면 → 배송은 BLOCKED, DeliveryCreated 없음
    String order2 = "ORD-" + UUID.randomUUID();
    String shipment2 = "SHP-" + UUID.randomUUID();
    orderCancelled(order2, "FULL");
    awaitSql("SELECT count(*) FROM stopped_order WHERE order_id = '" + order2 + "'");
    send(
        "DispatchRequested",
        shipment2,
        Map.of(
            "orderId",
            order2,
            "shipmentId",
            shipment2,
            "mfcId",
            mfc,
            "trigger",
            "MANUAL",
            "estimatedPackMinutes",
            5,
            "estimatedReadyAt",
            Instant.now().toString(),
            "requestedAt",
            Instant.now().toString()));
    String d2 = awaitDelivery(shipment2);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, d2))
        .isEqualTo("BLOCKED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'DeliveryCreated'",
                Integer.class,
                d2))
        .isZero();
  }

  @Test
  void handoverBeforePackageListDoesNotMarkPickedUpUntilListArrives() {
    String mfc = "MFC-D10";
    String rider = "RD-m4-" + UUID.randomUUID();
    riders(mfc, rider(rider, "AI_RECOMMENDED", true));
    riderLookup(rider(rider, "AI_RECOMMENDED", true));
    locations(Map.of(rider, new double[] {37.4985, 127.0280}));
    String order = "ORD-" + UUID.randomUUID();
    String shipment = "SHP-" + UUID.randomUUID();
    send(
        "DispatchRequested",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "mfcId",
            mfc,
            "trigger",
            "MANUAL",
            "estimatedPackMinutes",
            5,
            "estimatedReadyAt",
            Instant.now().toString(),
            "requestedAt",
            Instant.now().toString()));
    String deliveryId = awaitDelivery(shipment);
    engine.tick();
    String offer =
        jdbc.queryForObject(
            "SELECT offer_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
            String.class,
            deliveryId);
    assertThat(body(respond(rider, offer, "a-" + offer, "ACCEPT")).path("outcome").asString())
        .isEqualTo("ASSIGNED");
    // 포장 두 개 중 하나 인계 — 목록을 아직 모르니 픽업 완료 아님(M4)
    send(
        "PackageHandedOver",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "deliveryId",
            deliveryId,
            "packageId",
            "P-A-" + shipment,
            "riderId",
            rider,
            "handoverId",
            "HO-A",
            "inventoryCommitId",
            "IC-A",
            "lines",
            List.of(),
            "handedAt",
            Instant.now().toString()));
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND handed_over_packages::text LIKE '%P-A-%'");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, deliveryId))
        .isEqualTo("ASSIGNED");
    // 목록이 오면(포장 2개) 아직 하나 남아 그대로, 나머지 인계 뒤 PICKED_UP
    send(
        "ShipmentReady",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "mfcId",
            mfc,
            "readyAt",
            Instant.now().toString(),
            "packages",
            List.of(
                Map.of("packageId", "P-A-" + shipment, "bagCount", 1),
                Map.of("packageId", "P-B-" + shipment, "bagCount", 1))));
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND ready_at IS NOT NULL");
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, deliveryId))
        .isEqualTo("ASSIGNED");
    send(
        "PackageHandedOver",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "deliveryId",
            deliveryId,
            "packageId",
            "P-B-" + shipment,
            "riderId",
            rider,
            "handoverId",
            "HO-B",
            "inventoryCommitId",
            "IC-B",
            "lines",
            List.of(),
            "handedAt",
            Instant.now().toString()));
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND state = 'PICKED_UP'");

    // 운영자가 확정한 전달 실패(execution DeliveryFailed) → CLOSED(2026-11-13)
    kafka
        .send(
            "delivery.delivery.v1",
            deliveryId,
            EventEnvelopeFixtures.envelope("DeliveryFailed")
                .from("delivery-execution-service")
                .aggregate("Delivery", deliveryId, 7)
                .payload(
                    Map.of(
                        "deliveryId",
                        deliveryId,
                        "reason",
                        "CUSTOMER_ABSENT",
                        "custody",
                        "RIDER",
                        "failedAt",
                        Instant.now().toString()))
                .buildJson())
        .join();
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND state = 'CLOSED'");
  }

  @Test
  void sameRiderTwoListAcceptancesAtOnceRespectOneAtATime() throws Exception {
    String mfc = "MFC-D11";
    String rider = "RD-m1-" + UUID.randomUUID();
    Map<String, Object> r = rider(rider, "NORMAL", true);
    r.put("oneAtATime", true);
    riders(mfc, r);
    riderLookup(r);
    locations(Map.of(rider, new double[] {37.4985, 127.0280}));
    String a = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-11a-" + UUID.randomUUID());
    String b = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-11b-" + UUID.randomUUID());
    engine.tick(); // 좌표 준비
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var fa =
          pool.submit(
              () ->
                  body(
                      http.post()
                          .uri("/delivery-list/{id}/acceptances", a)
                          .headers(h -> riderHeaders(h, rider, "m1a-" + a))
                          .retrieve()
                          .toEntity(String.class)));
      var fb =
          pool.submit(
              () ->
                  body(
                      http.post()
                          .uri("/delivery-list/{id}/acceptances", b)
                          .headers(h -> riderHeaders(h, rider, "m1b-" + b))
                          .retrieve()
                          .toEntity(String.class)));
      List<String> outcomes =
          List.of(fa.get().path("outcome").asString(), fb.get().path("outcome").asString());
      // 하나씩 배달하기 — 먼저 잡은 수락의 건수를 본 뒤라 둘째는 INELIGIBLE(M1)
      assertThat(outcomes).containsExactlyInAnyOrder("ASSIGNED", "LATE");
    } finally {
      pool.shutdown();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM delivery WHERE rider_id = ? AND state = 'ASSIGNED'",
                Integer.class,
                rider))
        .isEqualTo(1);
  }

  @Test
  void onTimeDeliveryGetsNoDelayAlertAndOperatorCanCancelWaitingDelivery() {
    String mfc = "MFC-D12";
    riders(mfc);
    String deliveryId =
        readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-12-" + UUID.randomUUID());
    engine.tick();
    // 약속까지 10분 남음 — 예상 전달(지금 + 400초)이 약속 + 20분 안이라 안내 없음
    jdbc.update("UPDATE delivery SET promised_at = ? WHERE delivery_id = ?", ago(-600), deliveryId);
    engine.tick();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'DispatchDelayed'",
                Integer.class,
                deliveryId))
        .isZero();
    // 라이더는 배정 전 배송을 취소할 수 없다, 운영자(MFC 백오피스)는 재배차 없이 취소(M2)
    ResponseEntity<String> riderTry =
        cancel("RD-x-" + UUID.randomUUID(), deliveryId, "rc-" + deliveryId);
    assertThat(riderTry.getStatusCode().value()).isEqualTo(403);
    ResponseEntity<String> op =
        http.post()
            .uri("/deliveries/{id}/assignment-cancellations", deliveryId)
            .header("Authorization", "Bearer " + TestJwt.operator("OP-M", List.of(), List.of(mfc)))
            .header("Idempotency-Key", "oc-" + deliveryId)
            .header("X-Source-System", "mfc-backoffice")
            .body(
                Map.of("requestedBy", "STORE_STAFF", "reason", "STORE_REQUEST", "reassign", false))
            .retrieve()
            .toEntity(String.class);
    assertThat(op.getStatusCode().value()).isEqualTo(202);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, deliveryId))
        .isEqualTo("CANCELLED_BEFORE_PICKUP");
    assertThat(outboxCount(deliveryId, "DeliveryCancelledBeforePickup")).isEqualTo(1);

    // 점원 배차 요청 전(ShipmentReady 만 — CREATED, DeliveryCreated 를 낸 적 없음) 취소는 사건 없이(PC 1 검토 후속)
    String order = "ORD-" + UUID.randomUUID();
    String shipment = "SHP-" + UUID.randomUUID();
    send(
        "ShipmentReady",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "mfcId",
            mfc,
            "readyAt",
            Instant.now().toString(),
            "packages",
            List.of(Map.of("packageId", "PKG-12c-" + shipment, "bagCount", 1))));
    String created = awaitDelivery(shipment);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, created))
        .isEqualTo("CREATED");
    ResponseEntity<String> op2 =
        http.post()
            .uri("/deliveries/{id}/assignment-cancellations", created)
            .header("Authorization", "Bearer " + TestJwt.operator("OP-M", List.of(), List.of(mfc)))
            .header("Idempotency-Key", "oc-" + created)
            .header("X-Source-System", "mfc-backoffice")
            .body(
                Map.of("requestedBy", "STORE_STAFF", "reason", "STORE_REQUEST", "reassign", false))
            .retrieve()
            .toEntity(String.class);
    assertThat(op2.getStatusCode().value()).isEqualTo(202);
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, created))
        .isEqualTo("CANCELLED_BEFORE_PICKUP");
    assertThat(outboxCount(created, "DeliveryCreated")).isZero();
    assertThat(outboxCount(created, "DeliveryCancelledBeforePickup")).isZero();
  }

  int outboxCount(String deliveryId, String type) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = ?",
            Integer.class,
            deliveryId,
            type);
    return n == null ? 0 : n;
  }

  @Test
  void riderWithRecentCancellationsSeesNewDeliveriesLater() {
    String mfc = "MFC-D13";
    String rider = "RD-p-" + UUID.randomUUID();
    riders(mfc, rider(rider, "NORMAL", true));
    riderLookup(rider(rider, "NORMAL", true));
    locations(Map.of(rider, new double[] {37.4985, 127.0280}));
    String deliveryId =
        readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-13-" + UUID.randomUUID());
    engine.tick();
    assertThat(listIds(rider)).contains(deliveryId);
    // 최근 배차취소 3건 → 새 배송은 3분 뒤에야 보인다(제재 아님 — 알림 감소)
    for (int i = 0; i < 3; i++) {
      jdbc.update(
          "INSERT INTO assignment_cancellation (request_id, delivery_id, requested_by, actor, reason, reassign,"
              + " state, created_at) VALUES (?, ?, 'RIDER', ?, 'RIDER_UNABLE', true, 'CANCELLED_BEFORE_PICKUP', ?)",
          "pc-" + i + "-" + rider,
          "DLV-old-" + i,
          rider,
          ago(3600));
    }
    assertThat(listIds(rider)).doesNotContain(deliveryId);
    jdbc.update(
        "UPDATE dispatch_attempt SET started_at = ? WHERE delivery_id = ?", ago(200), deliveryId);
    assertThat(listIds(rider)).contains(deliveryId);
  }

  ResponseEntity<String> cancel(String rider, String deliveryId, String key) {
    return http.post()
        .uri("/deliveries/{id}/assignment-cancellations", deliveryId)
        .headers(h -> riderHeaders(h, rider, key))
        .body(Map.of("requestedBy", "RIDER", "reason", "RIDER_UNABLE"))
        .retrieve()
        .toEntity(String.class);
  }

  // ---------- 도우미 ----------

  String readyShipment(String mfc, String shipment, String packageId) {
    return readyShipment(mfc, shipment, packageId, "ORD-" + UUID.randomUUID());
  }

  String readyShipment(String mfc, String shipment, String packageId, String order) {
    send(
        "ShipmentReady",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "mfcId",
            mfc,
            "readyAt",
            Instant.now().toString(),
            "packages",
            List.of(
                Map.of("packageId", packageId, "bagCount", 1, "weightG", 1000, "alcohol", false))));
    String deliveryId = awaitDelivery(shipment);
    // 출하 대기만으로는 배차하지 않는다 — 점원 요청만(BR-51, 사용자 2026-11-13 "자동요청빼")
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM delivery WHERE delivery_id = ?", String.class, deliveryId))
        .isEqualTo("CREATED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM dispatch_attempt WHERE delivery_id = ?",
                Integer.class,
                deliveryId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'DeliveryCreated'",
                Integer.class,
                deliveryId))
        .isZero();
    send(
        "DispatchRequested",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "mfcId",
            mfc,
            "trigger",
            "MANUAL",
            "estimatedPackMinutes",
            1,
            "estimatedReadyAt",
            Instant.now().toString(),
            "requestedAt",
            Instant.now().toString()));
    awaitSql(
        "SELECT count(*) FROM delivery WHERE delivery_id = '"
            + deliveryId
            + "' AND state = 'DISPATCHING'");
    assertThat(outbox(deliveryId, "DeliveryCreated").path("payload").path("shipmentId").asString())
        .isEqualTo(shipment);
    return deliveryId;
  }

  List<String> listIds(String rider) {
    JsonNode n =
        body(
            http.get()
                .uri("/delivery-list")
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .toEntity(String.class));
    List<String> out = new ArrayList<>();
    n.path("items").forEach(i -> out.add(i.path("deliveryId").asString()));
    return out;
  }

  ResponseEntity<String> respond(String rider, String offerId, String key, String response) {
    return http.post()
        .uri("/offers/{id}/responses", offerId)
        .headers(h -> riderHeaders(h, rider, key))
        .body(Map.of("response", response))
        .retrieve()
        .toEntity(String.class);
  }

  void orderCancelled(String orderId, String scope) {
    String json =
        EventEnvelopeFixtures.envelope("OrderCancelled")
            .from("order-service")
            .aggregate("Order", orderId, scope.equals("FULL") ? 3 : 2)
            .payload(
                Map.of(
                    "orderId",
                    orderId,
                    "scope",
                    scope,
                    "lines",
                    List.of(Map.of("orderLineId", "L-1", "qty", 1)),
                    "reason",
                    "SHORTAGE",
                    "cancelledAt",
                    Instant.now().toString()))
            .buildJson();
    kafka.send("order.order.v1", orderId, json).join();
  }

  void send(String type, String key, Map<String, Object> payload) {
    String aggregateType =
        switch (type) {
          case "PackageHandedOver" -> "Package";
          case "WorkStopResult" -> "Fulfillment";
          default -> "Shipment";
        };
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from("mfc-fulfillment-service")
            .aggregate(aggregateType, key, 1)
            .payload(payload)
            .buildJson();
    kafka.send("mfc.fulfillment.v1", key, json).join();
  }

  @SafeVarargs
  final void riders(String mfc, Map<String, Object>... items) {
    up.stubFor(
        get(urlPathEqualTo("/api/v1/internal/riders/eligible"))
            .withQueryParam("mfcId", equalTo(mfc))
            .willReturn(json(Map.of("items", List.of(items)))));
  }

  @SafeVarargs
  final void riderLookup(Map<String, Object>... items) {
    for (Map<String, Object> r : items) {
      up.stubFor(
          get(urlPathEqualTo("/api/v1/internal/riders/" + r.get("riderId"))).willReturn(json(r)));
    }
  }

  void locations(Map<String, double[]> at) {
    List<Map<String, Object>> items = new ArrayList<>();
    at.forEach(
        (id, ll) ->
            items.add(
                Map.of(
                    "riderId",
                    id,
                    "lat",
                    ll[0],
                    "lng",
                    ll[1],
                    "receivedAt",
                    Instant.now().toString(),
                    "quality",
                    "GOOD")));
    for (String id : at.keySet()) {
      up.stubFor(
          get(urlPathEqualTo("/api/v1/internal/rider-locations"))
              .atPriority(1)
              .withQueryParam("riderIds", equalTo(id))
              .willReturn(
                  json(
                      Map.of(
                          "items",
                          items.stream().filter(i -> i.get("riderId").equals(id)).toList()))));
    }
    up.stubFor(
        get(urlPathEqualTo("/api/v1/internal/rider-locations"))
            .atPriority(5)
            .willReturn(json(Map.of("items", items))));
  }

  static Map<String, Object> rider(String id, String mode, boolean accepts) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("riderId", id);
    m.put("vehicleType", "MOTORCYCLE");
    m.put("dispatchMode", mode);
    m.put("oneAtATime", false);
    m.put("restriction", "NORMAL");
    m.put(
        "operating",
        Map.of("status", accepts ? "ON_DUTY" : "OFF_DUTY", "newDispatchEnabled", accepts));
    return m;
  }

  static Map<String, Object> matrix(List<List<Integer>> rows) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (List<Integer> r : rows) {
      out.add(
          Map.of(
              "elements",
              List.of(Map.of("status", "OK", "distanceM", r.get(0), "durationSeconds", r.get(1)))));
    }
    return Map.of("provider", "KAKAO_MOBILITY", "fallback", false, "rows", out);
  }

  String awaitDelivery(String shipment) {
    awaitSql("SELECT count(*) FROM delivery WHERE shipment_id = '" + shipment + "'");
    return jdbc.queryForObject(
        "SELECT delivery_id FROM delivery WHERE shipment_id = ?", String.class, shipment);
  }

  void awaitSql(String countSql) {
    long until = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < until) {
      Integer n = jdbc.queryForObject(countSql, Integer.class);
      if (n != null && n > 0) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError("기다린 상태가 오지 않음: " + countSql);
  }

  JsonNode outbox(String deliveryId, String type) {
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = ? ORDER BY created_at DESC LIMIT 1",
            String.class,
            deliveryId,
            type);
    return mapper.readTree(payload);
  }

  static void riderHeaders(org.springframework.http.HttpHeaders h, String rider, String key) {
    h.setBearerAuth(TestJwt.rider(rider));
    h.set("Idempotency-Key", key);
    h.set("X-Source-System", "rider-app");
  }

  JsonNode body(ResponseEntity<String> r) {
    return mapper.readTree(r.getBody() == null ? "{}" : r.getBody());
  }

  /** 시험의 시각은 JVM 시계로(Docker DB 시계와 어긋날 수 있어 DB now() 를 쓰지 않는다). */
  static java.sql.Timestamp ago(long seconds) {
    return java.sql.Timestamp.from(Instant.now().minusSeconds(seconds));
  }

  ResponseDefinitionBuilder json(Object body) {
    return aResponse()
        .withHeader("Content-Type", "application/json")
        .withBody(mapper.writeValueAsString(body));
  }
}
