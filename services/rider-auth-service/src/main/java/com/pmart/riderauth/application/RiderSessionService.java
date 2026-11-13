package com.pmart.riderauth.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.riderauth.infrastructure.RiderAuthStore;
import com.pmart.riderauth.infrastructure.RiderAuthStore.RefreshRow;
import com.pmart.riderauth.infrastructure.RiderAuthStore.SessionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 토큰 갱신·로그아웃·현재 세션. refresh token 은 한 번만 쓴다 — 쓴 토큰이 다시 오면 탈취로 보고 세션을 폐기한다(TOKEN_REUSED). 라이더 주체 상태는
 * 갱신 때마다 다시 읽는다. 세션은 마지막 사용(갱신) 기준으로 연장한다.
 */
@Service
public class RiderSessionService {

  private static final Logger log = LoggerFactory.getLogger(RiderSessionService.class);

  public record Current(String sessionId, String riderId, Instant issuedAt, Instant expiresAt) {}

  private final RiderAuthStore store;
  private final RiderTokens tokens;
  private final RiderAuthProperties props;
  private final TransactionTemplate tx;
  private final SessionBlocks blocks;
  private final Clock clock;

  public RiderSessionService(
      RiderAuthStore store,
      RiderTokens tokens,
      RiderAuthProperties props,
      TransactionTemplate tx,
      SessionBlocks blocks,
      Clock clock) {
    this.store = store;
    this.tokens = tokens;
    this.props = props;
    this.tx = tx;
    this.blocks = blocks;
    this.clock = clock;
  }

  public RiderTokens.Tokens refresh(String refreshToken, @Nullable String deviceId) {
    Instant now = clock.instant();
    String[] reason = new String[1];
    RiderTokens.Tokens result =
        tx.execute(
            status -> {
              RefreshRow r = store.lockRefresh(RiderTokens.hash(refreshToken)).orElse(null);
              if (r == null) {
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              SessionRow s = store.session(r.sessionId(), true).orElseThrow();
              if (r.usedAt() != null) {
                store.revokeSession(s.sessionId(), "REFRESH_REUSED", now);
                // 그 세션의 access token 도 게이트웨이에서 바로 막는다(2026-11-13)
                blocks.block(s.riderId(), s.sessionId(), now, "TOKEN_REUSED");
                log.warn("refresh token 재사용 감지: sessionId={}", s.sessionId());
                reason[0] = "TOKEN_REUSED";
                return null;
              }
              if (!"ACTIVE".equals(s.state())
                  || !r.expiresAt().isAfter(now)
                  || !s.expiresAt().isAfter(now)
                  || !s.deviceId().equals(deviceId)) {
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              if (!"ACTIVE".equals(store.accountState(s.riderId()).orElse(null))) {
                store.revokeSession(s.sessionId(), "ACCOUNT_DISABLED", now);
                reason[0] = "TOKEN_INVALID";
                return null;
              }
              Instant extended = now.plus(props.sessionMaxAge());
              store.markRefreshUsed(r.tokenHash(), now);
              store.refreshed(s.sessionId(), now, extended);
              return tokens.issue(s.riderId(), s.sessionId(), extended, now);
            });
    if (result == null) {
      throw new PmartException(
          reason[0],
          HttpStatus.UNAUTHORIZED,
          "TOKEN_REUSED".equals(reason[0])
              ? "이미 사용한 refresh token 입니다. 세션을 종료했으니 다시 로그인하세요."
              : "refresh token 이 유효하지 않습니다. 다시 로그인하세요.",
          false,
          List.of());
    }
    return result;
  }

  /** 현재 토큰의 세션(또는 그 라이더의 모든 세션) 종료. 이미 종료여도 성공. */
  public void logout(boolean allDevices) {
    Jwt jwt = riderJwt();
    Instant now = clock.instant();
    tx.executeWithoutResult(
        status -> {
          // 이미 나간 access token 도 바로 401 — 게이트웨이 Redis 차단 목록(2026-11-13)
          String sid = jwt.getClaimAsString("sid");
          if (allDevices) {
            store.revokeAllSessions(jwt.getSubject(), "LOGOUT_ALL", now);
            blocks.block(jwt.getSubject(), null, now, "LOGOUT_ALL");
          } else if (sid != null) {
            store.revokeSession(sid, "LOGOUT", now);
            blocks.block(jwt.getSubject(), sid, now, "LOGOUT");
          }
        });
  }

  public Current current() {
    Jwt jwt = riderJwt();
    String sid = jwt.getClaimAsString("sid");
    SessionRow s = (sid == null ? null : store.session(sid, false).orElse(null));
    if (s == null
        || !"ACTIVE".equals(s.state())
        || !s.expiresAt().isAfter(clock.instant())
        || !s.riderId().equals(jwt.getSubject())) {
      throw new PmartException(
          "SESSION_EXPIRED", HttpStatus.UNAUTHORIZED, "세션이 종료되었습니다. 다시 로그인하세요.", false, List.of());
    }
    return new Current(
        s.sessionId(),
        s.riderId(),
        Objects.requireNonNull(jwt.getIssuedAt()),
        Objects.requireNonNull(jwt.getExpiresAt()));
  }

  /** 이 서비스가 발급한 라이더 토큰만(다른 발급자·종류는 403). */
  private Jwt riderJwt() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.getPrincipal() instanceof Jwt jwt
        && RiderTokens.TYPE.equals(jwt.getClaimAsString("typ"))
        && jwt.getIssuer() != null
        && props.issuer().equals(jwt.getIssuer().toString())) {
      return jwt;
    }
    throw new PmartException(ErrorCode.FORBIDDEN, "라이더 토큰이 필요합니다.");
  }
}
