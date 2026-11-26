package com.pmart.cdc.quality.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 운영 제한(0.4.0, WMS CDC-F-031 — 사용자 2026-11-26 "새 할당과 출발"·"문서 그대로"). 정본은 여기, 지키는 곳은 cdc-inventory 새
 * 할당과 cdc-outbound 출발 검증(내부 확인 {@link #check}). 자동 해제 없음, 중첩은 맞는 제한이 하나라도 있으면 막는다.
 *
 * <ul>
 *   <li>CONGESTION: 걸기·풀기 그 센터 CDC_SUPERVISOR.
 *   <li>SAFETY: 걸기 그 센터 운영자 누구나(현장 책임자), 풀기 CDC_SUPERVISOR + 본사 승인 번호.
 *   <li>HQ·QUALITY: 걸기 운영자 + 본사 승인 번호, 풀기 CDC_SUPERVISOR + 본사 승인 번호.
 * </ul>
 */
@Service
public class RestrictionService {

  static final String OP_APPLY = "cdcQuality:restrictionApply";
  static final String OP_LIFT = "cdcQuality:restrictionLift";

  public record ApplyCmd(
      List<String> activities,
      @Nullable String temperatureZone,
      List<String> skus,
      String reason,
      @Nullable String approvalRef,
      @Nullable Instant plannedUntil,
      @Nullable String note) {}

  public record View(
      String restrictionId,
      String cdcId,
      List<String> activities,
      @Nullable String temperatureZone,
      List<String> skus,
      String reason,
      @Nullable String approvalRef,
      @Nullable Instant plannedUntil,
      @Nullable String note,
      String state,
      String appliedBy,
      Instant appliedAt,
      @Nullable String liftedBy,
      @Nullable String liftApprovalRef,
      @Nullable String liftNote,
      @Nullable Instant liftedAt) {}

  public record Item(String sku, @Nullable String temperatureZone) {}

  public record Match(String restrictionId, String reason, String sku) {}

  private final JdbcTemplate jdbc;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public RestrictionService(
      JdbcTemplate jdbc,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock) {
    this.jdbc = jdbc;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  public String apply(String requestId, String sourceSystem, String cdcId, ApplyCmd c) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    switch (c.reason()) {
      case "CONGESTION" -> op.requireManager();
      case "SAFETY" -> {} // 현장 책임자 — 그 센터 운영자 누구나
      default -> requireApproval(c.approvalRef(), "본사 부과·품질 제한은 본사 승인 번호(approvalRef)가 있어야 겁니다.");
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("cdcId", cdcId);
    req.put("cmd", c);
    return idempotency
        .execute(
            op.key(requestId, OP_APPLY),
            req,
            HoldService.Ref.class,
            () -> {
              String id = HoldService.newId("OR-");
              Instant at = clock.instant();
              jdbc.update(
                  "INSERT INTO operation_restriction (restriction_id, cdc_id, activities, temperature_zone, skus,"
                      + " reason, approval_ref, planned_until, note, state, applied_by, applied_at)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                  id,
                  cdcId,
                  String.join(",", c.activities().stream().distinct().sorted().toList()),
                  c.temperatureZone(),
                  c.skus().isEmpty()
                      ? null
                      : String.join(",", c.skus().stream().distinct().toList()),
                  c.reason(),
                  c.approvalRef(),
                  c.plannedUntil() == null ? null : Timestamp.from(c.plannedUntil()),
                  c.note(),
                  op.id(),
                  Timestamp.from(at));
              publish("OperationRestrictionApplied", find(id), op.id(), "appliedAt", at);
              return new HoldService.Ref(id);
            })
        .value()
        .id();
  }

  public void lift(
      String requestId,
      String sourceSystem,
      String cdcId,
      String restrictionId,
      @Nullable String approvalRef,
      @Nullable String note) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    op.requireManager();
    View pre = get(cdcId, restrictionId);
    if (!"CONGESTION".equals(pre.reason())) {
      requireApproval(approvalRef, "안전·본사 부과·품질 제한은 본사 승인 번호(approvalRef)가 있어야 풉니다.");
    }
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("restrictionId", restrictionId);
    req.put("approvalRef", String.valueOf(approvalRef));
    req.put("note", String.valueOf(note));
    idempotency.execute(
        op.key(requestId, OP_LIFT),
        req,
        HoldService.Ref.class,
        () -> {
          Instant at = clock.instant();
          int n =
              jdbc.update(
                  "UPDATE operation_restriction SET state = 'LIFTED', lifted_by = ?, lift_approval_ref = ?,"
                      + " lift_note = ?, lifted_at = ? WHERE restriction_id = ? AND cdc_id = ? AND state = 'ACTIVE'",
                  op.id(),
                  approvalRef,
                  note,
                  Timestamp.from(at),
                  restrictionId,
                  cdcId);
          if (n != 1) {
            throw PmartException.invalidState("이미 풀린 제한입니다.");
          }
          publish("OperationRestrictionLifted", find(restrictionId), op.id(), "liftedAt", at);
          return new HoldService.Ref(restrictionId);
        });
  }

  /** 내부 확인 — 줄마다 맞는 ACTIVE 제한(읽기만). */
  public List<Match> check(String cdcId, String activity, List<Item> items) {
    List<View> active =
        jdbc.query(
            "SELECT * FROM operation_restriction WHERE cdc_id = ? AND state = 'ACTIVE' ORDER BY applied_at",
            (rs, i) -> view(rs),
            cdcId);
    List<Match> out = new ArrayList<>();
    for (View r : active) {
      if (!r.activities().contains(activity)) {
        continue;
      }
      for (Item it : items) {
        boolean zone =
            r.temperatureZone() == null || r.temperatureZone().equals(it.temperatureZone());
        boolean sku = r.skus().isEmpty() || r.skus().contains(it.sku());
        if (zone && sku) {
          out.add(new Match(r.restrictionId(), r.reason(), it.sku()));
        }
      }
    }
    return out;
  }

  // ---------- 조회 ----------

  public View get(String cdcId, String restrictionId) {
    Operator.requireCenter(cdcId);
    List<View> v =
        jdbc.query(
            "SELECT * FROM operation_restriction WHERE restriction_id = ? AND cdc_id = ?",
            (rs, i) -> view(rs),
            restrictionId,
            cdcId);
    if (v.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "운영 제한 " + restrictionId + " 가 없습니다.");
    }
    return v.get(0);
  }

  public List<View> list(String cdcId, @Nullable String state, @Nullable String cursor, int size) {
    Operator.requireCenter(cdcId);
    StringBuilder sql = new StringBuilder("SELECT * FROM operation_restriction WHERE cdc_id = ?");
    List<Object> args = new ArrayList<>(List.of(cdcId));
    if (state != null) {
      sql.append(" AND state = ?");
      args.add(state);
    }
    if (cursor != null) {
      sql.append(
          " AND (applied_at, restriction_id) < (SELECT applied_at, restriction_id FROM operation_restriction"
              + " WHERE restriction_id = ?)");
      args.add(cursor);
    }
    sql.append(" ORDER BY applied_at DESC, restriction_id DESC LIMIT ?");
    args.add(size);
    return jdbc.query(sql.toString(), (rs, i) -> view(rs), args.toArray());
  }

  private View find(String id) {
    return jdbc.queryForObject(
        "SELECT * FROM operation_restriction WHERE restriction_id = ?", (rs, i) -> view(rs), id);
  }

  private static View view(java.sql.ResultSet rs) throws java.sql.SQLException {
    String skus = rs.getString("skus");
    Timestamp pu = rs.getTimestamp("planned_until");
    Timestamp la = rs.getTimestamp("lifted_at");
    return new View(
        rs.getString("restriction_id"),
        rs.getString("cdc_id"),
        Arrays.asList(rs.getString("activities").split(",")),
        rs.getString("temperature_zone"),
        skus == null || skus.isBlank() ? List.of() : Arrays.asList(skus.split(",")),
        rs.getString("reason"),
        rs.getString("approval_ref"),
        pu == null ? null : pu.toInstant(),
        rs.getString("note"),
        rs.getString("state"),
        rs.getString("applied_by"),
        rs.getTimestamp("applied_at").toInstant(),
        rs.getString("lifted_by"),
        rs.getString("lift_approval_ref"),
        rs.getString("lift_note"),
        la == null ? null : la.toInstant());
  }

  private void publish(String type, View r, String actor, String timeField, Instant at) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("centerType", "CDC");
    scope.put("centerId", r.cdcId());
    if (!r.skus().isEmpty()) {
      scope.put("skus", r.skus());
    }
    scope.put(
        "description",
        "activities="
            + String.join(",", r.activities())
            + (r.temperatureZone() == null ? "" : " temperatureZone=" + r.temperatureZone())
            + " reason="
            + r.reason());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("restrictionId", r.restrictionId());
    p.put("restrictionType", "QUALITY".equals(r.reason()) ? "QUALITY" : "WORK_STOP");
    p.put("scope", scope);
    p.put(timeField, at.toString());
    events.publish(
        codec.create(
            events.sourceSystem(),
            type,
            1,
            "OperationRestriction",
            r.restrictionId(),
            "OperationRestrictionApplied".equals(type) ? 1 : 2,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, actor),
            EventEnvelope.EventSource.REAL,
            p),
        RecallService.TOPIC);
  }

  private static void requireApproval(@Nullable String approvalRef, String message) {
    if (approvalRef == null || approvalRef.isBlank()) {
      throw new PmartException(ErrorCode.APPROVAL_REQUIRED, message);
    }
  }
}
