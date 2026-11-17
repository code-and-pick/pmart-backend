package com.pmart.riderauth.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 라이더 주체·Google 연결·원인증 요청·기기 제한·세션·refresh token 저장(JDBC). Google 토큰·이메일은 저장하지 않는다. */
@Repository
public class RiderAuthStore {

  public record AuthRequestRow(
      String authRequestId,
      String deviceId,
      String state,
      @Nullable String failureCode,
      @Nullable String riderId,
      boolean newAccount,
      Instant createdAt) {}

  public record SessionRow(
      String sessionId, String riderId, String deviceId, String state, Instant expiresAt) {}

  public record RefreshRow(
      String tokenHash, String sessionId, Instant expiresAt, @Nullable Instant usedAt) {}

  private final JdbcTemplate jdbc;

  public RiderAuthStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 라이더 주체·Google 연결 ----------

  public Optional<String> accountState(String riderId) {
    return jdbc
        .queryForList("SELECT state FROM rider_account WHERE rider_id = ?", String.class, riderId)
        .stream()
        .findFirst();
  }

  public Optional<String> linkedRider(String provider, String subject) {
    return jdbc
        .queryForList(
            "SELECT rider_id FROM rider_identity WHERE provider = ? AND provider_subject = ?",
            String.class,
            provider,
            subject)
        .stream()
        .findFirst();
  }

