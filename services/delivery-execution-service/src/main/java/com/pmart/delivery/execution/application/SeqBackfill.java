package com.pmart.delivery.execution.application;

import com.pmart.delivery.execution.infrastructure.DispatchClient;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 화면 배송 번호 순번 채우기(2026-11-18, ADR-0070): DeliveryCreated.deliverySeq 를 받지 못한 배송(dispatch 0.10.0 배포
 * 전 배송·놓친 사건)을 dispatch {@code GET /internal/deliveries/{id}/sequence} 로 채운다. 한 번에 {@value
 * #BATCH}건, dispatch 가 모르는 배송(404 — 옛 시험 배송 등)은 건너뛴다(다음 회차에 다시 묻는다 — 수가 적어 그대로 둔다).
 */
@Service
public class SeqBackfill {

  static final int BATCH = 100;
  private static final Logger log = LoggerFactory.getLogger(SeqBackfill.class);

  private final ExecutionStore store;
  private final DispatchClient dispatch;

  public SeqBackfill(ExecutionStore store, DispatchClient dispatch) {
    this.store = store;
    this.dispatch = dispatch;
  }

  @Scheduled(
      initialDelayString = "${pmart.execution.seq-backfill-interval:PT1M}",
      fixedDelayString = "${pmart.execution.seq-backfill-interval:PT1M}")
  public void run() {
    int filled = 0;
    for (String id : store.missingDeliverySeq(BATCH)) {
      var seq = dispatch.deliverySeq(id);
      if (seq.isPresent()) {
        store.setDeliverySeq(id, seq.getAsInt());
        filled++;
      }
    }
    if (filled > 0) {
      log.info("배송 순번 {}건 채움(dispatch)", filled);
    }
  }
}
