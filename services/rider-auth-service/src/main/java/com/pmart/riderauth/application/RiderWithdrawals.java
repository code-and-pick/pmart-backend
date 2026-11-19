package com.pmart.riderauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.riderauth.infrastructure.RiderAuthStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 라이더 탈퇴(2026-11-17 — rider {@code RiderWithdrawn}, 사용자 결정 "즉시 가림·재가입 허용"). 진행 중 배송이 없을 때만 탈퇴되므로
 * 기다리지 않고 바로: 모든 세션을 끊고 {@code SubjectBlocked}(ACCOUNT_DISABLED — 이미 나간 access token 도 게이트웨이에서
 * 401), 계정을 DISABLED, Google 연결을 지운다 — 같은 Google 계정의 다음 로그인은 새 라이더 주체(새로 가입). inbox 트랜잭션 안.
 */
@Service
public class RiderWithdrawals {

  private static final Logger log = LoggerFactory.getLogger(RiderWithdrawals.class);

  public static final Set<String> TYPES = Set.of("RiderWithdrawn");

  private final RiderAuthStore store;
  private final SessionBlocks blocks;
  private final Clock clock;

  public RiderWithdrawals(RiderAuthStore store, SessionBlocks blocks, Clock clock) {
    this.store = store;
    this.blocks = blocks;
    this.clock = clock;
  }

  public void on(EventEnvelope e) {
    String riderId = e.payload().path("riderId").asString("");
    if (riderId.isBlank()) {
      log.warn("RiderWithdrawn {} riderId 없음 — 버림", e.eventId());
      return;
    }
    Instant now = clock.instant();
    store.revokeAllSessions(riderId, "RIDER_WITHDRAWN", now);
    store.disableAndUnlink(riderId);
    blocks.block(riderId, null, now, "ACCOUNT_DISABLED");
    log.info("라이더 {} 탈퇴 — 세션 끊음·계정 사용 안 함·소셜 로그인 연결 지움", riderId);
  }
}
