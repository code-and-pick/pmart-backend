package com.pmart.delivery.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CP-1 1부(2026-11-16): mfc-capacity CapacityReduced → 영향 주문을 확정된 도착 시간대별로 묶어
 * SlotCapacityReduced(delivery.slot.v1), 도착 시간대 점유가 없는 주문은 MFC 작업 창 그대로 한 건. 점유는 바꾸지 않는다.
 */
@SpringBootTest(
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.security.service-token.client-secret=it-secret",
      "pmart.capacity.hold-sweep-interval=PT1H",
      "pmart.messaging.outbox.delete-after-insert=false"
    })
class CapacityReducedIT extends PostgresKafkaContainerSupport {

  @Autowired DataSource dataSource;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  /** 바꾸기(인자는 순서대로). */
  int update(String sql, Object... args) throws Exception {
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      return ps.executeUpdate();
    }
  }

  /** 첫 열 문자열들. */
  List<String> strings(String sql, Object... args) throws Exception {
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      List<String> out = new ArrayList<>();
      try (var rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
      return out;
    }
  }

  void confirmed(String order, LocalDate date, int start, int end) throws Exception {
    update(
        "INSERT INTO slot_hold (hold_id, version, order_id, mfc_id, slot_date, start_minute,"
            + " end_minute, state, held_at, expires_at, confirmed_at, source_system) VALUES (?, 2, ?,"
            + " 'MFC-CP', ?, ?, ?, 'CONFIRMED', now(), now(), now(), 'it')",
        "SH-" + UUID.randomUUID(),
        order,
        date,
        start,
        end);
  }

  @Test
  void reducedWorkWindowNotifiesAffectedOrdersByArrivalSlot() throws Exception {
    LocalDate tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1);
    String a = "ORD-CP-A-" + UUID.randomUUID();
    String b = "ORD-CP-B-" + UUID.randomUUID();
    String c = "ORD-CP-C-" + UUID.randomUUID();
    String now = "ORD-CP-N-" + UUID.randomUUID(); // 바로 배송(도착 시간대 점유 없음)
    confirmed(a, tomorrow, 18 * 60, 19 * 60);
    confirmed(b, tomorrow, 18 * 60, 19 * 60);
    confirmed(c, tomorrow, 19 * 60, 20 * 60);
    String restriction = "RST-" + UUID.randomUUID();
    Instant ws = tomorrow.atStartOfDay(ZoneId.of("Asia/Seoul")).plusHours(17).toInstant();
    String json =
        EventEnvelopeFixtures.envelope("CapacityReduced")
            .from("mfc-capacity-service")
            .aggregate("Capacity", "MFC-CP:" + restriction, 1)
            .payload(
                Map.of(
                    "mfcId",
                    "MFC-CP",
                    "window",
                    Map.of(
                        "start", ws.toString(),
                        "end", ws.plusSeconds(3600).toString(),
                        "meaning", "WORK"),
                    "oldCapacity",
                    20,
                    "newCapacity",
                    2,
                    "affectedOrders",
                    List.of(a, b, c, now),
                    "reason",
                    "STAFF_SHORTAGE",
                    "restrictionId",
                    restriction,
                    "reducedAt",
                    Instant.now().toString()))
            .buildJson();
    kafka.send("mfc.capacity.v1", "MFC-CP", json).join();

    List<JsonNode> out = List.of();
    for (int i = 0; i < 150 && out.size() < 3; i++) {
      Thread.sleep(200);
      out =
          strings(
                  "SELECT payload::text FROM outbox_event WHERE type = 'SlotCapacityReduced' AND"
                      + " (payload::text LIKE ? OR payload::text LIKE ? OR payload::text LIKE ?)",
                  "%" + a + "%",
                  "%" + c + "%",
                  "%" + now + "%")
              .stream()
              .map(mapper::readTree)
              .toList();
    }
    assertThat(out).hasSize(3);
    JsonNode six =
        out.stream()
            .map(n -> n.path("payload"))
            .filter(p -> p.path("affectedOrders").toString().contains(a))
            .findFirst()
            .orElseThrow();
    assertThat(six.path("affectedOrders").toString()).contains(b).doesNotContain(c);
    assertThat(six.path("window").path("meaning").asString()).isEqualTo("ARRIVAL");
    assertThat(Instant.parse(six.path("window").path("start").asString()))
        .isEqualTo(tomorrow.atStartOfDay(ZoneId.of("Asia/Seoul")).plusHours(18).toInstant());
    assertThat(six.path("reason").asString()).isEqualTo("MFC_CAPACITY_REDUCED");
    assertThat(six.path("newCapacity").asInt()).isEqualTo(2);
    JsonNode work =
        out.stream()
            .map(n -> n.path("payload"))
            .filter(p -> p.path("affectedOrders").toString().contains(now))
            .findFirst()
            .orElseThrow();
    assertThat(work.path("window").path("meaning").asString()).isEqualTo("WORK");
    assertThat(work.path("affectedOrders")).hasSize(1);
    assertThat(
            strings("SELECT DISTINCT topic FROM outbox_event WHERE type = 'SlotCapacityReduced'"))
        .containsExactly("delivery.slot.v1");
    // 점유는 그대로(자동으로 옮기거나 취소하지 않는다)
    assertThat(
            strings(
                "SELECT count(*) FROM slot_hold WHERE order_id IN (?, ?, ?) AND state = 'CONFIRMED'",
                a,
                b,
                c))
        .containsExactly("3");
  }
}
