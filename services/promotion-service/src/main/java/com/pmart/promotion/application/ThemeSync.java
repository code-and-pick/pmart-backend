package com.pmart.promotion.application;

import com.pmart.promotion.infrastructure.StorefrontThemesClient;
import com.pmart.promotion.infrastructure.ThemeCopyStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 진열 테마 사본 받기(0.3.0 — 사용자 "행사 대상에 테마도 추가", 방식 "추천안대로"). 1분마다 storefront 의 지금 게시 진열 테마를 받아 진열 버전이
 * 바뀌었으면 사본을 통째로 바꾼다. 기간 할인 THEME 대상·표시 할인(30초 주기)이 이 사본을 쓴다 — 진열이 바뀐 뒤 할인 반영까지 최대 약 1분 30초.
 * storefront 가 안 닿으면 사본을 그대로 두고 다음 주기에 다시(할인을 멈추지 않는다).
 */
@Service
public class ThemeSync {

  private static final Logger log = LoggerFactory.getLogger(ThemeSync.class);

  private final StorefrontThemesClient client;
  private final ThemeCopyStore store;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ThemeSync(
      StorefrontThemesClient client, ThemeCopyStore store, TransactionTemplate tx, Clock clock) {
    this.client = client;
    this.store = store;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.promotion.theme-sync-interval:PT1M}",
      initialDelayString = "${pmart.promotion.theme-sync-initial-delay:PT15S}")
  public void scheduled() {
    try {
      sync();
    } catch (RuntimeException ex) {
      log.warn("진열 테마 받기 실패 — 사본 그대로, 다음 주기에 다시 ({})", ex.getClass().getSimpleName());
    }
  }

  /** 한 번 받기. 사본을 바꿨으면 true. */
  public boolean sync() {
    StorefrontThemesClient.Published p = client.published();
    Boolean changed =
        tx.execute(s -> store.replace(p.layoutVersion(), p.themes(), clock.instant()));
    if (Boolean.TRUE.equals(changed)) {
      log.info("진열 테마 사본 바꿈 — 진열 버전 {}, 테마 {}개", p.layoutVersion(), p.themes().size());
    }
    return Boolean.TRUE.equals(changed);
  }
}
