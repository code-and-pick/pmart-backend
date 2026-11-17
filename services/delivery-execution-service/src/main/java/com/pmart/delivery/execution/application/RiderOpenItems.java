package com.pmart.delivery.execution.application;

import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 라이더 탈퇴를 막는 것(2026-11-17 — rider-service 탈퇴, 사용자 결정 "확정 전만 막기"·"열린 도움요청 막기"). 진행 중 배송(끝나지 않은 배정),
 * 확정 전 수행료(산정 가능·ESTIMATED·PENDING_CONFIRMATION), 산정 불가 조정 대기(산정 불가·ESTIMATED), 열린
 * 도움요청(OPEN·IN_PROGRESS).
 */
@Service
public class RiderOpenItems {

  private final ExecutionStore store;

  public RiderOpenItems(ExecutionStore store) {
    this.store = store;
  }

  public List<String> withdrawalBlockers(String riderId) {
    ExecutionStore.OpenItems o = store.riderOpenItems(riderId);
    List<String> out = new ArrayList<>();
    if (o.activeDeliveries() > 0) {
      out.add("ACTIVE_DELIVERY");
    }
    if (o.unconfirmedEarnings() > 0) {
      out.add("UNCONFIRMED_EARNING");
    }
    if (o.adjustmentPending() > 0) {
      out.add("EARNING_ADJUSTMENT_PENDING");
    }
    if (o.openHelpRequests() > 0) {
      out.add("OPEN_HELP_REQUEST");
    }
    return out;
  }
}
