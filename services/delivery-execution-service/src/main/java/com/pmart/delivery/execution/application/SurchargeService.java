package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.domain.ExecutionRules;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.EarningRow;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 엘리베이터 고장 추가 배달료(2026-11-17, 0.19.0 — 원문 §9.2, 사용자 "다른 사유로 추가금을 주진 않음"). 라이더가 전달 완료 뒤 층·사진으로 신청 →
 * 라이더 운영·관리자가 승인하면 그 수행료 합계에 더한다(금액은 서버가 층으로). 승인자·시각이 남는다.
 */
@Service
public class SurchargeService {

  private static final Logger log = LoggerFactory.getLogger(SurchargeService.class);
  private static final Set<String> OPEN_EARNING =
      Set.of("ESTIMATED", "PENDING_CONFIRMATION", "CONFIRMED");

  public record Request(
      String requestId,
      String deliveryId,
      String riderId,
      String reason,
      int floor,
      int amount,
      List<String> evidenceRefs,
      String state,
      Instant requestedAt,
      @Nullable String decidedBy,
      @Nullable Instant decidedAt,
      @Nullable String decisionNote) {}

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final RequestIdempotency idempotency;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Clock clock;

  public SurchargeService(
      ExecutionStore store,
      ExecutionEvents events,
      RequestIdempotency idempotency,
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.idempotency = idempotency;
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Request request(
      String deliveryId, String requestId, String source, int floor, List<String> evidenceRefs) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.RIDER)
            .orElseThrow(() -> ExecutionService.forbidden("라이더만 신청할 수 있습니다."));
    String id =
        idempotency
            .execute(
                new RequestKey(source, requestId, "requestSurcharge", p.subject()),
                Map.of("deliveryId", deliveryId, "floor", floor, "evidenceRefs", evidenceRefs),
                String.class,
                () -> create(deliveryId, p.subject(), floor, evidenceRefs))
            .value();
    return find(id).orElseThrow();
  }

  private String create(String deliveryId, String rider, int floor, List<String> evidenceRefs) {
    Instant now = clock.instant();
    DeliveryRow d =
        store
            .lockDelivery(deliveryId)
            .filter(x -> rider.equals(x.riderId()))
            .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
    if (!"COMPLETED".equals(d.state())) {
      throw PmartException.invalidState("전달 완료한 배송만 추가 배달료를 신청할 수 있습니다(지금 " + d.state() + ").");
    }
    int amount = ExecutionRules.elevatorSurcharge(floor);
    if (amount == 0) {
      throw unprocessable("FLOOR_BELOW_SURCHARGE", "엘리베이터 고장 추가 배달료는 15층부터입니다.");
    }
    for (String ref : evidenceRefs) {
      boolean mine =
          store
              .photo(ref)
              .filter(ph -> ph.deliveryId().equals(deliveryId) && rider.equals(ph.riderId()))
              .isPresent();
      if (!mine) {
        throw unprocessable("EVIDENCE_REQUIRED", "이 배송에 직접 올린 사진만 붙일 수 있습니다.");
      }
    }
    EarningRow e =
        store
            .earningOf(deliveryId, rider)
            .filter(x -> x.calculable() && OPEN_EARNING.contains(x.settlementState()))
            .orElseThrow(
                () -> conflict("EARNING_NOT_OPEN", "추가 배달료를 더할 수 있는 수행료가 아닙니다(지급 중·지급됨·산정 불가)."));
    Integer open =
        jdbc.queryForObject(
            "SELECT count(*) FROM surcharge_request WHERE delivery_id = ? AND state IN"
                + " ('REQUESTED', 'APPROVED')",
            Integer.class,
            deliveryId);
    if (open != null && open > 0) {
      throw conflict("SURCHARGE_ALREADY_REQUESTED", "이 배송은 이미 추가 배달료를 신청했습니다.");
    }
    String id = "SUR-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO surcharge_request (request_id, delivery_id, rider_id, earning_id, reason,"
            + " floor, amount, evidence_refs, state, requested_at) VALUES (?,?,?,?,"
            + " 'ELEVATOR_OUT_OF_ORDER',?,?,?::jsonb,'REQUESTED',?)",
        id,
        deliveryId,
        rider,
        e.earningId(),
        floor,
        amount,
        mapper.writeValueAsString(evidenceRefs),
        Timestamp.from(now));
    log.info("추가 배달료 신청 {} 배송 {} {}원", id, deliveryId, amount);
    return id;
  }

  /** state 가 null 이면 모든 상태(최근 신청부터, 2026-11-18 — 백오피스 "전체" 탭), 주면 그 상태만(오래된 것부터). */
  public List<Request> list(
      @Nullable String state,
      @Nullable String riderId,
      @Nullable String mfcId,
      int limit,
      int offset) {
    PmartPrincipal p = staff();
    if (!(p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS") || p.hasRole("CS_AGENT"))) {
      throw ExecutionService.forbidden("추가 배달료 신청은 라이더 운영·관리자·상담원만 볼 수 있습니다.");
    }
    return jdbc.query(
        "SELECT * FROM surcharge_request WHERE (CAST(? AS varchar) IS NULL OR state = ?) AND (CAST(?"
            + " AS varchar) IS NULL OR rider_id = ?) AND (CAST(? AS varchar) IS NULL OR EXISTS (SELECT 1"
            + " FROM execution_delivery d WHERE d.delivery_id = surcharge_request.delivery_id AND"
            + " d.mfc_id = ?)) ORDER BY "
            + (state == null ? "requested_at DESC, request_id DESC" : "requested_at, request_id")
            + " LIMIT ? OFFSET ?",
        (rs, i) -> row(rs),
        state,
        state,
        riderId,
        riderId,
        mfcId,
        mfcId,
        limit,
        offset);
  }

  @Transactional
  public Request decide(
      String requestId, String idemKey, String source, String decision, @Nullable String note) {
    PmartPrincipal p = staff();
    if (!(p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS"))) {
      throw ExecutionService.forbidden("추가 배달료 승인은 라이더 운영·관리자만 할 수 있습니다.");
    }
    String why = note == null ? null : note.strip();
    if ("REJECT".equals(decision) && (why == null || why.length() < 2)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "반려 사유를 적어 주세요.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("requestId", requestId);
    body.put("decision", decision);
    body.put("note", String.valueOf(why));
    idempotency.execute(
        new RequestKey(source, idemKey, "decideSurcharge", requestId),
        body,
        String.class,
        () -> apply(requestId, decision, why, p.subject()));
    return find(requestId).orElseThrow();
  }

  private String apply(String requestId, String decision, @Nullable String note, String by) {
    Instant now = clock.instant();
    Request r =
        jdbc
            .query(
                "SELECT * FROM surcharge_request WHERE request_id = ? FOR UPDATE",
                (rs, i) -> row(rs),
                requestId)
            .stream()
            .findFirst()
            .orElseThrow(() -> PmartException.notFound("SURCHARGE_NOT_FOUND", "신청이 없습니다."));
    if (!"REQUESTED".equals(r.state())) {
      throw conflict("NOT_DECIDABLE", "이미 처리한 신청입니다.");
    }
    String earningId =
        jdbc.queryForObject(
            "SELECT earning_id FROM surcharge_request WHERE request_id = ?",
            String.class,
            requestId);
    if ("APPROVE".equals(decision)) {
      EarningRow e =
          store
              .lockEarning(earningId)
              .filter(x -> x.calculable() && OPEN_EARNING.contains(x.settlementState()))
              .orElseThrow(() -> conflict("EARNING_NOT_OPEN", "그사이 수행료가 지급 중·지급됨이 되어 더할 수 없습니다."));
      jdbc.update(
          "UPDATE rider_earning SET total = COALESCE(total, 0) + ?, note = ? WHERE earning_id = ?",
          r.amount(),
          "엘리베이터 고장 추가 배달료 +" + r.amount() + "원(" + r.floor() + "층)",
          earningId);
      EarningRow after = store.lockEarning(earningId).orElseThrow();
      events.earningRecorded(after, store.bumpVersion(e.deliveryId(), now), now);
    }
    jdbc.update(
        "UPDATE surcharge_request SET state = ?, decided_by = ?, decided_at = ?, decision_note = ?"
            + " WHERE request_id = ?",
        "APPROVE".equals(decision) ? "APPROVED" : "REJECTED",
        by,
        Timestamp.from(now),
        note,
        requestId);
    log.info("추가 배달료 {} {}", requestId, decision);
    return requestId;
  }

  public java.util.Optional<Request> find(String requestId) {
    return jdbc
        .query(
            "SELECT * FROM surcharge_request WHERE request_id = ?", (rs, i) -> row(rs), requestId)
        .stream()
        .findFirst();
  }

  private Request row(java.sql.ResultSet rs) throws java.sql.SQLException {
    List<String> refs = new java.util.ArrayList<>();
    for (var n : mapper.readTree(rs.getString("evidence_refs"))) {
      refs.add(n.asString());
    }
    Timestamp decided = rs.getTimestamp("decided_at");
    return new Request(
        rs.getString("request_id"),
        rs.getString("delivery_id"),
        rs.getString("rider_id"),
        rs.getString("reason"),
        rs.getInt("floor"),
        rs.getInt("amount"),
        refs,
        rs.getString("state"),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getString("decided_by"),
        decided == null ? null : decided.toInstant(),
        rs.getString("decision_note"));
  }

  private static PmartPrincipal staff() {
    return PmartPrincipal.current()
        .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .orElseThrow(() -> ExecutionService.forbidden("직원만 할 수 있습니다."));
  }

  private static PmartException conflict(String code, String msg) {
    return new PmartException(code, HttpStatus.CONFLICT, msg, false, List.of());
  }

  private static PmartException unprocessable(String code, String msg) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, msg, false, List.of());
  }
}
