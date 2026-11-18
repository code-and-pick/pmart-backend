package com.pmart.notification.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.notification.domain.Notices;
import com.pmart.notification.infrastructure.RiderClient;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 라이더 공지(2026-11-18 — 사용자 "대상 고르기 + 알림함·푸시"). 라이더 운영·관리자(RIDER_OPS·OPERATOR_ADMIN)가 제목·본문을 써서 전체 또는
 * 그 센터 배달지역 라이더에게 보낸다 — 라이더마다 알림함 한 줄(RIDER_NOTICE, 분류 SYSTEM) + 커밋 뒤 휴대폰 푸시(키가 있을 때). 같은 직원·같은
 * Idempotency-Key 는 한 번만. 받는 라이더 목록은 rider 가 정한다(탈퇴 제외). 본문은 로그에 남기지 않는다.
 */
@Service
public class RiderNoticeService {

  private static final Logger log = LoggerFactory.getLogger(RiderNoticeService.class);

  public record Sent(
      String noticeId,
      String title,
      String body,
      String targetType,
      @Nullable String mfcId,
      String sentBy,
      Instant sentAt,
      int recipientCount) {}

  private final JdbcTemplate jdbc;
  private final RiderClient riders;
  private final NotificationService notifications;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RiderNoticeService(
      JdbcTemplate jdbc,
      RiderClient riders,
      NotificationService notifications,
      TransactionTemplate tx,
      Clock clock) {
    this.jdbc = jdbc;
    this.riders = riders;
    this.notifications = notifications;
    this.tx = tx;
    this.clock = clock;
  }

  static PmartPrincipal requireRiderOps() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .filter(p -> p.hasRole("RIDER_OPS") || p.hasRole("OPERATOR_ADMIN"))
        .orElseThrow(
            () ->
                new PmartException(
                    ErrorCode.FORBIDDEN.name(),
                    HttpStatus.FORBIDDEN,
                    "라이더 공지는 라이더 운영·관리자만 보낼 수 있습니다.",
                    false,
                    List.of()));
  }

  public Sent send(
      String idempotencyKey, String title, String body, String targetType, @Nullable String mfcId) {
    PmartPrincipal p = requireRiderOps();
    String t = title.strip();
    String b = body.strip();
    if (t.isEmpty() || t.length() > 40 || b.isEmpty() || b.length() > 500) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "제목은 1~40자, 본문은 1~500자입니다.");
    }
    boolean byMfc = "MFC".equals(targetType);
    if (byMfc && (mfcId == null || mfcId.isBlank())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "센터 대상이면 mfcId 가 필요합니다.");
    }
    String key = p.subject() + ":" + idempotencyKey;
    Optional<Sent> done = byKey(key);
    if (done.isPresent()) {
      return done.get();
    }
    // 받는 라이더는 트랜잭션 밖에서(외부 호출)
    List<String> ids = riders.recipients(byMfc ? mfcId : null);
    Instant now = clock.instant();
    String noticeId = "RNT-" + UUID.randomUUID();
    Notices.Notice n = new Notices.Notice("RIDER_NOTICE", "SYSTEM", t, b, "NONE", null);
    return tx.execute(
        s -> {
          int inserted =
              jdbc.update(
                  "INSERT INTO rider_notice (notice_id, request_key, title, body, target_type, mfc_id,"
                      + " sent_by, sent_at, recipient_count) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT"
                      + " (request_key) DO NOTHING",
                  noticeId,
                  key,
                  t,
                  b,
                  byMfc ? "MFC" : "ALL",
                  byMfc ? mfcId : null,
                  p.subject(),
                  Timestamp.from(now),
                  ids.size());
          if (inserted == 0) {
            return byKey(key).orElseThrow(); // 같은 요청이 먼저 들어감
          }
          for (String riderId : ids) {
            notifications.riderNotice(riderId, n, noticeId, now);
          }
          log.info("라이더 공지 {} — {} {}명", noticeId, byMfc ? mfcId : "전체", ids.size());
          return new Sent(
              noticeId,
              t,
              b,
              byMfc ? "MFC" : "ALL",
              byMfc ? mfcId : null,
              p.subject(),
              now,
              ids.size());
        });
  }

  public List<Sent> list(int limit, int offset) {
    requireRiderOps();
    return jdbc.query(
        "SELECT * FROM rider_notice ORDER BY sent_at DESC, notice_id DESC LIMIT ? OFFSET ?",
        (rs, i) -> row(rs),
        limit,
        offset);
  }

  private Optional<Sent> byKey(String key) {
    return jdbc
        .query("SELECT * FROM rider_notice WHERE request_key = ?", (rs, i) -> row(rs), key)
        .stream()
        .findFirst();
  }

  private static Sent row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Sent(
        rs.getString("notice_id"),
        rs.getString("title"),
        rs.getString("body"),
        rs.getString("target_type"),
        rs.getString("mfc_id"),
        rs.getString("sent_by"),
        rs.getTimestamp("sent_at").toInstant(),
        rs.getInt("recipient_count"));
  }
}
