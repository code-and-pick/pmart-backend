package com.pmart.riderauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.riderauth.infrastructure.KakaoUnlinkClient;
import com.pmart.riderauth.infrastructure.RiderAuthStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 라이더 탈퇴(2026-11-17 — rider {@code RiderWithdrawn}, 사용자 결정 "즉시 가림·재가입 허용"). 진행 중 배송이 없을 때만 탈퇴되므로
 * 기다리지 않고 바로: 모든 세션을 끊고 {@code SubjectBlocked}(ACCOUNT_DISABLED — 이미 나간 access token 도 게이트웨이에서
 * 401), 계정을 DISABLED, Google 연결을 지운다 — 같은 Google 계정의 다음 로그인은 새 라이더 주체(새로 가입). inbox 트랜잭션 안.
 *
 * <p>카카오(2026-11-22 RA-1 ① — 고객과 같음, 사용자 "동의는 처음 가입할 때만"): 지우기 전에 카카오 sub 를 읽어 두고 <b>커밋 뒤</b> 카카오 앱
 * 연결을 끊는다 — 다시 가입할 때 동의 화면이 뜬다. Admin 키가 없거나 실패하면 로그만(탈퇴는 그대로). 재처리로 다시 와도 연결이 이미 지워져 다시 부르지 않는다.
 * 네이버는 탈퇴 재인증의 코드 교환(purpose WITHDRAWAL)이 끊는다({@link NaverCodeExchangeService}).
 */
@Service
public class RiderWithdrawals {

  private static final Logger log = LoggerFactory.getLogger(RiderWithdrawals.class);

  public static final Set<String> TYPES = Set.of("RiderWithdrawn");

  private final RiderAuthStore store;
  private final SessionBlocks blocks;
  private final Clock clock;
  private final KakaoUnlinkClient kakao;

  public RiderWithdrawals(
      RiderAuthStore store, SessionBlocks blocks, Clock clock, KakaoUnlinkClient kakao) {
    this.store = store;
    this.blocks = blocks;
    this.clock = clock;
    this.kakao = kakao;
  }

  public void on(EventEnvelope e) {
    String riderId = e.payload().path("riderId").asString("");
    if (riderId.isBlank()) {
      log.warn("RiderWithdrawn {} riderId 없음 — 버림", e.eventId());
      return;
    }
    Instant now = clock.instant();
    List<String> kakaoIds = store.subjects(riderId, "KAKAO");
    store.revokeAllSessions(riderId, "RIDER_WITHDRAWN", now);
    store.disableAndUnlink(riderId);
    blocks.block(riderId, null, now, "ACCOUNT_DISABLED");
    log.info("라이더 {} 탈퇴 — 세션 끊음·계정 사용 안 함·소셜 로그인 연결 지움", riderId);
    if (kakaoIds.isEmpty()) {
      return;
    }
    Runnable unlink =
        () -> kakaoIds.forEach(id -> log.info("카카오 연결 끊기(탈퇴) ok={}", kakao.unlink(id)));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              unlink.run();
            }
          });
    } else {
      unlink.run();
    }
  }
}
