package com.pmart.rider.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.rider.domain.Rider;
import com.pmart.rider.infrastructure.RiderStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 라이더 문의 정지 단어 → 7일 정지(RS-8, 2026-11-23 — 사용자 "C는 걸리면 정지", 고객 CU-7 과 같은 규칙). case {@code
 * RiderProfanityDetected}(platform.case.v1 — 글·단어 없음)를 받아 계정 제한 SUSPENDED, 기한 = 찾은 시각 + 7일(지나면
 * {@link Rider#asOf} 가 NORMAL 로 본다 — 자동 해제). 수동 정지와 같은 길: 이력 + {@code RiderRestricted}(actor
 * SYSTEM) → dispatch 가 픽업 전 배정을 풀고, rider-auth 가 진행 중 배송이 끝난 뒤 로그인을 끊고, notification 이 라이더에게 "정지 ·
 * ~까지" 알림.
 *
 * <p>기존 제한과 맞추기(PC 5 결정): 탈퇴했거나 지금 TERMINATED 거나 이미 같은 기한 이상 SUSPENDED(기한 없음 포함)면 그대로 둔다. NORMAL·
 * DISPATCH_RESTRICTED·더 짧은 SUSPENDED 면 SUSPENDED 7일로 올린다(신규배차 제한은 정지가 끝나면 다시 걸리지 않는다 — 필요하면 직원이 다시
 * 건다). 기한이 이미 지난 늦은 사건은 버린다. 오인식은 직원이 계정 제한 화면에서 사유를 적고 NORMAL 로 푼다(기존 API). inbox 트랜잭션 안.
 */
@Service
public class ProfanitySuspensions {

  public static final Set<String> TYPES = Set.of("RiderProfanityDetected");

  /** 배송 도움요청 대화(2026-11-23 사용자 "응" — execution, delivery.delivery.v1). 같은 7일 정지. */
  public static final Set<String> HELP_TYPES = Set.of("RiderHelpProfanityDetected");

  static final String ACTOR = "system:case-profanity";
  private static final Logger log = LoggerFactory.getLogger(ProfanitySuspensions.class);

  private final RiderStore store;
  private final EventPublisher events;
  private final Clock clock;
  private final Duration period;

  public ProfanitySuspensions(
      RiderStore store,
      EventPublisher events,
      Clock clock,
      @Value("${pmart.rider.profanity-suspension:P7D}") Duration period) {
    this.store = store;
    this.events = events;
    this.clock = clock;
    this.period = period;
  }

  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String riderId = p.path("riderId").asString("");
    if (riderId.isBlank()) {
      return;
    }
    Instant now = clock.instant();
    Instant detectedAt = instant(p.path("detectedAt").asString(""), now);
    Instant until = detectedAt.plus(period);
    if (!until.isAfter(now)) {
      log.info("정지 단어 사건 생략 reason=expired eventId={}", e.eventId());
      return;
    }
    Rider r = store.lock(riderId).map(x -> x.asOf(now)).orElse(null);
    if (r == null || r.withdrawnAt() != null) {
      log.info("정지 단어 사건 생략 reason=no_rider eventId={}", e.eventId());
      return;
    }
    if (!stricterNeeded(r.restriction(), r.restrictionUntil(), until)) {
      log.info("정지 단어 사건 생략 reason=already_restricted eventId={}", e.eventId());
      return;
    }
    String source = p.path("source").asString("INQUIRY");
    String reason =
        HELP_TYPES.contains(e.eventType())
            ? "배송 도움요청 대화에 위협·거친 말 — 자동 7일 정지(배송 " + p.path("deliveryId").asString("") + ")"
            : "라이더 문의"
                + ("INQUIRY_MESSAGE".equals(source) ? " 대화" : "")
                + "에 위협·거친 말 — 자동 7일 정지(문의 "
                + p.path("inquiryId").asString("")
                + ")";
    Rider.Restriction previous = r.restriction();
    store.updateRestriction(
        riderId, r.version(), Rider.Restriction.SUSPENDED.name(), reason, until);
    store.insertRestriction(
        new RiderStore.RestrictionRow(
            "profanity:" + e.eventId(),
            riderId,
            Rider.Restriction.SUSPENDED.name(),
            previous.name(),
            reason,
            until,
            "[]",
            null,
            ACTOR,
            now,
            r.version() + 1));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("riderId", riderId);
    payload.put("restriction", Rider.Restriction.SUSPENDED.name());
    payload.put("previous", previous.name());
    payload.put("from", now.toString());
    payload.put("to", until.toString());
    payload.put("basis", reason);
    payload.put("evidenceRefs", List.of());
    events.publish(
        "RiderRestricted",
        1,
        "Rider",
        riderId,
        r.version() + 1L,
        new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, ACTOR),
        payload);
  }

  /** 지금 제한보다 7일 정지가 더 세면 true — TERMINATED·같거나 긴 SUSPENDED(기한 없음 포함)는 그대로. */
  static boolean stricterNeeded(
      Rider.Restriction current, @Nullable Instant currentUntil, Instant until) {
    return switch (current) {
      case TERMINATED -> false;
      case SUSPENDED -> currentUntil != null && currentUntil.isBefore(until);
      default -> true;
    };
  }

  private static Instant instant(String s, Instant fallback) {
    try {
      return s.isBlank() ? fallback : Instant.parse(s);
    } catch (java.time.format.DateTimeParseException ex) {
      return fallback;
    }
  }
}
