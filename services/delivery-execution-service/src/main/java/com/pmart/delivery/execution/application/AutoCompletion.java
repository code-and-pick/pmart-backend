package com.pmart.delivery.execution.application;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 자동 전달 완료 주기(2026-11-17 — 사용자 "5시간 지나면 자동완료"). 배송마다 따로 트랜잭션, 실패는 다음 주기에 다시. */
@Component
public class AutoCompletion {

  private static final Logger log = LoggerFactory.getLogger(AutoCompletion.class);

  private final ExecutionService service;
  private final com.pmart.delivery.execution.infrastructure.ExecutionStore store;
  private final ExecutionProperties props;
  private final Clock clock;

  public AutoCompletion(
      ExecutionService service,
      com.pmart.delivery.execution.infrastructure.ExecutionStore store,
      ExecutionProperties props,
      Clock clock) {
    this.service = service;
    this.store = store;
    this.props = props;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${pmart.execution.auto-complete-interval:PT1M}",
      fixedDelayString = "${pmart.execution.auto-complete-interval:PT1M}")
  public void scheduled() {
    run();
  }

  /** 시험도 직접 부른다. 자동 완료한 수. */
  public int run() {
    Instant now = clock.instant();
    Instant before = now.minus(props.autoCompleteAfter());
    int done = 0;
    for (String id : store.autoCompleteCandidates(before, 100)) {
      try {
        if (service.autoComplete(id, before, now)) {
          done++;
        }
      } catch (RuntimeException e) {
        log.warn("배송 {} 자동 완료 실패 — 다음 주기에 다시", id, e);
      }
    }
    return done;
  }
}
