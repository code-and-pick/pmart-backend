package com.pmart.delivery.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.delivery.execution.application.EarningService;
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
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
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
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.execution.confirm-interval=PT1H", // 확정 판단은 시험이 직접 부른다
      "pmart.execution.photo-sweep-interval=PT1H" // 사진 정리도 시험이 직접 부른다
    })
@Import({TestJwtConfiguration.class, ExecutionFlowIT.MemoryPhotos.class})
@EnableWireMock(
    @ConfigureWireMock(name = "order", baseUrlProperties = "pmart.execution.urls.order"))
class ExecutionFlowIT extends PostgresKafkaContainerSupport {

  static final Map<String, byte[]> STORED = new ConcurrentHashMap<>();

  @TestConfiguration
  static class MemoryPhotos {
    @Bean
    EvidencePhotoStorage evidencePhotoStorage() {
      return new EvidencePhotoStorage() {
        @Override
        public void put(String key, byte[] bytes, String type) {
          STORED.put(key, bytes);
        }

        @Override
        public java.util.Optional<byte[]> get(String key) {
          return java.util.Optional.ofNullable(STORED.get(key));
        }

        @Override
        public boolean delete(String key) {
          STORED.remove(key);
          return true;
        }
      };
    }
  }

  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F'};

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired EarningService earnings;
  @Autowired com.pmart.delivery.execution.application.PhotoSweeper sweeper;

  @InjectWireMock("order")
  WireMockServer orderApi;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  /** BR-24(사용자 2026-11-14): 배정 라이더에게 픽업 뒤 ~ 전달 완료 전만, 고객 전화번호 그대로, 저장 안 함(no-store). */
  @Test
  void dropInfoOnlyForAssignedRiderBetweenPickupAndCompletion() {
    String id = "DLV-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    String rider = "RDR-" + UUID.randomUUID();
    created(id, "SHP-" + UUID.randomUUID(), order);
    assigned(id, order, rider, 1);
    awaitState(id, "ASSIGNED");
    Map<String, Object> drop = new java.util.LinkedHashMap<>();
    drop.put("orderId", order);
    drop.put("roadAddress", "서울 강남구 테헤란로 1");
    drop.put("detailAddress", "101동 1001호");
    drop.put("receiverName", "김고객");
    drop.put("receiverPhone", "01012345678");
    drop.put("entranceMethod", "PASSWORD");
    drop.put("entranceNote", "#1234");
    drop.put("deliveryNote", "문 앞에 두세요");
    drop.put("geo", Map.of("lat", 37.5, "lng", 127.03));
    orderApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/api/v1/internal/orders/" + order + "/drop-info"))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(drop))));

    // 픽업 전 409
    ResponseEntity<String> early = dropInfo(id, rider);
    assertThat(early.getStatusCode().value()).isEqualTo(409);
    assertThat(body(early).path("code").asString()).isEqualTo("DROP_INFO_CLOSED");

    jdbc.update("UPDATE execution_delivery SET state = 'PICKED_UP' WHERE delivery_id = ?", id);
    ResponseEntity<String> ok = dropInfo(id, rider);
    assertThat(ok.getStatusCode().value()).isEqualTo(200);
    assertThat(ok.getHeaders().getCacheControl()).contains("no-store");
    JsonNode d = body(ok);
    assertThat(d.path("receiverPhone").asString()).isEqualTo("01012345678"); // 안심번호 아님
    assertThat(d.path("detailAddress").asString()).isEqualTo("101동 1001호");
    assertThat(d.path("entranceMethod").asString()).isEqualTo("PASSWORD");
    assertThat(d.path("deliveryNote").asString()).isEqualTo("문 앞에 두세요");
    assertThat(d.path("lat").asDouble()).isEqualTo(37.5);
    // 다른 라이더는 있는지도 모른다(404), 운영자 토큰은 403
    assertThat(dropInfo(id, "RDR-OTHER").getStatusCode().value()).isEqualTo(404);
    assertThat(
            http.get()
                .uri("/deliveries/{id}/drop-info", id)
                .header(
                    "Authorization",
                    "Bearer " + TestJwt.operator("OP-1", List.of("OPERATOR_ADMIN"), List.of()))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);

    // 실패 등록(확정 전)은 아직 진행 중 — 열림, 전달 완료 뒤 닫힘
    jdbc.update(
        "UPDATE execution_delivery SET state = 'FAILURE_REPORTED' WHERE delivery_id = ?", id);
    assertThat(dropInfo(id, rider).getStatusCode().value()).isEqualTo(200);
    jdbc.update("UPDATE execution_delivery SET state = 'COMPLETED' WHERE delivery_id = ?", id);
    assertThat(dropInfo(id, rider).getStatusCode().value()).isEqualTo(409);

    // 주문 당시 사본 없는 주문 → 404 DROP_INFO_NOT_FOUND, order 장애 → 503
    jdbc.update("UPDATE execution_delivery SET state = 'PICKED_UP' WHERE delivery_id = ?", id);
    orderApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/api/v1/internal/orders/" + order + "/drop-info"))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(404)));
    ResponseEntity<String> none = dropInfo(id, rider);
    assertThat(none.getStatusCode().value()).isEqualTo(404);
    assertThat(body(none).path("code").asString()).isEqualTo("DROP_INFO_NOT_FOUND");
    orderApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/api/v1/internal/orders/" + order + "/drop-info"))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(503)));
    assertThat(dropInfo(id, rider).getStatusCode().value()).isEqualTo(503);
  }

