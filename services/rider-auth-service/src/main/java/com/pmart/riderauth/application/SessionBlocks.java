package com.pmart.riderauth.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 로그아웃·refresh 재사용 때 이미 나간 라이더 access token 을 바로 막는 사건(계약 {@code SubjectBlocked}, 토픽
 * platform.subject-block.v1 — 2026-11-13, auth-service·employee-auth-service 와 같은 방식). {@code
 * sessionId} 가 있으면 그 세션만(LOGOUT·TOKEN_REUSED), 없으면 그 라이더의 모든 토큰(LOGOUT_ALL). 게이트웨이가 Redis 차단 목록에
 * 넣는다. 세션 종료와 같은 트랜잭션(outbox).
 */
@Component
public class SessionBlocks {

  static final EventEnvelope.Actor ACTOR =
      new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "rider-auth-service");

  private final EventPublisher events;
  private final String issuer;

  public SessionBlocks(EventPublisher events, RiderAuthProperties props) {
    this.events = events;
    this.issuer = props.issuer();
  }

  public void block(
      String riderId, @Nullable String sessionId, Instant blockedBefore, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("issuer", issuer);
    payload.put("subject", riderId);
    if (sessionId != null) {
      payload.put("sessionId", sessionId);
    }
    payload.put("userType", RiderTokens.TYPE);
    payload.put("blockedBefore", blockedBefore.toString());
    payload.put("reason", reason);
    events.publish("SubjectBlocked", 1, "SubjectBlock", riderId, 1L, ACTOR, payload);
  }
}
