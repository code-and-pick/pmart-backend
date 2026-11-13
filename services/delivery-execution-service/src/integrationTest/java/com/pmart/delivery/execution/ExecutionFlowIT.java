package com.pmart.delivery.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.delivery.execution.application.EvidencePhotoStorage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 배송 수행 0.2.0(PC 1 검토 H2): 배정 사건 → 가게 도착 → 인계 사실(PackageHandedOver)로만 픽업 완료(포장 목록을 모르면 보류, DE-1) →
 * 고객 도착 → 증빙 사진 → 전달 완료(DeliveryCompleted, 인계 줄 합계). 배정 해제·재배정·픽업 전 취소. 실제 PostgreSQL·Kafka, 사진
 * 저장소만 메모리(S3 어댑터는 customer-service 와 같은 방식).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
@Import({TestJwtConfiguration.class, ExecutionFlowIT.MemoryPhotos.class})
class ExecutionFlowIT extends PostgresKafkaContainerSupport {

  static final Map<String, byte[]> STORED = new ConcurrentHashMap<>();

  @TestConfiguration
  static class MemoryPhotos {
    @Bean
    EvidencePhotoStorage evidencePhotoStorage() {
      return (key, bytes, type) -> STORED.put(key, bytes);
    }
  }

  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F'};

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  @Test
  void pickupOnlyAfterAllPackagesHandedOverThenCompleteWithPhoto() {
    String id = "DLV-" + UUID.randomUUID();
    String shipment = "SHP-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    String rider = "RDR-" + UUID.randomUUID();
    created(id, shipment, order);
    assigned(id, order, rider, 1);
    awaitState(id, "ASSIGNED");

    assertThat(post(id, "store-arrivals", "RDR-OTHER", Map.of()).getStatusCode().value())
        .isEqualTo(403);
    ResponseEntity<String> arrived = post(id, "store-arrivals", rider, Map.of());
    assertThat(arrived.getStatusCode().value()).isEqualTo(200);
    assertThat(body(arrived).path("status").asString()).isEqualTo("APPLIED");
    assertThat(outbox(id, "RiderArrivedAtStore").path("payload").path("riderId").asString())
        .isEqualTo(rider);

    // 포장 목록을 모를 때 온 첫 인계 — 픽업 완료 아님(M4 와 같은 규칙)
    handedOver(id, shipment, order, rider, "PKG-1", "HO-1", Map.of("L-1", 2));
    awaitSql("SELECT count(*) FROM package_handover WHERE handover_id = 'HO-1'");
    ResponseEntity<String> early =
        post(id, "pickups", rider, Map.of("handoverIds", List.of("HO-1")));
    assertThat(early.getStatusCode().value()).isEqualTo(422);
    assertThat(body(early).path("code").asString()).isEqualTo("HANDOVER_NOT_CONFIRMED");

    // 목록이 오면(포장 2개) 아직 하나 남음 → 두 번째 인계에서 픽업 완료
    send(
        "mfc.fulfillment.v1",
        "ShipmentReady",
        "Shipment",
        shipment,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "mfcId",
            "MFC-1",
            "readyAt",
            Instant.now().toString(),
            "packages",
            List.of(Map.of("packageId", "PKG-1"), Map.of("packageId", "PKG-2"))));
    awaitSql("SELECT count(*) FROM shipment_package WHERE shipment_id = '" + shipment + "'");
    assertThat(state(id)).isEqualTo("ARRIVED_AT_STORE");
    handedOver(id, shipment, order, rider, "PKG-2", "HO-2", Map.of("L-1", 1, "L-2", 3));
    awaitState(id, "PICKED_UP");
    JsonNode picked = outbox(id, "PickupCompleted").path("payload");
    assertThat(picked.path("packageIds").size()).isEqualTo(2);
    assertThat(picked.path("handoverIds").toString()).contains("HO-1", "HO-2");
    assertThat(
            post(id, "pickups", rider, Map.of("handoverIds", List.of("HO-1", "HO-2")))
                .getStatusCode()
                .value())
        .isEqualTo(200);