  public void insertAccount(String riderId, Instant at) {
    jdbc.update(
        "INSERT INTO rider_account (rider_id, state, created_at, last_login_at) VALUES (?, 'ACTIVE', ?, ?)",
        riderId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 연결 생성. 같은 Google 계정이 동시에 들어오면 한쪽은 0(이미 연결됨). */
  public int insertIdentity(String provider, String subject, String riderId, Instant at) {
    return jdbc.update(
        "INSERT INTO rider_identity (provider, provider_subject, rider_id, linked_at, last_login_at)"
            + " VALUES (?,?,?,?,?) ON CONFLICT (provider, provider_subject) DO NOTHING",
        provider,
        subject,
        riderId,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public void recordLogin(String provider, String subject, String riderId, Instant at) {
    jdbc.update(
        "UPDATE rider_identity SET last_login_at = ? WHERE provider = ? AND provider_subject = ?",
        Timestamp.from(at),
        provider,
        subject);
    jdbc.update(
        "UPDATE rider_account SET last_login_at = ? WHERE rider_id = ?",
        Timestamp.from(at),
        riderId);
  }

  // ---------- 원인증 요청 ----------

  public Optional<AuthRequestRow> requestByKey(String sourceSystem, String idempotencyKey) {
    return jdbc
        .query(
            "SELECT * FROM rider_auth_request WHERE source_system = ? AND idempotency_key = ?",
            (rs, i) -> request(rs),
            sourceSystem,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  public Optional<AuthRequestRow> request(String authRequestId) {
    return jdbc
        .query(
            "SELECT * FROM rider_auth_request WHERE auth_request_id = ?",
            (rs, i) -> request(rs),
            authRequestId)
        .stream()
        .findFirst();
  }

  public void insertRequest(
      String authRequestId,
      String sourceSystem,
      String idempotencyKey,
      String deviceId,
      String provider,
      String state,
      @Nullable String failureCode,
      @Nullable String riderId,
      boolean newAccount,
      Instant at) {
    jdbc.update(
        "INSERT INTO rider_auth_request (auth_request_id, source_system, idempotency_key, device_id,"
            + " provider, state, failure_code, rider_id, new_account, created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
        authRequestId,
        sourceSystem,
        idempotencyKey,
        deviceId,
        provider,
        state,
        failureCode,
        riderId,
        newAccount,
        Timestamp.from(at));
  }

  // ---------- 실패 제한 ----------

  public int failuresSince(String deviceId, Instant since) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM rider_auth_request WHERE device_id = ?"
                + " AND failure_code = 'PROVIDER_REJECTED' AND created_at > ?",
            Integer.class,
            deviceId,
            Timestamp.from(since));
    return n == null ? 0 : n;
  }

  public Optional<Instant> restrictedUntil(String deviceId) {
    return jdbc
        .queryForList(
            "SELECT restricted_until FROM rider_auth_restriction WHERE device_id = ?",
            Timestamp.class,
            deviceId)
        .stream()
        .findFirst()
        .map(Timestamp::toInstant);
  }

  public void restrict(String deviceId, Instant until) {
    jdbc.update(
        "INSERT INTO rider_auth_restriction (device_id, restricted_until) VALUES (?,?)"
            + " ON CONFLICT (device_id) DO UPDATE SET restricted_until = EXCLUDED.restricted_until",
        deviceId,
        Timestamp.from(until));
  }

  /** 같은 기기의 요청을 직렬화(실패 집계·제한 판정 경합 방지). */
  public void lockDevice(String deviceId) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "rider-auth:" + deviceId);
  }

  // ---------- 세션·refresh token ----------

  public void insertSession(
      String sessionId,
      String riderId,
      String deviceId,
      String sourceSystem,
      Instant at,
      Instant expiresAt) {
    jdbc.update(
        "INSERT INTO rider_session (session_id, rider_id, device_id, source_system, state, created_at,"
            + " expires_at) VALUES (?,?,?,?,'ACTIVE',?,?)",
        sessionId,
        riderId,
        deviceId,
        sourceSystem,
        Timestamp.from(at),
        Timestamp.from(expiresAt));
  }

  public Optional<SessionRow> session(String sessionId, boolean forUpdate) {
    return jdbc
        .query(
            "SELECT session_id, rider_id, device_id, state, expires_at FROM rider_session"
                + " WHERE session_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""),
            (rs, i) ->
                new SessionRow(
                    rs.getString("session_id"),
                    rs.getString("rider_id"),
                    rs.getString("device_id"),
                    rs.getString("state"),
                    rs.getTimestamp("expires_at").toInstant()),
            sessionId)
        .stream()
        .findFirst();
  }

  public void revokeSession(String sessionId, String reason, Instant at) {
    jdbc.update(
        "UPDATE rider_session SET state = 'REVOKED', revoked_at = ?, revoke_reason = ?"
            + " WHERE session_id = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        reason,
        sessionId);
  }

  public void revokeAllSessions(String riderId, String reason, Instant at) {
    jdbc.update(
        "UPDATE rider_session SET state = 'REVOKED', revoked_at = ?, revoke_reason = ?"
            + " WHERE rider_id = ? AND state = 'ACTIVE'",
        Timestamp.from(at),
        reason,
        riderId);
  }

  /** 마지막 사용 기준 연장 + 갱신 시각 기록. */
  public void refreshed(String sessionId, Instant at, Instant expiresAt) {
    jdbc.update(
        "UPDATE rider_session SET last_refreshed_at = ?, expires_at = ? WHERE session_id = ?",
        Timestamp.from(at),
        Timestamp.from(expiresAt),
        sessionId);
  }

  public void insertRefresh(String tokenHash, String sessionId, Instant at, Instant expiresAt) {
    jdbc.update(
        "INSERT INTO rider_refresh_token (token_hash, session_id, issued_at, expires_at) VALUES (?,?,?,?)",
        tokenHash,
        sessionId,
        Timestamp.from(at),
        Timestamp.from(expiresAt));
  }

  public Optional<RefreshRow> lockRefresh(String tokenHash) {
    return jdbc
        .query(
            "SELECT token_hash, session_id, expires_at, used_at FROM rider_refresh_token"
                + " WHERE token_hash = ? FOR UPDATE",
            (rs, i) ->
                new RefreshRow(
                    rs.getString("token_hash"),
                    rs.getString("session_id"),
                    rs.getTimestamp("expires_at").toInstant(),
                    instant(rs, "used_at")),
            tokenHash)
        .stream()
        .findFirst();
  }

  public void markRefreshUsed(String tokenHash, Instant at) {
    jdbc.update(
        "UPDATE rider_refresh_token SET used_at = ? WHERE token_hash = ?",
        Timestamp.from(at),
        tokenHash);
  }

  private static AuthRequestRow request(ResultSet rs) throws SQLException {
    return new AuthRequestRow(
        rs.getString("auth_request_id"),
        rs.getString("device_id"),
        rs.getString("state"),
        rs.getString("failure_code"),
        rs.getString("rider_id"),
        rs.getBoolean("new_account"),
        rs.getTimestamp("created_at").toInstant());
  }

  private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  /** 탈퇴(2026-11-17): 계정 DISABLED, Google 연결 지움(다음 로그인은 새 라이더 주체). */
  public void disableAndUnlink(String riderId) {
    jdbc.update("UPDATE rider_account SET state = 'DISABLED' WHERE rider_id = ?", riderId);
    jdbc.update("DELETE FROM rider_identity WHERE rider_id = ?", riderId);
  }
}
