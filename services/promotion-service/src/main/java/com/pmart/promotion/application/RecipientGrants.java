package com.pmart.promotion.application;

import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.infrastructure.PromotionStore;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import com.pmart.promotion.infrastructure.RecipientStore;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만들 때 정한 받을 고객에게 쿠폰 주기(0.3.0 — 사용자 "직원 지정 발급 … 고객 id 를 넣는 칸", 세부 "추천안대로"). 1분마다 지금 진행 중인 지정
 * 발급(ADMIN) 행사의 아직 안 준 고객에게 {@link CouponService#issue}(지정 발급과 같은 규칙 — 1인 한도·총 수량, 채널 ADMIN, 쿠폰 도착
 * 알림). 발급한 사람 = 행사를 만든 직원, 사유 = 만들 때 적은 사유. 시작 전에 끝난 행사는 주지 않는다(결과 PENDING 그대로). 행사 하나 = 트랜잭션 하나(행사
 * 행 잠금 — 다른 인스턴스·지정 발급과 겹치지 않게).
 */
@Service
public class RecipientGrants {

  private static final Logger log = LoggerFactory.getLogger(RecipientGrants.class);

  private final RecipientStore recipients;
  private final PromotionStore store;
  private final CouponService coupons;
  private final TransactionTemplate tx;
  private final Clock clock;

  public RecipientGrants(
      RecipientStore recipients,
      PromotionStore store,
      CouponService coupons,
      TransactionTemplate tx,
      Clock clock) {
    this.recipients = recipients;
    this.store = store;
    this.coupons = coupons;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.promotion.recipient-grant-interval:PT1M}",
      initialDelayString = "${pmart.promotion.recipient-grant-initial-delay:PT30S}")
  public void scheduled() {
    try {
      int n = run();
      if (n > 0) {
        log.info("받을 고객 지정 쿠폰 {}명 처리", n);
      }
    } catch (RuntimeException ex) {
      log.warn("받을 고객 지정 쿠폰 발급 실패 — 다음 주기에 다시 ({})", ex.getClass().getSimpleName());
    }
  }

  /** 한 번 돌기. 처리한 고객 수. */
  public int run() {
    int total = 0;
    for (String promotionId : recipients.duePromotions(clock.instant())) {
      Integer n = tx.execute(s -> grant(promotionId));
      total += n == null ? 0 : n;
    }
    return total;
  }

  private int grant(String promotionId) {
    PromotionRow p = store.lock(promotionId).orElse(null);
    Instant now = clock.instant();
    if (p == null
        || !"ADMIN".equals(p.issueMode())
        || !"RUNNING"
            .equals(PromotionRules.phase(p.state(), p.startsAt(), p.endsAt(), p.endedAt(), now))) {
      return 0;
    }
    String reason = recipients.reason(promotionId).orElse(null);
    int n = 0;
    for (String acc : recipients.pending(promotionId)) {
      String r = coupons.issue(p, acc, "ADMIN", p.createdBy(), reason);
      if (r.startsWith("!")) {
        recipients.done(
            promotionId, acc, "!LIMIT".equals(r) ? "SKIPPED_LIMIT" : "SOLD_OUT", null, now);
      } else {
        recipients.done(promotionId, acc, "ISSUED", r, now);
      }
      n++;
    }
    return n;
  }
}
