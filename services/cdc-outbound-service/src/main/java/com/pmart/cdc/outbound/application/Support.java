package com.pmart.cdc.outbound.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** 공통: 운영자 범위 검사, 멱등 키, 시계, 발행(토픽 cdc.outbound.v1). */
@Component
public class Support {

  public static final String TOPIC = "cdc.outbound.v1";

  /** 명령 결과(멱등 재생용). */
  public record CommandResult(
      String status, String requestId, String resultId, int version, Instant occurredAt) {}

  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;

  public Support(EventPublisher events, EventEnvelopeCodec codec, Clock clock) {
    this.events = events;
    this.codec = codec;
    this.clock = clock;
  }

  Instant now() {
    return Instant.now(clock);
  }

  static String newId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  /**
   * 자기 센터 운영자(centers 클레임)인지.
   *
   * @return 운영자 ID(토큰 주체)
   */
  static String requireCenter(String cdcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(cdcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return p.subject();
  }

  /** 작업자 본인(토큰 주체 = workerId)이고 자기 센터인지 — PDA 경로. */
  static void requireSelf(String cdcId, String workerId) {
    String me = requireCenter(cdcId);
    if (!me.equals(workerId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "작업자 본인만 할 수 있습니다.");
    }
  }

  static RequestKey key(String sourceSystem, String requestId, String operation, String actor) {
    return new RequestKey(sourceSystem, requestId, operation, actor);
  }

  void publish(
      String type,
      String aggregateType,
      String aggregateId,
      long version,
      String actor,
      Map<String, Object> payload) {
    events.publish(
        codec.create(
            events.sourceSystem(),
            type,
            1,
            aggregateType,
            aggregateId,
            version,
            new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, actor),
            EventEnvelope.EventSource.REAL,
            payload),
        TOPIC);
  }

  @Configuration(proxyBeanMethods = false)
  static class ClockConfig {
    /** UTC 저장(ADR-0010). */
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
