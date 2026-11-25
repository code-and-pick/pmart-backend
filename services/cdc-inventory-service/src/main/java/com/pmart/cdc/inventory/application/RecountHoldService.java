package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.infrastructure.RecountHoldStore;
import com.pmart.cdc.inventory.infrastructure.RecountHoldStore.HoldRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재계수 대기(0.7.0, WMS CDC-F-018) — 운영자 목록·풀기. 대기는 피킹 부족 해제(recountHold)가 만든다. 센 결과의 장부 반영(조정)은 아직 없다
 * — 풀기는 "다시 할당 후보로 되돌린다"만 한다.
 */
@Service
public class RecountHoldService {

  static final String OP_RELEASE = "cdcInventory:recountHoldRelease";
  static final Set<String> STATES = Set.of("HELD", "RELEASED");

  private final RecountHoldStore store;
  private final RequestIdempotency idempotency;
  private final Ledger ledger;

  public RecountHoldService(RecountHoldStore store, RequestIdempotency idempotency, Ledger ledger) {
    this.store = store;
    this.idempotency = idempotency;
    this.ledger = ledger;
  }

  @Transactional(readOnly = true)
  public List<HoldRow> list(String cdcId, @Nullable String state) {
    Operator.requireCenter(cdcId);
    if (state != null && !STATES.contains(state)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "state 는 HELD·RELEASED 입니다.");
    }
    return store.list(cdcId, state);
  }

  public Outcome<HoldRow> release(
      String requestId, String sourceSystem, String cdcId, String holdId, String reason) {
    Operator op = Operator.forCenter(cdcId, sourceSystem);
    return idempotency.execute(
        op.key(requestId, OP_RELEASE),
        Map.of("cdcId", cdcId, "holdId", holdId, "reason", reason),
        HoldRow.class,
        () -> {
          HoldRow h =
              store
                  .find(holdId, true)
                  .filter(x -> x.cdcId().equals(cdcId))
                  .orElseThrow(
                      () -> PmartException.notFound("NOT_FOUND", "재계수 대기 " + holdId + " 가 없습니다."));
          if ("HELD".equals(h.state())) {
            store.release(holdId, op.id(), reason, ledger.now());
          }
          return store.find(holdId, false).orElseThrow();
        });
  }
}