  ResponseEntity<String> dropInfo(String id, String rider) {
    return http.get()
        .uri("/deliveries/{id}/drop-info", id)
        .header("Authorization", "Bearer " + TestJwt.rider(rider))
        .retrieve()
        .toEntity(String.class);
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
    // 같은 포장을 다시 인계(재인계) — 완료 수량은 포장마다 마지막 인계만(두 번 세지 않는다, PC 1 재검토 ③)
    handedOver(id, shipment, order, rider, "PKG-2", "HO-2B", Map.of("L-1", 1, "L-2", 3));
    awaitSql("SELECT count(*) FROM package_handover WHERE handover_id = 'HO-2B'");
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
    // 저장소 올리기는 트랜잭션 밖(PC 1 검토 후속): 같은 키 재시도 → 같은 사진 참조·객체 하나
    String key = "photo-" + UUID.randomUUID();
    String again = body(upload(id, rider, JPEG, key)).path("photoRef").asString();
    assertThat(body(upload(id, rider, JPEG, key)).path("photoRef").asString()).isEqualTo(again);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM photo_upload WHERE delivery_id = ?", Integer.class, id))
        .isZero(); // 참조를 저장한 트랜잭션이 올리는 중 기록을 지웠다
    // 중복 요청 기록이 없어진 뒤 같은 요청을 다시 보내도 409 를 반복하지 않고 그 사진을 돌려준다(#12 후속)
    jdbc.update("DELETE FROM request_record WHERE request_id = ?", key);
    ResponseEntity<String> afterPurge = upload(id, rider, JPEG, key);
    assertThat(afterPurge.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(body(afterPurge).path("photoRef").asString()).isEqualTo(again);
    // 같은 키·다른 사진 → 409. 올린 객체는 바로 지우지 않고(동시 중복 요청이 같은 객체로 성공했을 수 있다) 5시간 뒤 참조가 없으면 지운다
    // (사용자 2026-11-14 "5시간이 지나면 삭제")
    byte[] other = java.util.Arrays.copyOf(JPEG, JPEG.length + 1);
    long before = STORED.keySet().stream().filter(k -> k.startsWith("deliveries/" + id)).count();
    assertThat(upload(id, rider, other, key).getStatusCode().value()).isEqualTo(409);
    assertThat(STORED.keySet().stream().filter(k -> k.startsWith("deliveries/" + id)).count())
        .isEqualTo(before + 1);
    sweeper.sweep(); // 아직 5시간 안 — 그대로
    assertThat(STORED.keySet().stream().filter(k -> k.startsWith("deliveries/" + id)).count())
        .isEqualTo(before + 1);
    jdbc.update(
        "UPDATE photo_upload SET started_at = started_at - interval '6 hours' WHERE delivery_id = ?",
        id);
    sweeper.sweep();
    assertThat(STORED.keySet().stream().filter(k -> k.startsWith("deliveries/" + id)).count())
        .isEqualTo(before);
    assertThat(STORED).containsKey("deliveries/" + id + "/" + again); // 참조 있는 사진은 남는다
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM photo_upload WHERE delivery_id = ?", Integer.class, id))
        .isZero();

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

    // 수행료 — 수락 당시 금액(DeliveryAssigned.fee)으로 확정 대기, 5시간 뒤 확정(시험은 확정 시각을 당겨 직접 판단)
    Map<String, Object> earning =
        jdbc.queryForMap(
            "SELECT calculable, total, settlement_state, payout_date FROM rider_earning WHERE delivery_id = ?",
            id);
    assertThat(earning.get("calculable")).isEqualTo(true);
    assertThat(earning.get("total")).isEqualTo(3900);
    assertThat(earning.get("settlement_state")).isEqualTo("PENDING_CONFIRMATION");
    assertThat(earning.get("payout_date")).isNotNull();
    assertThat(outbox(id, "RiderEarningRecorded").path("payload").path("status").asString())
        .isEqualTo("PENDING_CONFIRMATION");
    jdbc.update(
        "UPDATE rider_earning SET confirm_at = ? WHERE delivery_id = ?",
        java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
        id);
    earnings.confirmDue();
    JsonNode myEarnings =
        body(
            http.get()
                .uri("/riders/me/earnings")
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .toEntity(String.class));
    assertThat(myEarnings.path("items").get(0).path("settlementState").asString())
        .isEqualTo("CONFIRMED");
    assertThat(myEarnings.path("items").get(0).path("total").asInt()).isEqualTo(3900);
    assertThat(myEarnings.path("totals").path("confirmed").asInt()).isEqualTo(3900);
    assertThat(view(id, rider).path("earning").path("settlementState").asString())
        .isEqualTo("CONFIRMED");
    // 배송 사건마다 aggregateVersion 이 다르다(수행료 사건 포함)
    assertThat(
            jdbc.queryForList(
                "SELECT (payload->>'aggregateVersion')::bigint FROM outbox_event WHERE aggregateid = ?",
                Long.class,
                id))
        .doesNotHaveDuplicates();
  }

  @Test
  void failureReportedStaysInProgressUntilOperatorDecides() {
    String id = "DLV-" + UUID.randomUUID();
    String rider = "RDR-" + UUID.randomUUID();
    pickedUp(id, "SHP-" + UUID.randomUUID(), "ORD-" + UUID.randomUUID(), rider);
    String photo = body(upload(id, rider, JPEG)).path("photoRef").asString();

    // 고객 사유인데 연락 1회 → 422, 남의(없는) 사진 → 422
    ResponseEntity<String> oneCall =
        post(id, "failures", rider, failure("CUSTOMER_ABSENT", List.of(photo), 1));
    assertThat(oneCall.getStatusCode().value()).isEqualTo(422);
    assertThat(body(oneCall).path("code").asString()).isEqualTo("CONTACT_ATTEMPTS_REQUIRED");
    ResponseEntity<String> noPhoto =
        post(id, "failures", rider, failure("CUSTOMER_ABSENT", List.of("EVP-NONE"), 2));
    assertThat(body(noPhoto).path("code").asString()).isEqualTo("EVIDENCE_REQUIRED");

    // 등록 → FAILURE_REPORTED(접수 — 아직 진행 중), 지원센터로 갈 사건
    ResponseEntity<String> reported =
        post(id, "failures", rider, failure("CUSTOMER_ABSENT", List.of(photo), 2));
    assertThat(reported.getStatusCode().value()).isEqualTo(200);
    assertThat(body(reported).path("status").asString()).isEqualTo("ACCEPTED");
    assertThat(state(id)).isEqualTo("FAILURE_REPORTED");
    JsonNode incident = outbox(id, "DeliveryIncidentRaised").path("payload");
    assertThat(incident.path("category").asString()).isEqualTo("DELIVERY_FAILURE");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'DeliveryFailed'",
                Integer.class,
                id))
        .isZero();
    // 다른 센터 관리자 403, 같은 센터 작업자 403(관리자만 — 2026-11-14), 반려 → 원래 상태(고객 도착 전이면 PICKED_UP)로
    assertThat(decide(id, "OP-X", List.of("MFC-OTHER"), "REJECT").getStatusCode().value())
        .isEqualTo(403);
    assertThat(
            decide(id, "OP-W", List.of("MFC_WORKER"), List.of("MFC-1"), "REJECT")
                .getStatusCode()
                .value())
        .isEqualTo(403);
    assertThat(decide(id, "OP-1", List.of("MFC-1"), "REJECT").getStatusCode().value())
        .isEqualTo(200);
    assertThat(state(id)).isEqualTo("PICKED_UP");

