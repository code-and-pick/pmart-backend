package com.pmart.rider.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 라이더 휴대폰 번호 확인·탈퇴 번호 재가입 막기(V12, 2026-11-19). */
@Repository
public class PhoneVerificationStore {

  public record Row(
      String verificationId,
      String subject,
      String phoneHash,
      String code,
      String state,
      int attempts,
      @Nullable String failureReason,
      Instant expiresAt,
      Instant createdAt,
      @Nullable Instant finishedAt) {}

  private final JdbcTemplate jdbc;

  public PhoneVerificationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int startsSince(String subject, Instant since) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM rider_phone_verification WHERE subject = ? AND created_at >= ?",
            Integer.class,
            subject,
            Timestamp.from(since));
    return n == null ? 0 : n;
  }

  @Transactional
  public void start(Row r) {
    jdbc.update(
        "UPDATE rider_phone_verification SET state = 'EXPIRED', finished_at = ? WHERE subject = ? AND"
            + " state = 'PENDING'",
        Timestamp.from(r.createdAt()),
        r.subject());
    jdbc.update(
        "INSERT INTO rider_phone_verification (verification_id, subject, phone_hash, code, state,"
            + " attempts, expires_at, created_at) VALUES (?,?,?,?,'PENDING',0,?,?)",
        r.verificationId(),
        r.subject(),
        r.phoneHash(),
        r.code(),
        Timestamp.from(r.expiresAt()),
        Timestamp.from(r.createdAt()));
  }

  public Optional<Row> find(String verificationId) {
    return jdbc
        .query(
            "SELECT * FROM rider_phone_verification WHERE verification_id = ?",
            (rs, i) ->
                new Row(
                    rs.getString("verification_id"),
                    rs.getString("subject"),
                    rs.getString("phone_hash"),
                    rs.getString("code"),
                    rs.getString("state"),
                    rs.getInt("attempts"),
                    rs.getString("failure_reason"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("finished_at") == null
                        ? null
                        : rs.getTimestamp("finished_at").toInstant()),
            verificationId)
        .stream()
        .findFirst();
  }

  public int finish(String verificationId, String state, @Nullable String reason, Instant at) {
    return jdbc.update(
        "UPDATE rider_phone_verification SET state = ?, failure_reason = ?, finished_at = ? WHERE"
            + " verification_id = ? AND state = 'PENDING'",
        state,
        reason,
        Timestamp.from(at),
        verificationId);
  }

  public int miss(String verificationId, int maxAttempts, Instant at) {
    return jdbc.update(
        "UPDATE rider_phone_verification SET attempts = attempts + 1, state = CASE WHEN attempts + 1"
            + " >= ? THEN 'FAILED' ELSE state END, failure_reason = CASE WHEN attempts + 1 >= ? THEN"
            + " 'TOO_MANY_ATTEMPTS' ELSE failure_reason END, finished_at = CASE WHEN attempts + 1 >= ?"
            + " THEN ? ELSE finished_at END WHERE verification_id = ? AND state = 'PENDING'",
        maxAttempts,
        maxAttempts,
        maxAttempts,
        Timestamp.from(at),
        verificationId);
  }

  public int verify(String verificationId, Instant at) {
    return jdbc.update(
        "UPDATE rider_phone_verification SET state = 'VERIFIED', finished_at = ? WHERE"
            + " verification_id = ? AND state = 'PENDING'",
        Timestamp.from(at),
        verificationId);
  }

  /** 탈퇴 번호 막기(rejoin-block) — 같은 번호면 더 늦은 기한으로. */
  public void block(String phoneHash, Instant until, Instant now) {
    jdbc.update(
        "INSERT INTO rider_rejoin_block (phone_hash, blocked_until, created_at) VALUES (?,?,?) ON"
            + " CONFLICT (phone_hash) DO UPDATE SET blocked_until = GREATEST(rider_rejoin_block.blocked_until,"
            + " EXCLUDED.blocked_until)",
        phoneHash,
        Timestamp.from(until),
        Timestamp.from(now));
  }

  public Optional<Instant> blockedUntil(String phoneHash, Instant now) {
    return jdbc
        .query(
            "SELECT blocked_until FROM rider_rejoin_block WHERE phone_hash = ? AND blocked_until > ?",
            (rs, i) -> rs.getTimestamp(1).toInstant(),
            phoneHash,
            Timestamp.from(now))
        .stream()
        .findFirst();
  }

  /** 탈퇴 뒤 부정 이용 방지 보관(처리방침 §3) — 이름·전화를 기한까지. 같은 라이더면 덮어쓴다. */
  public void hold(
      String riderId, @Nullable String name, @Nullable String phone, Instant until, Instant now) {
    jdbc.update(
        "INSERT INTO rider_withdrawal_hold (rider_id, name, phone, held_until, created_at) VALUES"
            + " (?,?,?,?,?) ON CONFLICT (rider_id) DO UPDATE SET name = EXCLUDED.name, phone ="
            + " EXCLUDED.phone, held_until = EXCLUDED.held_until",
        riderId,
        name,
        phone,
        Timestamp.from(until),
        Timestamp.from(now));
  }

  /** 기한 지난 탈퇴 보관(이름·전화)과 재가입 막기 해시를 지운다. 지운 보관 건수. */
  public int purgeExpired(Instant now) {
    Timestamp t = Timestamp.from(now);
    int held = jdbc.update("DELETE FROM rider_withdrawal_hold WHERE held_until <= ?", t);
    jdbc.update("DELETE FROM rider_rejoin_block WHERE blocked_until <= ?", t);
    return held;
  }
}
