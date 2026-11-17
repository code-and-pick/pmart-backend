package com.pmart.delivery.tracking.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.delivery.tracking.infrastructure.ProgressStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 고객에게 받았는지 묻기(2026-11-17 — 사용자 "도착예정 시간보다 20분이 지나면 고객에게 음식을 받았는지 물어보고 고객이 전달완료를 눌러주면 라이더앱에도 반영").
 * 픽업 뒤·전달 전 배송이 기준(라이더 도착 예정, 없으면 약속 시간대 끝) + {@code receipt-check-after}(20분)를 넘으면 한 번 {@code
 * DeliveryReceiptCheckRequested}(delivery.tracking.v1) — notification 이 고객에게 묻고, 고객이 누르면 execution
 * 이 완료한다.
 */
@Component
public class ReceiptCheck {

  private static final Logger log = LoggerFactory.getLogger(ReceiptCheck.class);
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  private final ProgressStore store;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final TrackingProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ReceiptCheck(
      ProgressStore store,
      EventPublisher events,
      EventEnvelopeCodec codec,
      TrackingProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.events = events;
    this.codec = codec;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${pmart.tracking.receipt-check-interval:PT1M}",
      fixedDelayString = "${pmart.tracking.receipt-check-interval:PT1M}")
  public void scheduled() {
    run();
  }

  /** 물은 배송 수(시험도 직접 부른다). */
  public int run() {
    Instant now = clock.instant();
    int asked = 0;
    for (ProgressStore.Progress p : store.receiptCandidates(200)) {
      Instant expected = p.etaDrop();
      String basis = "ETA";
      if (expected == null) {
        expected = windowEnd(p);
        basis = "ARRIVAL_WINDOW";
      }
      if (expected == null || now.isBefore(expected.plus(props.receiptCheckAfter()))) {
        continue;
      }
      Instant exp = expected;
      String b = basis;
      Boolean done =
          tx.execute(
              s -> {
                if (store.markReceiptAsked(p.deliveryId(), now) == 0) {
                  return false;
                }
                Integer v = store.bumpEventVersion(p.deliveryId());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("deliveryId", p.deliveryId());
                payload.put("orderId", p.orderId());
                if (p.mfcId() != null) {
                  payload.put("mfcId", p.mfcId());
                }
                payload.put("expectedAt", exp.toString());
                payload.put("basis", b);
                payload.put("askedAt", now.toString());
                events.publish(
                    codec.create(
                        events.sourceSystem(),
                        "DeliveryReceiptCheckRequested",
                        1,
                        "Delivery",
                        p.deliveryId(),
                        v == null ? 1L : v.longValue(),
                        null,
                        EventEnvelope.EventSource.REAL,
                        payload),
                    RiderWatch.TOPIC);
                return true;
              });
      if (Boolean.TRUE.equals(done)) {
        asked++;
        log.info(
            "배송 {} 도착 예정 + {} 지남 — 고객에게 받았는지 묻기({})", p.deliveryId(), props.receiptCheckAfter(), b);
      }
    }
    return asked;
  }

  private static @Nullable Instant windowEnd(ProgressStore.Progress p) {
    if (p.arrivalDate() == null || p.arrivalEnd() == null) {
      return null;
    }
    try {
      return p.arrivalDate().atTime(LocalTime.parse(p.arrivalEnd())).atZone(SEOUL).toInstant();
    } catch (java.time.format.DateTimeParseException e) {
      return null;
    }
  }
}