    // 고객 도착 전 완료 → 409, 잘못된 사진 → 422, 사진 올리기 → 201
    String takenAt = Instant.now().toString();
    assertThat(
            post(id, "completions", rider, completion(List.of("EVP-NONE"), takenAt))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    ResponseEntity<String> badPhoto = upload(id, rider, "x".getBytes());
    assertThat(badPhoto.getStatusCode().value()).isEqualTo(422);
    assertThat(body(badPhoto).path("code").asString()).isEqualTo("PHOTO_INVALID");
    ResponseEntity<String> photo = upload(id, rider, JPEG);
    assertThat(photo.getStatusCode().value()).isEqualTo(201);
    String photoRef = body(photo).path("photoRef").asString();
    assertThat(body(photo).path("contentType").asString()).isEqualTo("image/jpeg");
    assertThat(STORED).containsKey("deliveries/" + id + "/" + photoRef);
    assertThat(upload(id, "RDR-OTHER", JPEG).getStatusCode().value()).isEqualTo(403);

    assertThat(post(id, "customer-arrivals", rider, Map.of()).getStatusCode().value())
        .isEqualTo(200);
    ResponseEntity<String> noPhoto =
        post(id, "completions", rider, completion(List.of("EVP-NONE"), takenAt));
    assertThat(noPhoto.getStatusCode().value()).isEqualTo(422);
    assertThat(body(noPhoto).path("code").asString()).isEqualTo("EVIDENCE_REQUIRED");
    ResponseEntity<String> done =
        post(id, "completions", rider, completion(List.of(photoRef), takenAt));
    assertThat(done.getStatusCode().value()).isEqualTo(200);
    assertThat(state(id)).isEqualTo("COMPLETED");

    JsonNode completed = outbox(id, "DeliveryCompleted").path("payload");
    assertThat(completed.path("orderId").asString()).isEqualTo(order);
    assertThat(completed.path("method").asString()).isEqualTo("CONTACTLESS");
    assertThat(completed.path("evidenceRef").path("kind").asString()).isEqualTo("PHOTO");
    Map<String, Integer> lines = new java.util.HashMap<>();
    for (JsonNode l : completed.path("lines")) {
      lines.put(l.path("orderLineId").asString(), l.path("qty").asInt());
    }
    assertThat(lines).containsExactlyInAnyOrderEntriesOf(Map.of("L-1", 3, "L-2", 3));

    JsonNode view =
        body(
            http.get()
                .uri("/deliveries/{id}", id)
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .toEntity(String.class));
    assertThat(view.path("state").asString()).isEqualTo("COMPLETED");
    assertThat(view.path("evidence").path("photoRefs").get(0).asString()).isEqualTo(photoRef);
    assertThat(view.path("history").size()).isEqualTo(5); // 배정·가게·픽업·고객·완료
    assertThat(
            http.get()
                .uri("/deliveries/{id}", id)
                .header("Authorization", "Bearer " + TestJwt.rider("RDR-OTHER"))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    JsonNode mine =
        body(
            http.get()
                .uri("/riders/me/deliveries?state=COMPLETED")
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .toEntity(String.class));
    assertThat(mine.path("items").size()).isEqualTo(1);
  }

  @Test
  void unassignReassignAndCancelBeforePickup() {
    String id = "DLV-" + UUID.randomUUID();
    String shipment = "SHP-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    created(id, shipment, order);
    assigned(id, order, "RDR-A", 1);
    awaitState(id, "ASSIGNED");
    assertThat(post(id, "store-arrivals", "RDR-A", Map.of()).getStatusCode().value())
        .isEqualTo(200);

    // 배차 취소(재배차) → 다시 배차 대기, 옛 배정 사건(낮은 버전)은 버림, 새 라이더 배정
    send(
        "delivery.delivery.v1",
        "DeliveryAssignmentChanged",
        "Delivery",
        id,
        Map.of(
            "deliveryId",
            id,
            "oldRiderId",
            "RDR-A",
            "assignmentVersion",
            2,
            "reason",
            "CANCELLED_RIDER",
            "changedAt",
            Instant.now().toString()));
    awaitState(id, "UNASSIGNED");
    assigned(id, order, "RDR-A", 1);
    assigned(id, order, "RDR-B", 3);
    awaitSql(
        "SELECT count(*) FROM execution_delivery WHERE delivery_id = '"
            + id
            + "' AND rider_id = 'RDR-B'");
    assertThat(state(id)).isEqualTo("ASSIGNED");
    assertThat(post(id, "store-arrivals", "RDR-A", Map.of()).getStatusCode().value())
        .isEqualTo(403);

    send(
        "delivery.delivery.v1",
        "DeliveryCancelledBeforePickup",
        "Delivery",
        id,
        Map.of("deliveryId", id, "orderId", order, "reason", "ORDER_CANCELLED"));
    awaitState(id, "CANCELLED");
    assertThat(post(id, "store-arrivals", "RDR-B", Map.of()).getStatusCode().value())
        .isEqualTo(409); // 취소된 배송 — 상태 전이 불가
  }

  // ---- 도우미

  void created(String id, String shipment, String order) {
    send(
        "delivery.delivery.v1",
        "DeliveryCreated",
        "Delivery",
        id,
        Map.of(
            "deliveryId",
            id,
            "orderId",
            order,
            "shipmentId",
            shipment,
            "type",
            "FORWARD",
            "mfcId",
            "MFC-1",
            "dropAddressRef",
            "order:" + order));
  }

  void assigned(String id, String order, String rider, int version) {
    send(
        "delivery.delivery.v1",
        "DeliveryAssigned",
        "Delivery",
        id,
        Map.of(
            "deliveryId",
            id,
            "orderId",
            order,
            "riderId",
            rider,
            "assignmentVersion",
            version,
            "acceptedAt",
            Instant.now().toString()));
  }

  void handedOver(
      String id,
      String shipment,
      String order,
      String rider,
      String pkg,
      String handoverId,
      Map<String, Integer> lines) {
    send(
        "mfc.fulfillment.v1",
        "PackageHandedOver",
        "Package",
        pkg,
        Map.of(
            "orderId",
            order,
            "shipmentId",
            shipment,
            "deliveryId",
            id,
            "packageId",
            pkg,
            "riderId",
            rider,
            "handoverId",
            handoverId,
            "inventoryCommitId",
            "IC-" + handoverId,
            "lines",
            lines.entrySet().stream()
                .map(e -> Map.of("orderLineId", e.getKey(), "sku", "SKU-1", "qty", e.getValue()))
                .toList(),
            "handedAt",
            Instant.now().toString()));
  }

  void send(String topic, String type, String aggregateType, String key, Map<String, Object> p) {
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from(topic.startsWith("mfc") ? "mfc-fulfillment-service" : "delivery-dispatch-service")
            .aggregate(aggregateType, key, 1)
            .payload(p)
            .buildJson();
    kafka.send(topic, key, json).join();
  }

  ResponseEntity<String> post(String id, String action, String rider, Object body) {
    return http.post()
        .uri("/deliveries/{id}/" + action, id)
        .headers(h -> riderHeaders(h, rider))
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  ResponseEntity<String> upload(String id, String rider, byte[] bytes) {
    LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "file",
        new ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "photo.jpg";
          }
        });
    return http.post()
        .uri("/deliveries/{id}/evidence-photos", id)
        .headers(h -> riderHeaders(h, rider))
        .contentType(MediaType.MULTIPART_FORM_DATA)
        .body(form)
        .retrieve()
        .toEntity(String.class);
  }

  static Map<String, Object> completion(List<String> photoRefs, String takenAt) {
    return Map.of(
        "method",
        "CONTACTLESS",
        "evidence",
        Map.of("photoRefs", photoRefs, "takenAt", takenAt, "placement", "FRONT_DOOR"));
  }

  static void riderHeaders(HttpHeaders h, String rider) {
    h.setBearerAuth(TestJwt.rider(rider));
    h.set("Idempotency-Key", UUID.randomUUID().toString());
    h.set("X-Source-System", "rider-app");
  }

  String state(String id) {
    return jdbc.queryForObject(
        "SELECT state FROM execution_delivery WHERE delivery_id = ?", String.class, id);
  }

  void awaitState(String id, String state) {
    awaitSql(
        "SELECT count(*) FROM execution_delivery WHERE delivery_id = '"
            + id
            + "' AND state = '"
            + state
            + "'");
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

  JsonNode outbox(String id, String type) {
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = ? ORDER BY created_at DESC LIMIT 1",
            String.class,
            id,
            type);
    return mapper.readTree(payload);
  }

  JsonNode body(ResponseEntity<String> r) {
    return mapper.readTree(r.getBody() == null ? "{}" : r.getBody());
  }
}
