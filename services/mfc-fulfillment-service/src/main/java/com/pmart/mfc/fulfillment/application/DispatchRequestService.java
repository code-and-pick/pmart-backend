package com.pmart.mfc.fulfillment.application;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.TaskInfo;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 배차 요청(2026-11-08 사용자 결정 D-5 — Delivery §3.1). 점원이 포장 중(또는 출하 대기 뒤) 예상 포장 시간과 함께 요청(MANUAL)만 한다 —
 * 출하 대기 때 자동 요청(AUTO_ON_READY)은 뺐다(사용자 2026-11-13 "자동요청빼", BR-51). 출고당 한 번, 같은 트랜잭션에서 {@code
 * DispatchRequested}. 잠금 = 그 출고 PACK 작업 행(포장 밀봉과 한 줄).
 */
@Service
public class DispatchRequestService {

  private static final Logger log = LoggerFactory.getLogger(DispatchRequestService.class);

  public record Request(
      String shipmentId,
      String trigger,
      @Nullable Integer estimatedPackMinutes,
      Instant estimatedReadyAt,
      Instant requestedAt,
      @Nullable String requestedBy) {}

  /** created = 이번 요청으로 만들었는가(아니면 이미 있던 요청). */
  public record Outcome(Request request, boolean created) {}

  private final JdbcTemplate jdbc;
  private final TaskStore tasks;
  private final FulfillmentEvents events;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DispatchRequestService(
      JdbcTemplate jdbc,
      TaskStore tasks,
      FulfillmentEvents events,
      PlatformTransactionManager txManager,
      Clock clock) {
    this.jdbc = jdbc;
    this.tasks = tasks;
    this.events = events;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  /** 점원 요청. */
  public Outcome request(String shipmentId, int packMinutes) {
    TaskInfo pack0 =
        tasks
            .sibling(shipmentId, "PACK")
            .orElseThrow(
                () -> PmartException.notFound("SHIPMENT_NOT_FOUND", "출고가 없습니다: " + shipmentId));
    String me = TaskService.requireWorker(pack0.mfcId());
    if (packMinutes < 1 || packMinutes > 180) {
      throw TaskService.invalid("estimatedPackMinutes 는 1~180 분입니다.");
    }
    return java.util.Objects.requireNonNull(
        tx.execute(
            s -> {
              TaskInfo pack = tasks.lock(pack0.taskId()).orElseThrow();
              Optional<Request> existing = find(shipmentId);
              if (existing.isPresent()) {
                return new Outcome(existing.get(), false);
              }
              String shipmentState = shipmentState(shipmentId);
              boolean started =
                  "IN_PROGRESS".equals(pack.state()) || "COMPLETED".equals(pack.state());
              if (!started || "CANCELLED".equals(shipmentState)) {
                throw new PmartException(
                    "INVALID_STATE",
                    HttpStatus.CONFLICT,
                    "포장을 시작한 출고만 배차를 요청합니다(포장 " + pack.state() + ", 출고 " + shipmentState + ").",
                    false,
                    List.of());
              }
              Instant now = clock.instant();
              Request r =
                  new Request(
                      shipmentId,
                      "MANUAL",
                      packMinutes,
                      now.plusSeconds(60L * packMinutes),
                      now,
                      me);
              save(r);
              events.dispatchRequested(pack, r);
              log.info("출고 {} 배차 요청(점원 {}, 예상 포장 {}분)", shipmentId, me, packMinutes);
              return new Outcome(r, true);
            }));
  }

  public Optional<Request> find(String shipmentId) {
    return jdbc
        .query(
            "SELECT shipment_id, dispatch_trigger, dispatch_pack_minutes, dispatch_estimated_ready_at,"
                + " dispatch_requested_at, dispatch_requested_by FROM shipment"
                + " WHERE shipment_id = ? AND dispatch_requested_at IS NOT NULL",
            (rs, i) ->
                new Request(
                    rs.getString(1),
                    rs.getString(2),
                    (Integer) rs.getObject(3),
                    rs.getTimestamp(4).toInstant(),
                    rs.getTimestamp(5).toInstant(),
                    rs.getString(6)),
            shipmentId)
        .stream()
        .findFirst();
  }

  private String shipmentState(String shipmentId) {
    return jdbc.queryForObject(
        "SELECT state FROM shipment WHERE shipment_id = ?", String.class, shipmentId);
  }

  private void save(Request r) {
    jdbc.update(
        "UPDATE shipment SET dispatch_requested_at = ?, dispatch_trigger = ?, dispatch_pack_minutes = ?,"
            + " dispatch_estimated_ready_at = ?, dispatch_requested_by = ? WHERE shipment_id = ?"
            + " AND dispatch_requested_at IS NULL",
        Timestamp.from(r.requestedAt()),
        r.trigger(),
        r.estimatedPackMinutes(),
        Timestamp.from(r.estimatedReadyAt()),
        r.requestedBy(),
        r.shipmentId());
  }
}
