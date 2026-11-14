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
