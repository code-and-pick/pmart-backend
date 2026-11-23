package com.pmart.delivery.execution.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.domain.ProfanityRules;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 배송 도움요청 대화의 금칙어(2026-11-23 — 사용자 "응": case 문의·문의 대화와 같은 규칙을 도움요청에도). 라이더가 쓴 글만 본다(도움요청 내용·대화
 * 메시지·전달 재개 메모).
 *
 * <ul>
 *   <li>막기 단어(욕설, {@code pmart.execution.profanity.blocked-words}): 받지 않는다 — 422 BLOCKED_WORDS(정지
 *       없음). 멱등 처리 밖에서 먼저.
 *   <li>정지 단어(위협·거친 말, {@code pmart.execution.profanity.words}): 받고 {@code
 *       RiderHelpProfanityDetected} (delivery.delivery.v1 — 글·단어 없음) → rider 가 7일 정지(RS-8 과 같은 규칙).
 * </ul>
 *
 * 목록은 case-service 와 같은 값을 설정 저장소에 둔다(PC 1). 비면 아무것도 막지 않는다.
 */
@Component
@EnableConfigurationProperties(HelpProfanityGuard.Words.class)
public class HelpProfanityGuard {

  @ConfigurationProperties(prefix = "pmart.execution.profanity")
  public record Words(@Nullable List<String> words, @Nullable List<String> blockedWords) {
    public Words {
      words = words == null ? List.of() : List.copyOf(words);
      blockedWords = blockedWords == null ? List.of() : List.copyOf(blockedWords);
    }
  }

  private final Words list;
  private final EventPublisher events;

  public HelpProfanityGuard(Words list, EventPublisher events) {
    this.list = list;
    this.events = events;
  }

  /** 막기 단어가 있으면 422 BLOCKED_WORDS — 멱등 처리 밖(기록하지 않음)에서 부른다. */
  public void rejectBlocked(@Nullable String... texts) {
    if (ProfanityRules.contains(list.blockedWords(), texts)) {
      throw new PmartException(
          "BLOCKED_WORDS",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "쓸 수 없는 말이 들어 있어요. 고쳐서 다시 보내 주세요.",
          false,
          List.of());
    }
  }

  /**
   * 정지 단어가 있으면 정지 사건 — 글을 저장하는 같은 트랜잭션 안에서. version = 지금 수행 버전(대화는 버전을 올리지 않는다 — 라이더 다음 동작이 버전 충돌
   * 나지 않게, HelpRequestReplied 와 같음).
   */
  public void reportSuspendable(
      String deliveryId,
      String requestId,
      String riderId,
      String source,
      long version,
      Instant now,
      @Nullable String... texts) {
    if (!ProfanityRules.contains(list.words(), texts)) {
      return;
    }
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("riderId", riderId);
    p.put("deliveryId", deliveryId);
    p.put("requestId", requestId);
    p.put("source", source);
    p.put("detectedAt", now.toString());
    events.publish(
        "RiderHelpProfanityDetected",
        1,
        "Delivery",
        deliveryId,
        version,
        new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "execution-profanity"),
        p);
  }
}
