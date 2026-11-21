package com.pmart.delivery.execution.application;

import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 직원 배송 찾기(0.4.0, BOD-5 — 백오피스 고객센터·라이더 운영): 주문 ID·라이더·상태로. 전달 실패 확정 대기는 state=FAILURE_REPORTED.
 * 목록에는 개인정보가 없다(ID·상태·시각).
 */
@Service
public class StaffQueries {

  private final ExecutionStore store;

  public StaffQueries(ExecutionStore store) {
    this.store = store;
  }

  public java.util.Map<String, Long> counts(ExecutionStore.DeliveryFilter filter) {
    Access.requireStaffScope(filter.mfcId());
    return store.staffCounts(filter);
  }

  public List<DeliveryRow> deliveries(ExecutionStore.DeliveryFilter filter, int limit, int offset) {
    Access.requireStaffScope(filter.mfcId());
    return store.staffDeliveries(filter, limit, offset);
  }

  /** 목록 줄의 단계 시각(이미 범위를 확인한 목록의 배송만 — 2026-11-21). */
  public java.util.Map<String, ExecutionStore.StageTimes> stageTimes(List<String> deliveryIds) {
    return store.stageTimes(deliveryIds);
  }
}
