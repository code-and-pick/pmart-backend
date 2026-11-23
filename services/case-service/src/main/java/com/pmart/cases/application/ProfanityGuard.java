package com.pmart.cases.application;

import com.pmart.cases.domain.InquiryRules;
import com.pmart.cases.domain.ProfanityRules;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 문의 글 금칙어(0.37.0 — 사용자 2026-11-23 "A는 문의나 채팅에 입력 자체가 안 되게, C는 걸리면 정지, B도" — 막기만·정지 없음, 문의 + AI 채팅
 * + 라이더 문의 대화).
 *
 * <ul>
 *   <li>막기 단어(욕설): 받지 않는다 — 422 BLOCKED_WORDS. 정지 없음.
 *   <li>정지 단어(위협·거친 말): 받고, 고객이면 CustomerProfanityDetected·라이더면 RiderProfanityDetected — 정지는
 *       customer(CU-7)·rider(PC 5).
 * </ul>
 *
 * 글·걸린 단어는 응답·사건·로그에 싣지 않는다.
 */
@Component
public class ProfanityGuard {

  private static final Logger log = LoggerFactory.getLogger(ProfanityGuard.class);

  private final CaseProperties props;
  private final EventPublisher events;

  public ProfanityGuard(CaseProperties props, EventPublisher events) {
    this.props = props;
    this.events = events;
  }

  /** 막기 단어가 있으면 422 — 멱등 처리 밖(기록하지 않음)에서 부른다. */
  public void rejectBlocked(@Nullable String... texts) {
    if (ProfanityRules.contains(props.blocked(), texts)) {
      throw new PmartException(
          "BLOCKED_WORDS",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "쓸 수 없는 말이 들어 있어요. 고쳐서 다시 보내 주세요.",
          false,
          List.of());
    }
  }

  /** 정지 단어가 있으면 정지 사건 — 글을 저장하는 같은 트랜잭션 안에서 부른다. */
  public void reportSuspendable(
      String requesterType,
      String subject,
      String inquiryId,
      String caseId,
      String source,
      long aggregateVersion,
      Instant now,
      @Nullable String... texts) {
    if (!ProfanityRules.contains(props.profanity(), texts)) {
      return;
    }
    boolean rider = InquiryRules.RIDER.equals(requesterType);
    Map<String, Object> pd = new LinkedHashMap<>();
    pd.put(rider ? "riderId" : "customerId", subject);
    pd.put("inquiryId", inquiryId);
    pd.put("caseId", caseId);
    pd.put("source", source);
    pd.put("detectedAt", now.toString());
    events.publish(
        rider ? "RiderProfanityDetected" : "CustomerProfanityDetected",
        1,
        "Case",
        caseId,
        aggregateVersion,
        new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "case-service"),
        pd);
    log.info("inquiry suspend-word detected inquiry={} requester={}", inquiryId, requesterType);
  }
}