    // 다시 등록(고객 도착 뒤) → 확정 → FAILED + DeliveryFailed, 수행료 산정 불가
    assertThat(post(id, "customer-arrivals", rider, Map.of()).getStatusCode().value())
        .isEqualTo(200);
    assertThat(
            post(id, "failures", rider, failure("UNREACHABLE", List.of(photo), 2))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(
            post(id, "completions", rider, completion(List.of(photo), Instant.now().toString()))
                .getStatusCode()
                .value())
        .isEqualTo(409); // 확정 전에는 완료도 안 된다
    assertThat(decide(id, "OP-1", List.of("MFC-1"), "CONFIRM").getStatusCode().value())
        .isEqualTo(200);
    assertThat(state(id)).isEqualTo("FAILED");
    JsonNode failed = outbox(id, "DeliveryFailed").path("payload");
    assertThat(failed.path("reason").asString()).isEqualTo("UNREACHABLE");
    assertThat(failed.path("custody").asString()).isEqualTo("RIDER");
    assertThat(
            jdbc.queryForObject(
                "SELECT calculable FROM rider_earning WHERE delivery_id = ?", Boolean.class, id))
        .isFalse();
    JsonNode v = view(id, rider);
    assertThat(v.path("failure").path("decision").asString()).isEqualTo("CONFIRM");
    assertThat(v.path("failure").path("contactAttempts").asInt()).isEqualTo(2);
    assertThat(decide(id, "OP-1", List.of("MFC-1"), "CONFIRM").getStatusCode().value())
        .isEqualTo(409); // 이미 끝남
  }

