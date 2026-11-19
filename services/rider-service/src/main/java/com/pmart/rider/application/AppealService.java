package com.pmart.rider.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.rider.domain.Rider;
import com.pmart.rider.infrastructure.RiderStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 라이더 소명·이의 신청(2026-11-18 — 사용자 "앱에서 신청 → 백오피스 결정", 원문 §9.6 "사유·기간·통지·소명 결과 기록"). 대상(지금 계정 제한, 또는
 * 배송 수행료)마다 한 번. 라이더 운영·관리자가 유지(UPHELD)·인정(ACCEPTED)으로 결정하고, 제한 인정이면 바로 정상으로 푼다. 수행료 인정은 결정만 — 금액은
 * execution 수행료 조정에서 따로. 결정은 RiderAppealDecided(설명 글 없음) → 라이더 알림함.
 */
@Service
public class AppealService {

  public record Appeal(
      String appealId,
      String riderId,
      String targetType,
      String targetRef,
      @Nullable String restriction,
      String body,
      String state,
      Instant submittedAt,
      @Nullable String decidedBy,
      @Nullable Instant decidedAt,
      @Nullable String decisionNote) {

    public boolean adjustmentRequired() {
      return "EARNING".equals(targetType) && "ACCEPTED".equals(state);
    }
  }

  private final JdbcTemplate jdbc;
  private final RiderStore store;
  private final RiderService riders;
  private final EventPublisher events;
  private final TransactionTemplate tx;
  private final Clock clock;

  public AppealService(
      JdbcTemplate jdbc,
      RiderStore store,
      RiderService riders,
      EventPublisher events,
      TransactionTemplate tx,
      Clock clock) {
    this.jdbc = jdbc;
    this.store = store;
    this.riders = riders;
    this.events = events;
    this.tx = tx;
    this.clock = clock;
  }

