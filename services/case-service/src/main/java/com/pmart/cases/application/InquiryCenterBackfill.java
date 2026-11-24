package com.pmart.cases.application;

import com.pmart.cases.infrastructure.InquiryStore;
import com.pmart.cases.infrastructure.OrderClient;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 0.40.0 R-17(2026-11-24): 센터를 모르는 주문 있는 고객 문의(0.40.0 전에 받은 것·받을 때 센터를 못 읽은 것)에 주문 이행 센터를 채운다. 서비스가
 * 뜨면 가상 스레드로 한 번 — order {@code GET /internal/orders/{id}} 의 mfcId(claim CL-8 과 같은 길). order 를 못
 * 부르면 멈추고(다음 기동 때 이어서) 경고만. 주문에 센터가 없거나 주문이 없으면 확인만 남긴다(센터 없음 — 센터를 골라도 늘 함께 보인다). 라이더 문의의 지난 행은
 * 채우지 않는다(운행 센터는 라이더 토큰으로만 읽힘 — 센터 없음).
 */
@Component
public class InquiryCenterBackfill {

  private static final Logger log = LoggerFactory.getLogger(InquiryCenterBackfill.class);
  private static final int BATCH = 100;

  private final InquiryStore store;
  private final OrderClient orders;
  private final CaseProperties props;

  public InquiryCenterBackfill(InquiryStore store, OrderClient orders, CaseProperties props) {
    this.store = store;
    this.orders = orders;
    this.props = props;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    if (props.orderServiceBaseUrl() == null) {
      return;
    }
    Thread.ofVirtual().name("inquiry-center-backfill").start(this::run);
  }

  /** 확인한 주문 수. 시험이 직접 부른다. */
  public int run() {
    int done = 0;
    try {
      while (true) {
        List<String> todo = store.ordersWithoutCenter(BATCH);
        if (todo.isEmpty()) {
          break;
        }
        for (String orderId : todo) {
          store.fillCenter(orderId, orders.internalCenter(orderId).orElse(null));
          done++;
        }
      }
    } catch (RuntimeException e) {
      log.warn("문의 센터 채우기 멈춤 — 다음 기동 때 이어서 ({})", e.getClass().getSimpleName());
    }
    if (done > 0) {
      log.info("문의 센터 채우기 — 주문 {}건 확인", done);
    }
    return done;
  }
}
