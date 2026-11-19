package com.pmart.notification.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 단말 푸시 토큰 표(V3 — {@code push_token}). 토큰 원문은 저장만 하고 읽어 내보내는 질의는 두지 않는다(발송은 아직 없음). 목록·응답용 행에는 토큰이
 * 없다.
 */
@Repository
public class PushTokenStore {

  /** 등록된 단말(토큰 값 없음). */
  public record PushDeviceRow(
      String deviceId,
      String platform,
      @Nullable String appVersion,
      Instant registeredAt,
      Instant updatedAt) {}

  private final JdbcTemplate jdbc;

  public PushTokenStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 트랜잭션 끝까지 잡는 잠금(같은 토큰·같은 주인의 등록을 줄 세운다). 키에 토큰 원문을 쓰지 않는다. */
  public void lock(String key) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, key);
  }

  /** 같은 토큰이 다른 주인·단말에 있으면 지운다(토큰 하나는 한 번에 한 주인). 지운 줄 수. */
  public int deleteTokenElsewhere(
      String tokenSha256, String ownerType, String ownerId, String deviceId) {
    return jdbc.update(
        "DELETE FROM push_token WHERE token_sha256 = ?"
            + " AND NOT (owner_type = ? AND owner_id = ? AND device_id = ?)",
        tokenSha256,
        ownerType,
        ownerId,
        deviceId);
  }

  /** 주인·단말 한 줄을 넣거나 토큰·앱 버전·갱신 시각을 바꾼다(처음 등록 시각은 그대로). */
  public void upsert(
      String ownerType,
      String ownerId,
      String deviceId,
      String token,
      String tokenSha256,
      String platform,
      @Nullable String appVersion,
      Instant now) {
    jdbc.update(
        "INSERT INTO push_token (owner_type, owner_id, device_id, token, token_sha256, platform,"
            + " app_version, registered_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (owner_type, owner_id, device_id) DO UPDATE SET token = EXCLUDED.token,"
            + " token_sha256 = EXCLUDED.token_sha256, platform = EXCLUDED.platform,"
            + " app_version = EXCLUDED.app_version, updated_at = EXCLUDED.updated_at",
        ownerType,
        ownerId,
        deviceId,
        token,
        tokenSha256,
        platform,
        appVersion,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  /** 주인의 단말이 {@code max} 개를 넘으면 가장 오래 갱신되지 않은 것부터 지운다({@code keepDeviceId} 는 남긴다). 지운 줄 수. */
  public int evictBeyond(String ownerType, String ownerId, String keepDeviceId, int max) {
    return jdbc.update(
        "DELETE FROM push_token WHERE owner_type = ? AND owner_id = ? AND device_id IN ("
            + " SELECT device_id FROM push_token WHERE owner_type = ? AND owner_id = ?"
            + " AND device_id <> ? ORDER BY updated_at DESC, device_id DESC OFFSET ?)",
        ownerType,
        ownerId,
        ownerType,
        ownerId,
        keepDeviceId,
        Math.max(0, max - 1));
  }

  /** 주인의 모든 단말(2026-11-19 — 라이더 탈퇴). 지운 수. */
  public int deleteOwner(String ownerType, String ownerId) {
    return jdbc.update(
        "DELETE FROM push_token WHERE owner_type = ? AND owner_id = ?", ownerType, ownerId);
  }

  public int delete(String ownerType, String ownerId, String deviceId) {
    return jdbc.update(
        "DELETE FROM push_token WHERE owner_type = ? AND owner_id = ? AND device_id = ?",
        ownerType,
        ownerId,
        deviceId);
  }

  public Optional<PushDeviceRow> find(String ownerType, String ownerId, String deviceId) {
    return jdbc
        .query(
            "SELECT device_id, platform, app_version, registered_at, updated_at FROM push_token"
                + " WHERE owner_type = ? AND owner_id = ? AND device_id = ?",
            (rs, i) -> row(rs),
            ownerType,
            ownerId,
            deviceId)
        .stream()
        .findFirst();
  }

  /** 최근 갱신 순. */
  public List<PushDeviceRow> list(String ownerType, String ownerId) {
    return jdbc.query(
        "SELECT device_id, platform, app_version, registered_at, updated_at FROM push_token"
            + " WHERE owner_type = ? AND owner_id = ? ORDER BY updated_at DESC, device_id DESC",
        (rs, i) -> row(rs),
        ownerType,
        ownerId);
  }

  private static PushDeviceRow row(ResultSet rs) throws SQLException {
    return new PushDeviceRow(
        rs.getString("device_id"),
        rs.getString("platform"),
        rs.getString("app_version"),
        rs.getTimestamp("registered_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  /** 발송용 토큰(원문) — 라이더 푸시(2026-11-17). 로그에 남기지 않는다. */
  public record TokenRow(String deviceId, String token) {
    @Override
    public String toString() {
      return "TokenRow[" + deviceId + ", 가림]";
    }
  }

  public List<TokenRow> tokens(String ownerType, String ownerId) {
    return jdbc.query(
        "SELECT device_id, token FROM push_token WHERE owner_type = ? AND owner_id = ? ORDER BY"
            + " updated_at DESC",
        (rs, i) -> new TokenRow(rs.getString(1), rs.getString(2)),
        ownerType,
        ownerId);
  }
}