  private static PmartPrincipal rider() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.RIDER)
        .orElseThrow(
            () ->
                new PmartException(
                    ErrorCode.FORBIDDEN.name(),
                    HttpStatus.FORBIDDEN,
                    "라이더만 신청할 수 있습니다.",
                    false,
                    List.of()));
  }

  public Appeal submit(
      String idempotencyKey, String targetType, @Nullable String targetRef, String body) {
    PmartPrincipal p = rider();
    String riderId = p.subject();
    String text = body.strip();
    if (text.isEmpty() || text.length() > 1000) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "내용은 1~1000자입니다.");
    }
    String key = riderId + ":" + idempotencyKey;
    return tx.execute(
        s -> {
          Optional<Appeal> same = one("WHERE request_key = ?", key);
          if (same.isPresent()) {
            return same.get();
          }
          Rider r = riders.get(riderId);
          String ref;
          String restriction = null;
          if ("RESTRICTION".equals(targetType)) {
            if (r.restriction() == Rider.Restriction.NORMAL) {
              throw PmartException.invalidState("지금 걸린 계정 제한이 없습니다.");
            }
            restriction = r.restriction().name();
            ref =
                store.restrictions(riderId, 1).stream()
                    .findFirst()
                    .map(RiderStore.RestrictionRow::requestId)
                    .orElse("CURRENT");
          } else if ("EARNING".equals(targetType)) {
            if (targetRef == null || !targetRef.matches("[A-Za-z0-9._:-]{1,64}")) {
              throw new PmartException(
                  ErrorCode.VALIDATION_FAILED, "수행료 이의는 배송 ID(targetRef)가 필요합니다.");
            }
            ref = targetRef;
          } else {
            throw new PmartException(ErrorCode.VALIDATION_FAILED, "알 수 없는 대상입니다.");
          }
          if (one(
                  "WHERE rider_id = ? AND target_type = ? AND target_ref = ?",
                  riderId,
                  targetType,
                  ref)
              .isPresent()) {
            throw new PmartException(
                "APPEAL_EXISTS", HttpStatus.CONFLICT, "이 대상에는 이미 신청했습니다.", false, List.of());
          }
          String id = "RAP-" + UUID.randomUUID();
          jdbc.update(
              "INSERT INTO rider_appeal (appeal_id, request_key, rider_id, target_type, target_ref,"
                  + " restriction, body, submitted_at) VALUES (?,?,?,?,?,?,?,?)",
              id,
              key,
              riderId,
              targetType,
              ref,
              restriction,
              text,
              Timestamp.from(clock.instant()));
          return one("WHERE appeal_id = ?", id).orElseThrow();
        });
  }

  /** 내부(execution 보상금 확인 — 2026-11-19). */
  public Appeal internal(String appealId) {
    return one("WHERE appeal_id = ?", appealId)
        .orElseThrow(() -> PmartException.notFound("APPEAL_NOT_FOUND", "신청이 없습니다."));
  }

  public List<Appeal> mine() {
    String riderId = rider().subject();
    return list("WHERE rider_id = ? ORDER BY submitted_at DESC LIMIT 100", riderId).stream()
        .map(
            a ->
                new Appeal(
                    a.appealId(),
                    a.riderId(),
                    a.targetType(),
                    a.targetRef(),
                    a.restriction(),
                    a.body(),
                    a.state(),
                    a.submittedAt(),
                    null,
                    a.decidedAt(),
                    a.decisionNote()))
        .toList();
  }

  public List<Appeal> forStaff(
      @Nullable String state, @Nullable String riderId, @Nullable String targetType) {
    RiderService.requireRestrictionAdmin();
    StringBuilder where = new StringBuilder("WHERE state = ?");
    List<Object> args = new ArrayList<>(List.of(state == null ? "OPEN" : state));
    if (riderId != null) {
      where.append(" AND rider_id = ?");
      args.add(riderId);
    }
    if (targetType != null) {
      where.append(" AND target_type = ?");
      args.add(targetType);
    }
    where.append(" ORDER BY submitted_at, appeal_id LIMIT 200");
    return list(where.toString(), args.toArray());
  }

  public Appeal decide(String appealId, String idempotencyKey, String decision, String note) {
    PmartPrincipal p = RiderService.requireRestrictionAdmin();
    String n = note.strip();
    if (n.isEmpty() || n.length() > 300) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "결과 설명은 1~300자입니다.");
    }
    Appeal a =
        one("WHERE appeal_id = ?", appealId)
            .orElseThrow(() -> PmartException.notFound("APPEAL_NOT_FOUND", "신청이 없습니다."));
    if (!"OPEN".equals(a.state())) {
      if (decision.equals(a.state()) && p.subject().equals(a.decidedBy())) {
        return a; // 같은 결정을 다시 보냄
      }
      throw PmartException.invalidState("이미 결정된 신청입니다(" + a.state() + ").");
    }
    // 제한 인정 → 바로 정상으로(제한 이력·RiderRestricted 는 기존 흐름). 이미 풀렸으면 결정만.
    if ("ACCEPTED".equals(decision)
        && "RESTRICTION".equals(a.targetType())
        && riders.get(a.riderId()).restriction() != Rider.Restriction.NORMAL) {
      riders.changeRestriction(
          a.riderId(),
          "appeal-" + appealId,
          "rider-appeal",
          null,
          new RiderService.RestrictionChange(
              "NORMAL", "소명 인정(" + appealId + ")", null, List.of(), null));
    }
    return tx.execute(
        s -> {
          Instant now = clock.instant();
          int changed =
              jdbc.update(
                  "UPDATE rider_appeal SET state = ?, decided_by = ?, decided_at = ?, decision_note = ?"
                      + " WHERE appeal_id = ? AND state = 'OPEN'",
                  decision,
                  p.subject(),
                  Timestamp.from(now),
                  n,
                  appealId);
          if (changed == 0) {
            throw PmartException.invalidState("이미 결정된 신청입니다.");
          }
          Map<String, Object> e = new LinkedHashMap<>();
          e.put("riderId", a.riderId());
          e.put("appealId", appealId);
          e.put("targetType", a.targetType());
          e.put("decision", decision);
          e.put("decidedAt", now.toString());
          events.publish(
              "RiderAppealDecided",
              1,
              "Rider",
              a.riderId(),
              (long) riders.get(a.riderId()).version(),
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
              e);
          return one("WHERE appeal_id = ?", appealId).orElseThrow();
        });
  }

  private Optional<Appeal> one(String where, Object... args) {
    return list(where, args).stream().findFirst();
  }

  private List<Appeal> list(String where, Object... args) {
    return jdbc.query("SELECT * FROM rider_appeal " + where, AppealService::row, args);
  }

  private static Appeal row(ResultSet rs, int i) throws SQLException {
    Timestamp decided = rs.getTimestamp("decided_at");
    return new Appeal(
        rs.getString("appeal_id"),
        rs.getString("rider_id"),
        rs.getString("target_type"),
        rs.getString("target_ref"),
        rs.getString("restriction"),
        rs.getString("body"),
        rs.getString("state"),
        rs.getTimestamp("submitted_at").toInstant(),
        rs.getString("decided_by"),
        decided == null ? null : decided.toInstant(),
        rs.getString("decision_note"));
  }
}
