package com.pmart.order.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 오배송 다시 배송 실제 집 주소(V12, ADR-0065). 개인정보 — 값은 로그에 남기지 않는다. */
@Repository
public class RedeliveryDestinationStore {

  public record Destination(
      String destinationId,
      String orderId,
      String deliveryId,
      String roadAddress,
      @Nullable String detailAddress,
      @Nullable String postalCode,
      Instant recordedAt) {

    @Override
    public String toString() {
      return "Destination[" + destinationId + ", 주소 가림]";
    }
  }

  private final JdbcTemplate jdbc;

  public RedeliveryDestinationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 같은 ID 가 있으면 넣지 않는다(같은 요청 재전송). */
  public void insert(Destination d, String reason, String recordedBy) {
    jdbc.update(
        "INSERT INTO order_redelivery_destination (destination_id, order_id, delivery_id,"
            + " road_address, detail_address, postal_code, reason, recorded_by, recorded_at) VALUES"
            + " (?,?,?,?,?,?,?,?,?) ON CONFLICT (destination_id) DO NOTHING",
        d.destinationId(),
        d.orderId(),
        d.deliveryId(),
        d.roadAddress(),
        d.detailAddress(),
        d.postalCode(),
        reason,
        recordedBy,
        Timestamp.from(d.recordedAt()));
  }

  public Optional<Destination> find(String orderId, String destinationId) {
    return jdbc
        .query(
            "SELECT * FROM order_redelivery_destination WHERE destination_id = ? AND order_id = ?",
            (rs, i) ->
                new Destination(
                    rs.getString("destination_id"),
                    rs.getString("order_id"),
                    rs.getString("delivery_id"),
                    rs.getString("road_address"),
                    rs.getString("detail_address"),
                    rs.getString("postal_code"),
                    rs.getTimestamp("recorded_at").toInstant()),
            destinationId,
            orderId)
        .stream()
        .findFirst();
  }
}
