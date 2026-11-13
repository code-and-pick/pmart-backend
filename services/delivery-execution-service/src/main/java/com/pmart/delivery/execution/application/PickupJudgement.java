package com.pmart.delivery.execution.application;

import com.pmart.delivery.execution.domain.ExecutionRules;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.HandoverRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 픽업 완료 판정(DE-1·D-6): 배송의 포장 전부에 {@code PackageHandedOver} 가 있을 때만 PICKED_UP + {@code
 * PickupCompleted}. 버튼·GPS·QR 만으로는 바꾸지 않는다. 사건이 어떤 순서로 와도(배송·포장 목록·인계) 올 때마다 다시 판정한다. 호출자 트랜잭션 안.
 */
@Component
public class PickupJudgement {

  private static final Logger log = LoggerFactory.getLogger(PickupJudgement.class);

  private final ExecutionStore store;
  private final ExecutionEvents events;
  private final ObjectMapper mapper;

  public PickupJudgement(ExecutionStore store, ExecutionEvents events, ObjectMapper mapper) {
    this.store = store;
    this.events = events;
    this.mapper = mapper;
  }

  /**
   * @return 이번에 픽업 완료로 바꿨으면 true
   */
  public boolean judge(String deliveryId, Instant now) {
    DeliveryRow d = store.lockDelivery(deliveryId).orElse(null);
    if (d == null || !ExecutionRules.PICKUP_FROM.contains(d.state()) || d.shipmentId() == null) {
      return false;
    }
    List<String> known = store.shipmentPackages(d.shipmentId()).map(this::ids).orElse(List.of());
    // 같은 포장을 두 번 인계 기록했으면(재인계) 마지막 것을 쓴다
    Map<String, HandoverRow> byPackage =
        store.handovers(deliveryId).stream()
            .collect(
                Collectors.toMap(
                    HandoverRow::packageId,
                    Function.identity(),
                    (a, b) -> b,
                    java.util.LinkedHashMap::new));
    if (!ExecutionRules.allHandedOver(known, byPackage.keySet())) {
      if (known.isEmpty() && !byPackage.isEmpty()) {
        log.info("배송 {} 인계 기록 있음 — 포장 목록을 아직 몰라 픽업 판정 보류", deliveryId);
      }
      return false;
    }
    List<String> handoverIds = new ArrayList<>();
    Instant pickedUpAt = Instant.EPOCH;
    for (String pkg : known) {
      HandoverRow h = byPackage.get(pkg);
      handoverIds.add(h.handoverId());
      if (h.handedAt().isAfter(pickedUpAt)) {
        pickedUpAt = h.handedAt();
      }
    }
    store.pickedUp(deliveryId, pickedUpAt, now);
    store.history(deliveryId, "PICKED_UP", pickedUpAt, "SYSTEM_EVENT", handoverIds.getLast(), now);
    events.pickupCompleted(d, known, handoverIds, pickedUpAt);
    log.info("배송 {} 픽업 완료 — 포장 {}개 모두 인계", deliveryId, known.size());
    return true;
  }

  private List<String> ids(String json) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : mapper.readTree(json)) {
      out.add(n.asString());
    }
    return out;
  }
}