  @Test
  void helpRequestIsRecordedAndRaisedForSupportCenter() {
    String id = "DLV-" + UUID.randomUUID();
    String rider = "RDR-" + UUID.randomUUID();
    pickedUp(id, "SHP-" + UUID.randomUUID(), "ORD-" + UUID.randomUUID(), rider);
    assertThat(
            post(id, "help-requests", "RDR-OTHER", Map.of("category", "ACCIDENT"))
                .getStatusCode()
                .value())
        .isEqualTo(403);
    ResponseEntity<String> created =
        post(
            id,
            "help-requests",
            rider,
            Map.of("category", "STORE_WAITING", "detail", "포장이 아직 안 나옴"));
    assertThat(created.getStatusCode().value()).isEqualTo(202);
    String requestId = body(created).path("resultId").asString();
    JsonNode raised = outbox(id, "DeliveryIncidentRaised").path("payload");
    assertThat(raised.path("type").asString()).isEqualTo("HELP_REQUEST");
    assertThat(raised.path("category").asString()).isEqualTo("STORE_WAITING");
    assertThat(raised.path("stage").asString()).isEqualTo("PICKED_UP");
    assertThat(raised.path("incidentId").asString())
        .isEqualTo(id + ":" + requestId); // 요청 ID(앱 Idempotency-Key)는 배송마다만 유일 — 배송 ID 를 붙인다

    JsonNode mine =
        body(
            http.get()
                .uri("/deliveries/{id}/help-requests/{r}", id, requestId)
                .header("Authorization", "Bearer " + TestJwt.rider(rider))
                .retrieve()
                .toEntity(String.class));
    assertThat(mine.path("state").asString()).isEqualTo("OPEN");
    assertThat(mine.path("caseId").isNull() || mine.path("caseId").isMissingNode()).isTrue();
    assertThat(
            http.get()
                .uri("/deliveries/{id}/help-requests/{r}", id, requestId)
                .header("Authorization", "Bearer " + TestJwt.rider("RDR-OTHER"))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(404);
    assertThat(
            http.get()
                .uri("/deliveries/{id}/help-requests/{r}", id, requestId)
                .header(
                    "Authorization",
                    "Bearer " + TestJwt.operator("OP-CS", List.of("CS_AGENT"), List.of()))
                .retrieve()
                .toEntity(String.class)
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(view(id, rider).path("incidents").get(0).path("category").asString())
        .isEqualTo("STORE_WAITING");
  }

  /** 0.4.0 백오피스 직원 목록(BOD-4·5): 배송 찾기·도움요청 대기 목록·처리함 닫기, RIDER_OPS 권한(BOD-1). */
  @Test
  void staffFindsDeliveriesAndHelpQueueAndResolvesHelpRequest() {
    String id = "DLV-" + UUID.randomUUID();
    String rider = "RDR-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    pickedUp(id, "SHP-" + UUID.randomUUID(), order, rider);
    String requestId =
        body(post(
                id,
                "help-requests",
                rider,
                Map.of("category", "ADDRESS_ISSUE", "detail", "동 호수가 안 보임")))
            .path("resultId")
            .asString();
    String ops = TestJwt.operator("OP-RO", List.of("RIDER_OPS"), List.of());
    String worker = TestJwt.operator("OP-W", List.of("MFC_WORKER"), List.of("MFC-1"));

    // 배송 찾기 — 주문 ID 로(상담원), 라이더는 403, 센터 직원은 mfcId 를 줘야
    JsonNode found =
        body(
            staffGet(
                "/deliveries?orderId=" + order,
                TestJwt.operator("OP-CS", List.of("CS_AGENT"), List.of())));
    assertThat(found.path("items")).hasSize(1);
    assertThat(found.path("items").get(0).path("deliveryId").asString()).isEqualTo(id);
    assertThat(found.path("items").get(0).path("state").asString()).isEqualTo("PICKED_UP");
    assertThat(found.path("items").get(0).path("riderId").asString()).isEqualTo(rider);
    assertThat(
            staffGet("/deliveries?orderId=" + order, TestJwt.rider(rider)).getStatusCode().value())
        .isEqualTo(403);
    assertThat(staffGet("/deliveries?orderId=" + order, worker).getStatusCode().value())
        .isEqualTo(403);
    assertThat(body(staffGet("/deliveries?mfcId=MFC-1&orderId=" + order, worker)).path("items"))
        .hasSize(1);
    assertThat(staffGet("/deliveries?mfcId=MFC-2&orderId=" + order, worker).getStatusCode().value())
        .isEqualTo(403);
    assertThat(staffGet("/deliveries?sort=SIDEWAYS", ops).getStatusCode().value()).isEqualTo(400);

    // 도움요청 대기 목록 — 라이더 글(detail)은 목록에 없다
    JsonNode queue = body(staffGet("/delivery-help-requests?riderId=" + rider, ops));
    assertThat(queue.path("items")).hasSize(1);
    JsonNode row = queue.path("items").get(0);
    assertThat(row.path("incidentId").asString()).isEqualTo(id + ":" + requestId);
    assertThat(row.path("orderId").asString()).isEqualTo(order);
    assertThat(row.path("mfcId").asString()).isEqualTo("MFC-1");
    assertThat(row.path("state").asString()).isEqualTo("OPEN");
    assertThat(row.has("detail")).isFalse();
    assertThat(staffGet("/delivery-help-requests?riderId=" + rider, worker).getStatusCode().value())
        .isEqualTo(403);
    assertThat(staffGet("/delivery-help-requests?state=DONE", ops).getStatusCode().value())
        .isEqualTo(400);

    // 단건 — 라이더도 자기 글은 본다
    JsonNode mine =
        body(staffGet("/deliveries/" + id + "/help-requests/" + requestId, TestJwt.rider(rider)));
    assertThat(mine.path("detail").asString()).isEqualTo("동 호수가 안 보임");

    // 닫기 — 같은 센터 작업자는 403, 라이더 운영은 닫는다, 같은 키 재전송은 같은 결과, 새 키는 409
    assertThat(resolve(id, requestId, worker, "K-" + UUID.randomUUID()).getStatusCode().value())
        .isEqualTo(403);
    String key = "K-" + UUID.randomUUID();
    ResponseEntity<String> closed = resolve(id, requestId, ops, key);
    assertThat(closed.getStatusCode().value()).isEqualTo(200);
    assertThat(body(closed).path("state").asString()).isEqualTo("RESOLVED");
    assertThat(body(closed).path("resolvedBy").asString()).isEqualTo("OP-RO");
    assertThat(body(closed).path("resolutionNote").asString()).isEqualTo("고객과 통화해 위치 안내");
    assertThat(resolve(id, requestId, ops, key).getStatusCode().value()).isEqualTo(200);
    assertThat(resolve(id, requestId, ops, "K-" + UUID.randomUUID()).getStatusCode().value())
        .isEqualTo(409);
    assertThat(body(staffGet("/delivery-help-requests?riderId=" + rider, ops)).path("items"))
        .isEmpty();
    assertThat(
            body(staffGet("/delivery-help-requests?state=RESOLVED&riderId=" + rider, ops))
                .path("items"))
        .hasSize(1);

    // 라이더에게는 닫힌 것만 — 누가·메모는 숨긴다
    JsonNode after =
        body(staffGet("/deliveries/" + id + "/help-requests/" + requestId, TestJwt.rider(rider)));
    assertThat(after.path("state").asString()).isEqualTo("RESOLVED");
    assertThat(after.path("resolvedAt").isNull()).isFalse();
    assertThat(after.path("resolvedBy").isNull() || after.path("resolvedBy").isMissingNode())
        .isTrue();
    assertThat(
            after.path("resolutionNote").isNull() || after.path("resolutionNote").isMissingNode())
        .isTrue();

    // 라이더 운영은 배송 상세도 본다(BOD-1)
    assertThat(staffGet("/deliveries/" + id, ops).getStatusCode().value()).isEqualTo(200);
  }

  ResponseEntity<String> staffGet(String uri, String token) {
    return http.get()
        .uri(uri)
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toEntity(String.class);
  }

  /** DE-CASE(2026-11-16): 사건함이 도움요청으로 사건을 열면 도움요청에 사건 번호·처리 중, 그동안 옛 닫기 API 는 409, 사건을 닫으면 처리됨. */
  @Test
  void supportCaseOpenAndCloseFollowHelpRequest() throws Exception {
    String id = "DLV-" + UUID.randomUUID();
    String rider = "RDR-" + UUID.randomUUID();
    pickedUp(id, "SHP-" + UUID.randomUUID(), "ORD-" + UUID.randomUUID(), rider);
    String requestId =
        body(post(id, "help-requests", rider, Map.of("category", "CUSTOMER_UNREACHABLE")))
            .path("resultId")
            .asString();
    String ops = TestJwt.operator("OP-RO", List.of("RIDER_OPS"), List.of());
    String caseId = "CASE-" + UUID.randomUUID();
    kafka
        .send(
            "platform.case.v1",
            caseId,
            EventEnvelopeFixtures.envelope("CaseOpened")
                .from("case-service")
                .aggregate("Case", caseId, 1)
                .payload(
                    Map.of(
                        "caseId",
                        caseId,
                        "type",
                        "RIDER_HELP",
                        "refs",
                        List.of(
                            Map.of("refType", "incidentId", "refId", id + ":" + requestId),
                            Map.of("refType", "deliveryId", "refId", id),
                            Map.of("refType", "requestId", "refId", requestId)),
                        "openedAt",
                        Instant.now().toString()))
                .buildJson())
        .join();
    JsonNode help = null;
    for (int i = 0; i < 100; i++) {
      help = body(staffGet("/deliveries/" + id + "/help-requests/" + requestId, ops));
      if (caseId.equals(help.path("caseId").asString())) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(help.path("caseId").asString()).isEqualTo(caseId);
    assertThat(help.path("state").asString()).isEqualTo("IN_PROGRESS");
    ResponseEntity<String> blocked = resolve(id, requestId, ops, "K-" + UUID.randomUUID());
    assertThat(blocked.getStatusCode().value()).isEqualTo(409);
    assertThat(body(blocked).path("code").asString()).isEqualTo("HELP_REQUEST_HAS_CASE");

    kafka
        .send(
            "platform.case.v1",
            caseId,
            EventEnvelopeFixtures.envelope("CaseClosed")
                .from("case-service")
                .aggregate("Case", caseId, 2)
                .payload(
                    Map.of(
                        "caseId",
                        caseId,
                        "type",
                        "RIDER_HELP",
                        "outcome",
                        "RESOLVED",
                        "closedAt",
                        Instant.now().toString()))
                .buildJson())
        .join();
    for (int i = 0; i < 100; i++) {
      help = body(staffGet("/deliveries/" + id + "/help-requests/" + requestId, ops));
      if ("RESOLVED".equals(help.path("state").asString())) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(help.path("state").asString()).isEqualTo("RESOLVED");
    assertThat(help.path("resolvedBy").asString()).isEqualTo("case:" + caseId);
    assertThat(help.path("resolutionNote").asString()).contains("RESOLVED");

    kafka
        .send(
            "platform.case.v1",
            caseId,
            EventEnvelopeFixtures.envelope("CaseReopened")
                .from("case-service")
                .aggregate("Case", caseId, 3)
                .payload(
                    Map.of(
                        "caseId",
                        caseId,
                        "type",
                        "RIDER_HELP",
                        "reopenedAt",
                        Instant.now().toString()))
                .buildJson())
        .join();
    for (int i = 0; i < 100; i++) {
      help = body(staffGet("/deliveries/" + id + "/help-requests/" + requestId, ops));
      if ("IN_PROGRESS".equals(help.path("state").asString())) {
        break;
      }
      Thread.sleep(200);
    }
    assertThat(help.path("state").asString()).isEqualTo("IN_PROGRESS");
  }

  ResponseEntity<String> resolve(String id, String requestId, String token, String key) {
    return http.post()
        .uri("/deliveries/{id}/help-requests/{r}/resolutions", id, requestId)
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "backoffice");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("note", "고객과 통화해 위치 안내"))
        .retrieve()
        .toEntity(String.class);
  }

  JsonNode view(String id, String rider) {
    return body(
        http.get()
            .uri("/deliveries/{id}", id)
            .header("Authorization", "Bearer " + TestJwt.rider(rider))
            .retrieve()
            .toEntity(String.class));
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

  @Test
  void sameRiderReassignedAfterOperatorCancelStillEarnsOnCompletion() {
    // 운영자 배차 취소(산정 불가 기록) 뒤 같은 라이더가 다시 배정돼 완료 → 완료 수행료가 따로 생긴다(V4, PC 1 검토 후속)
    String id = "DLV-" + UUID.randomUUID();
    String shipment = "SHP-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    String rider = "RDR-" + UUID.randomUUID();
    created(id, shipment, order);
    assigned(id, order, rider, 1);
    awaitState(id, "ASSIGNED");
    send(
        "delivery.delivery.v1",
        "DeliveryAssignmentChanged",
        "Delivery",
        id,
        Map.of(
            "deliveryId",
            id,
            "oldRiderId",
            rider,
            "assignmentVersion",
            2,
            "reason",
            "CANCELLED_OPERATOR",
            "changedAt",
            Instant.now().toString()));
    awaitState(id, "UNASSIGNED");
    awaitSql("SELECT count(*) FROM rider_earning WHERE delivery_id = '" + id + "'");

    assigned(id, order, rider, 3);
    awaitSql(
        "SELECT count(*) FROM execution_delivery WHERE delivery_id = '"
            + id
            + "' AND assignment_version = 3");
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
            List.of(Map.of("packageId", "PKG-" + id))));
    handedOver(id, shipment, order, rider, "PKG-" + id, "HO-" + id, Map.of("L-1", 1));
    awaitState(id, "PICKED_UP");
    assertThat(post(id, "customer-arrivals", rider, Map.of()).getStatusCode().value())
        .isEqualTo(200);
    String photoRef = body(upload(id, rider, JPEG)).path("photoRef").asString();
    assertThat(
            post(id, "completions", rider, completion(List.of(photoRef), Instant.now().toString()))
                .getStatusCode()
                .value())
        .isEqualTo(200);

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT assignment_version, calculable, total, settlement_state FROM rider_earning"
                + " WHERE delivery_id = ? ORDER BY assignment_version",
            id);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("assignment_version")).isEqualTo(1);
    assertThat(rows.get(0).get("calculable")).isEqualTo(false);
    assertThat(rows.get(1).get("assignment_version")).isEqualTo(3);
    assertThat(rows.get(1).get("calculable")).isEqualTo(true);
    assertThat(rows.get(1).get("total")).isEqualTo(3900);
    assertThat(rows.get(1).get("settlement_state")).isEqualTo("PENDING_CONFIRMATION");
    assertThat(view(id, rider).path("earning").path("settlementState").asString())
        .isEqualTo("PENDING_CONFIRMATION"); // 배송 조회는 가장 최근 수행료
  }

  /**
   * DD-2(사용자 2026-11-16 "추천대로"): 오배송 회수 배송 — 회수지에서 사진 → 회수(ReturnPickedUp, 원래 배송이 전달한 줄) → MFC 도착 =
   * 반납(ReturnDelivered, 수행료 = 수락 당시 금액). 원래 고객 전달지는 열지 않고, 일반 배송 단계는 409. 회수 라이더에게 원래 완료 증빙을 보여 준다.
   */
  @Test
  void misdeliveryRecoveryPicksUpAtSpotAndReturnsToStore() {
    String origin = "DLV-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    String r1 = "RDR-" + UUID.randomUUID();
    String r2 = "RDR-" + UUID.randomUUID();
    pickedUp(origin, "SHP-" + UUID.randomUUID(), order, r1);
    assertThat(post(origin, "customer-arrivals", r1, Map.of()).getStatusCode().value())
        .isEqualTo(200);
    String originPhoto = body(upload(origin, r1, JPEG)).path("photoRef").asString();
    assertThat(
            post(
                    origin,
                    "completions",
                    r1,
                    completion(List.of(originPhoto), Instant.now().toString()))
                .getStatusCode()
                .value())
        .isEqualTo(200);

    String caseId = "CASE-" + UUID.randomUUID();
    String rec = "DLV-" + UUID.randomUUID();
    Map<String, Object> created = new java.util.LinkedHashMap<>();
    created.put("deliveryId", rec);
    created.put("orderId", order);
    created.put("shipmentId", "RCV-" + caseId);
    created.put("type", "RETURN");
    created.put("purpose", "MISDELIVERY_RECOVERY");
    created.put("caseId", caseId);
    created.put("originDeliveryId", origin);
    created.put("mfcId", "MFC-1");
    created.put("dropAddressRef", "mfc:MFC-1");
    send("delivery.delivery.v1", "DeliveryCreated", "Delivery", rec, created);
    assigned(rec, order, r2, 1);
    awaitState(rec, "ASSIGNED");
    assertThat(
            jdbc.queryForObject(
                "SELECT type || ':' || purpose FROM execution_delivery WHERE delivery_id = ?",
                String.class,
                rec))
        .isEqualTo("RETURN:MISDELIVERY_RECOVERY");

    // 원래 고객 전달지는 열지 않는다, 일반 배송 단계는 409
    ResponseEntity<String> drop = dropInfo(rec, r2);
    assertThat(drop.getStatusCode().value()).isEqualTo(409);
    assertThat(body(drop).path("code").asString()).isEqualTo("NO_CUSTOMER_DROP");
    assertThat(post(rec, "store-arrivals", r2, Map.of()).getStatusCode().value()).isEqualTo(409);
    // 회수 라이더에게 원래 완료 증빙(놓은 곳·사진 참조)
    JsonNode v = view(rec, r2);
    assertThat(v.path("purpose").asString()).isEqualTo("MISDELIVERY_RECOVERY");
    assertThat(v.path("originDeliveryId").asString()).isEqualTo(origin);
    assertThat(v.path("recoveryOrigin").path("placement").asString()).isEqualTo("FRONT_DOOR");
    assertThat(v.path("recoveryOrigin").path("photoRefs").get(0).asString()).isEqualTo(originPhoto);

    // 원래 고객 전화(사용자 2026-11-16 "원래 고객의 전화는 회수 라이더에게 보여야 해") — 전화만, 주소는 없다
    orderApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/api/v1/internal/orders/" + order + "/drop-info"))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "orderId",
                                order,
                                "roadAddress",
                                "서울 강남구 테헤란로 1",
                                "detailAddress",
                                "101동 1001호",
                                "receiverName",
                                "김고객",
                                "receiverPhone",
                                "01012345678")))));
    ResponseEntity<String> contact = riderGet("/deliveries/{id}/recovery-contact", rec, r2);
    assertThat(contact.getStatusCode().value()).isEqualTo(200);
    assertThat(contact.getHeaders().getCacheControl()).contains("no-store");
    assertThat(body(contact).path("receiverPhone").asString()).isEqualTo("01012345678");
    assertThat(body(contact).path("originDeliveryId").asString()).isEqualTo(origin);
    assertThat(body(contact).has("roadAddress")).isFalse();
    assertThat(body(contact).has("detailAddress")).isFalse();
    assertThat(riderGet("/deliveries/{id}/recovery-contact", rec, r1).getStatusCode().value())
        .isEqualTo(404); // 회수 라이더가 아니면 있는지도 모른다
    ResponseEntity<String> notRecovery = riderGet("/deliveries/{id}/recovery-contact", origin, r1);
    assertThat(notRecovery.getStatusCode().value()).isEqualTo(409);
    assertThat(body(notRecovery).path("code").asString()).isEqualTo("NOT_RECOVERY");

    // 사진 보기(사용자 2026-11-16 "사진보기 기능 넣어") — 회수 라이더는 원래 배송 사진을, 원래 라이더·운영자도 본다
    ResponseEntity<byte[]> img = photo(origin, originPhoto, TestJwt.rider(r2));
    assertThat(img.getStatusCode().value()).isEqualTo(200);
    assertThat(img.getBody()).isEqualTo(JPEG);
    assertThat(img.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
    assertThat(img.getHeaders().getCacheControl()).contains("no-store");
    assertThat(photo(origin, originPhoto, TestJwt.rider(r1)).getStatusCode().value())
        .isEqualTo(200);
    assertThat(
            photo(origin, originPhoto, TestJwt.operator("OP-RO", List.of("RIDER_OPS"), List.of()))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(photo(origin, originPhoto, TestJwt.rider("RDR-other")).getStatusCode().value())
        .isEqualTo(404);
    assertThat(photo(rec, originPhoto, TestJwt.rider(r2)).getStatusCode().value())
        .isEqualTo(404); // 다른 배송의 사진
    assertThat(photo(origin, "EVP-none", TestJwt.rider(r2)).getStatusCode().value()).isEqualTo(404);

    // 사진 없이 회수 → 400(계약 minItems 1), 남의 사진 → 422, 회수지에서 사진(배정 상태에서 올릴 수 있다) → 회수
    assertThat(
            post(rec, "returns/pickups", r2, Map.of("items", List.of(), "evidenceRefs", List.of()))
                .getStatusCode()
                .value())
        .isEqualTo(400);
    ResponseEntity<String> notMine =
        post(
            rec,
            "returns/pickups",
            r2,
            Map.of("items", List.of(), "evidenceRefs", List.of(originPhoto)));
    assertThat(notMine.getStatusCode().value()).isEqualTo(422);
    assertThat(body(notMine).path("code").asString()).isEqualTo("EVIDENCE_REQUIRED");
    String spotPhoto = body(upload(rec, r2, JPEG)).path("photoRef").asString();
    assertThat(spotPhoto).isNotBlank();
    assertThat(
            post(
                    rec,
                    "returns/pickups",
                    r2,
                    Map.of("items", List.of(), "evidenceRefs", List.of(spotPhoto)))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(state(rec)).isEqualTo("RETURN_PICKED_UP");
    JsonNode picked = outbox(rec, "ReturnPickedUp").path("payload");
    assertThat(picked.path("purpose").asString()).isEqualTo("MISDELIVERY_RECOVERY");
    assertThat(picked.path("caseId").asString()).isEqualTo(caseId);
    assertThat(picked.path("originDeliveryId").asString()).isEqualTo(origin);
    assertThat(picked.path("riderId").asString()).isEqualTo(r2);
    assertThat(picked.path("lines").get(0).path("orderLineId").asString()).isEqualTo("L-1");
    assertThat(picked.path("lines").get(0).path("qty").asInt()).isEqualTo(1);
    // 전달 완료는 회수 배송에 쓰지 않는다
    assertThat(
            post(rec, "completions", r2, completion(List.of(spotPhoto), Instant.now().toString()))
                .getStatusCode()
                .value())
        .isEqualTo(409);

    // MFC 도착 = 반납 → 배송 끝, 수행료
    assertThat(post(rec, "returns/store-deliveries", r2, Map.of()).getStatusCode().value())
        .isEqualTo(200);
    assertThat(state(rec)).isEqualTo("RETURNED_TO_STORE");
    JsonNode returned = outbox(rec, "ReturnDelivered").path("payload");
    assertThat(returned.path("mfcId").asString()).isEqualTo("MFC-1");
    assertThat(returned.path("lines")).hasSize(1);
    Map<String, Object> earning =
        jdbc.queryForMap(
            "SELECT rider_id, calculable, total, settlement_state FROM rider_earning WHERE"
                + " delivery_id = ?",
            rec);
    assertThat(earning.get("rider_id")).isEqualTo(r2);
    assertThat(earning.get("calculable")).isEqualTo(true);
    assertThat(earning.get("total")).isEqualTo(3900);
    assertThat(earning.get("settlement_state")).isEqualTo("PENDING_CONFIRMATION");
    assertThat(view(rec, r2).path("evidence").path("photoRefs").get(0).asString())
        .isEqualTo(spotPhoto);
    // 반납 뒤에는 원래 고객 전화·원래 배송 사진을 닫는다(자기 회수 사진은 본다)
    assertThat(body(riderGet("/deliveries/{id}/recovery-contact", rec, r2)).path("code").asString())
        .isEqualTo("RECOVERY_CONTACT_CLOSED");
    assertThat(photo(origin, originPhoto, TestJwt.rider(r2)).getStatusCode().value())
        .isEqualTo(404);
    assertThat(photo(rec, spotPhoto, TestJwt.rider(r2)).getStatusCode().value()).isEqualTo(200);
  }

  ResponseEntity<String> riderGet(String uri, String id, String rider) {
    return http.get()
        .uri(uri, id)
        .header("Authorization", "Bearer " + TestJwt.rider(rider))
        .retrieve()
        .toEntity(String.class);
  }

  ResponseEntity<byte[]> photo(String id, String photoRef, String token) {
    return http.get()
        .uri("/deliveries/{id}/evidence-photos/{ref}", id, photoRef)
        .header("Authorization", "Bearer " + token)
        .retrieve()
        .toEntity(byte[].class);
  }

  /** 라이더 귀책 오배송(ADR-0065): 원래 수행료 지급 전 → ADJUSTED·산정 제외, 이미 지급 → 메모만. 시스템 귀책은 그대로. */
  @Test
  void riderFaultMisdeliveryWithholdsOriginalFee() {
    String id = "DLV-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    String rider = "RDR-" + UUID.randomUUID();
    pickedUp(id, "SHP-" + UUID.randomUUID(), order, rider);
    assertThat(post(id, "customer-arrivals", rider, Map.of()).getStatusCode().value())
        .isEqualTo(200);
    String photo = body(upload(id, rider, JPEG)).path("photoRef").asString();
    assertThat(
            post(id, "completions", rider, completion(List.of(photo), Instant.now().toString()))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    caseEvent(id, "SYSTEM");
    caseEvent(id, "RIDER");
    awaitSql(
        "SELECT count(*) FROM rider_earning WHERE delivery_id = '"
            + id
            + "' AND settlement_state = 'ADJUSTED'");
    Map<String, Object> e =
        jdbc.queryForMap("SELECT calculable, note FROM rider_earning WHERE delivery_id = ?", id);
    assertThat(e.get("calculable")).isEqualTo(false);
    assertThat((String) e.get("note")).contains("배달료 미지급");

    // 이미 지급된 수행료는 상태 그대로, 메모만
    String id2 = "DLV-" + UUID.randomUUID();
    String rider2 = "RDR-" + UUID.randomUUID();
    pickedUp(id2, "SHP-" + UUID.randomUUID(), "ORD-" + UUID.randomUUID(), rider2);
    post(id2, "customer-arrivals", rider2, Map.of());
    String photo2 = body(upload(id2, rider2, JPEG)).path("photoRef").asString();
    post(id2, "completions", rider2, completion(List.of(photo2), Instant.now().toString()));
    jdbc.update("UPDATE rider_earning SET settlement_state = 'PAID' WHERE delivery_id = ?", id2);
    caseEvent(id2, "RIDER");
    awaitSql(
        "SELECT count(*) FROM rider_earning WHERE delivery_id = '"
            + id2
            + "' AND note LIKE '%회수 필요%'");
    assertThat(
            jdbc.queryForObject(
                "SELECT settlement_state FROM rider_earning WHERE delivery_id = ?",
                String.class,
                id2))
        .isEqualTo("PAID");
  }

  /**
   * ADR-0065 고객 잘못 다시 배송: 잘못 둔 곳에서 사진·받음 → PICKED_UP → 고객 도착 → 완료(DeliveryCompleted purpose, 원래 줄).
   * 가게 도착은 409, 전달지 = 실제 집 주소 + 주문 당시 연락처, 원래 기사는 배달료 0.
   */
  @Test
  void customerFaultRedeliveryPicksUpAtSpotAndDeliversToRealHome() {
    String origin = "DLV-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    String r1 = "RDR-" + UUID.randomUUID();
    pickedUp(origin, "SHP-" + UUID.randomUUID(), order, r1);
    post(origin, "customer-arrivals", r1, Map.of());
    String originPhoto = body(upload(origin, r1, JPEG)).path("photoRef").asString();
    post(origin, "completions", r1, completion(List.of(originPhoto), Instant.now().toString()));

    String rd = "DLV-" + UUID.randomUUID();
    Map<String, Object> created = new java.util.LinkedHashMap<>();
    created.put("deliveryId", rd);
    created.put("orderId", order);
    created.put("shipmentId", "RDL-CASE-1");
    created.put("type", "RESHIPMENT");
    created.put("purpose", "MISDELIVERY_REDELIVERY");
    created.put("caseId", "CASE-RD");
    created.put("originDeliveryId", origin);
    created.put("mfcId", "MFC-1");
    created.put("dropAddressRef", "order:" + order + ":redelivery:RDD-1");
    send("delivery.delivery.v1", "DeliveryCreated", "Delivery", rd, created);
    send(
        "delivery.delivery.v1",
        "DeliveryAssigned",
        "Delivery",
        rd,
        Map.of(
            "deliveryId",
            rd,
            "orderId",
            order,
            "riderId",
            r1,
            "assignmentVersion",
            1,
            "acceptedAt",
            Instant.now().toString(),
            "fee",
            0,
            "vehicleType",
            "MOTORCYCLE",
            "distanceM",
            120));
    awaitState(rd, "ASSIGNED");
    assertThat(
            jdbc.queryForObject(
                "SELECT redelivery_destination_id FROM execution_delivery WHERE delivery_id = ?",
                String.class,
                rd))
        .isEqualTo("RDD-1");
    assertThat(post(rd, "store-arrivals", r1, Map.of()).getStatusCode().value()).isEqualTo(409);
    // 잘못 둔 곳에서 사진(배정 상태) → 받음 → PICKED_UP
    String spot = body(upload(rd, r1, JPEG)).path("photoRef").asString();
    assertThat(
            post(
                    rd,
                    "returns/pickups",
                    r1,
                    Map.of("items", List.of(), "evidenceRefs", List.of(spot)))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(state(rd)).isEqualTo("PICKED_UP");
    assertThat(outbox(rd, "ReturnPickedUp").path("payload").path("purpose").asString())
        .isEqualTo("MISDELIVERY_REDELIVERY");
    // 전달지: 실제 집 + 주문 당시 연락처
    orderApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/api/v1/internal/orders/" + order + "/drop-info"))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "orderId",
                                order,
                                "roadAddress",
                                "잘못 입력한 주소",
                                "detailAddress",
                                "101동",
                                "receiverName",
                                "김고객",
                                "receiverPhone",
                                "01012345678")))));
    orderApi.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo(
                    "/api/v1/internal/orders/" + order + "/redelivery-destinations/RDD-1"))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "destinationId",
                                "RDD-1",
                                "orderId",
                                order,
                                "deliveryId",
                                origin,
                                "roadAddress",
                                "실제 집 주소",
                                "detailAddress",
                                "202동 1501호")))));
    JsonNode drop = body(dropInfo(rd, r1));
    assertThat(drop.path("roadAddress").asString()).isEqualTo("실제 집 주소");
    assertThat(drop.path("detailAddress").asString()).isEqualTo("202동 1501호");
    assertThat(drop.path("receiverPhone").asString()).isEqualTo("01012345678");
    // 고객 도착 → 사진 → 완료
    assertThat(post(rd, "customer-arrivals", r1, Map.of()).getStatusCode().value()).isEqualTo(200);
    String door = body(upload(rd, r1, JPEG)).path("photoRef").asString();
    assertThat(
            post(rd, "completions", r1, completion(List.of(door), Instant.now().toString()))
                .getStatusCode()
                .value())
        .isEqualTo(200);
    assertThat(state(rd)).isEqualTo("COMPLETED");
    JsonNode done = outbox(rd, "DeliveryCompleted").path("payload");
    assertThat(done.path("purpose").asString()).isEqualTo("MISDELIVERY_REDELIVERY");
    assertThat(done.path("lines").get(0).path("orderLineId").asString()).isEqualTo("L-1");
    assertThat(
            jdbc.queryForObject(
                "SELECT total FROM rider_earning WHERE delivery_id = ?", Integer.class, rd))
        .isZero(); // 원래 기사 추가 운행료 없음
  }

  void caseEvent(String deliveryId, String fault) {
    String caseId = "CASE-" + UUID.randomUUID();
    String json =
        EventEnvelopeFixtures.envelope("MisdeliveryConfirmed")
            .from("case-service")
            .aggregate("Case", caseId, 1)
            .payload(
                Map.of(
                    "caseId",
                    caseId,
                    "deliveryId",
                    deliveryId,
                    "confirmedBy",
                    "OP-CS",
                    "confirmedAt",
                    Instant.now().toString(),
                    "fault",
                    fault,
                    "resolution",
                    "RESHIP"))
            .buildJson();
    kafka.send("platform.case.v1", caseId, json).join();
  }

  /**
   * 오배송 재발송 배송(2026-11-16): type RESHIPMENT·purpose MISDELIVERY_RESHIPMENT — 수행은 일반 배송과 같다(고객 전달지
   * 열림).
   */
  @Test
  void reshipmentDeliveryIsExecutedLikeForward() {
    String id = "DLV-" + UUID.randomUUID();
    String order = "ORD-" + UUID.randomUUID();
    String rider = "RDR-" + UUID.randomUUID();
    Map<String, Object> created = new java.util.LinkedHashMap<>();
    created.put("deliveryId", id);
    created.put("orderId", order);
    created.put("shipmentId", order + "-RS-1a2b3c4d");
    created.put("type", "RESHIPMENT");
    created.put("purpose", "MISDELIVERY_RESHIPMENT");
    created.put("caseId", "CASE-1");
    created.put("mfcId", "MFC-1");
    created.put("dropAddressRef", "order:" + order);
    send("delivery.delivery.v1", "DeliveryCreated", "Delivery", id, created);
    assigned(id, order, rider, 1);
    awaitState(id, "ASSIGNED");
    JsonNode v = view(id, rider);
    assertThat(v.path("type").asString()).isEqualTo("RESHIPMENT");
    assertThat(v.path("purpose").asString()).isEqualTo("MISDELIVERY_RESHIPMENT");
    assertThat(post(id, "store-arrivals", rider, Map.of()).getStatusCode().value()).isEqualTo(200);
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
            Instant.now().toString(),
            "fee",
            3900,
            "vehicleType",
            "MOTORCYCLE",
            "distanceM",
            1800));
  }

  /** 배정 → 목록(포장 1개) → 인계 → PICKED_UP 까지. */
  void pickedUp(String id, String shipment, String order, String rider) {
    created(id, shipment, order);
    assigned(id, order, rider, 1);
    awaitState(id, "ASSIGNED");
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
            List.of(Map.of("packageId", "PKG-" + id))));
    handedOver(id, shipment, order, rider, "PKG-" + id, "HO-" + id, Map.of("L-1", 1));
    awaitState(id, "PICKED_UP");
  }

  static Map<String, Object> failure(String reason, List<String> photos, int contacts) {
    List<Map<String, Object>> attempts = new java.util.ArrayList<>();
    for (int i = 0; i < contacts; i++) {
      attempts.add(
          Map.of(
              "attemptedAt",
              Instant.now().minusSeconds(60L * (i + 1)).toString(),
              "method",
              "SAFE_NUMBER_CALL",
              "result",
              "NO_ANSWER"));
    }
    return Map.of(
        "reason",
        reason,
        "evidenceRefs",
        photos,
        "goodsLocation",
        "WITH_RIDER",
        "contactAttempts",
        attempts);
  }

  ResponseEntity<String> decide(String id, String operator, List<String> centers, String decision) {
    return decide(id, operator, List.of("MFC_SUPERVISOR"), centers, decision);
  }

  ResponseEntity<String> decide(
      String id, String operator, List<String> roles, List<String> centers, String decision) {
    return http.post()
        .uri("/deliveries/{id}/failure-decisions", id)
        .headers(
            h -> {
              h.setBearerAuth(TestJwt.operator(operator, roles, centers));
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "mfc-backoffice");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("decision", decision, "note", "IT"))
        .retrieve()
        .toEntity(String.class);
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
    return upload(id, rider, bytes, UUID.randomUUID().toString());
  }

  ResponseEntity<String> upload(String id, String rider, byte[] bytes, String idempotencyKey) {
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
        .headers(
            h -> {
              riderHeaders(h, rider);
              h.set("Idempotency-Key", idempotencyKey);
            })
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
