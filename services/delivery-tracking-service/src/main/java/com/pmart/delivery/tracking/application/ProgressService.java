package com.pmart.delivery.tracking.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.tracking.domain.ProgressRules;
import com.pmart.delivery.tracking.infrastructure.LocationStore;
import com.pmart.delivery.tracking.infrastructure.OrderClient;
import com.pmart.delivery.tracking.infrastructure.ProgressStore;
import com.pmart.delivery.tracking.infrastructure.ProgressStore.Progress;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 고객·직원 ETA 조회·실시간 구독(TR-3, 2026-11-17 — 사용자 "PC 5에 요청"). 배송 진행 단계 사본({@link ProgressStore})을 배송
 * 사건으로 만들고, 조회 때 접근을 판정한다.
 *
 * <ul>
 *   <li>고객: 본인 주문의 배송만(배송 → 주문 → order 내부 조회 accountId = 토큰 sub). 남의·모르는 배송은 404. dropEta 만.
 *   <li>직원: execution {@code Access} 와 같게 — OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 는 모든 센터, 그 밖 운영자는 그
 *       센터 권한. pickupEta·dropEta.
 *   <li>라이더 좌표는 누구에게도 주지 않는다.
 * </ul>
 */
@Service
public class ProgressService {

  public static final Set<String> TYPES =
      Set.of(
          "DeliveryCreated",
          "DeliveryAssigned",
          "DeliveryAssignmentChanged",
          "RiderArrivedAtStore",
          "PickupCompleted",
          "ReturnPickedUp",
          "RiderArrivedAtCustomer",
          "DeliveryCompleted",
          "ReturnDelivered",
          "DeliveryFailed",
          "DeliveryCancelledBeforePickup");

  /** 조회 결과 — staff 면 픽업 예정도 보인다. */
  public record View(Progress progress, boolean staff, boolean etaEnabled, boolean locating) {}

  private final ProgressStore store;
  private final LocationStore locations;
  private final OrderClient orders;
  private final TrackingProperties props;
  private final Clock clock;

  public ProgressService(
      ProgressStore store,
      LocationStore locations,
      OrderClient orders,
      TrackingProperties props,
      Clock clock) {
    this.store = store;
    this.locations = locations;
    this.orders = orders;
    this.props = props;
    this.clock = clock;
  }

  /** inbox 트랜잭션 안. */
  public void on(EventEnvelope e) {
    JsonNode p = e.payload();
    String deliveryId = p.path("deliveryId").asString("");
    if (deliveryId.isBlank()) {
      return;
    }
    Instant now = clock.instant();
    if ("DeliveryCreated".equals(e.eventType())) {
      JsonNode w = p.path("arrivalWindow");
      store.created(
          deliveryId,
          text(p, "orderId"),
          text(p, "mfcId"),
          date(text(w, "date")),
          text(w, "start"),
          text(w, "end"),
          now);
      return;
    }
    boolean released =
        "DeliveryAssignmentChanged".equals(e.eventType())
            && p.path("newRiderId").asString("").isBlank();
    ProgressRules.Target target = ProgressRules.target(e.eventType(), released);
    if (target == null) {
      return;
    }
    String current = store.lockStage(deliveryId).orElse(null);
    if (ProgressRules.shouldMove(current, target)) {
      store.setStage(deliveryId, target.stage(), target.outcome(), text(p, "mfcId"), now);
    }
  }

  /** 조회 권한 판정 + 사본. 남의·모르는 배송은 404, 고객·직원이 아니면 403. */
  public View view(String deliveryId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "인증이 필요합니다."));
    Progress row = store.find(deliveryId).orElseThrow(() -> notFound(deliveryId));
    boolean staff;
    if (p.type() == PmartPrincipal.PrincipalType.CUSTOMER) {
      if (row.orderId() == null
          || !orders.accountId(row.orderId()).map(p.subject()::equals).orElse(false)) {
        throw notFound(deliveryId);
      }
      staff = false;
    } else if (p.type() == PmartPrincipal.PrincipalType.OPERATOR) {
      if (!allCenters(p) && (row.mfcId() == null || !p.canAccessCenter(row.mfcId()))) {
        throw notFound(deliveryId);
      }
      staff = true;
    } else {
      throw new PmartException(ErrorCode.FORBIDDEN, "고객·직원만 볼 수 있습니다.");
    }
    return snapshot(row, staff);
  }

  /** 다시 읽기(구독 중 — 권한은 연결 때 판정). 행이 지워졌으면 null. */
  public @Nullable View refresh(String deliveryId, boolean staff) {
    return store.find(deliveryId).map(r -> snapshot(r, staff)).orElse(null);
  }

  private View snapshot(Progress row, boolean staff) {
    boolean locating = false;
    if (row.riderId() != null && row.etaDrop() == null) {
      Instant now = clock.instant();
      locating =
          locations
              .latest(row.riderId())
              .map(l -> l.receivedAt().isBefore(now.minus(props.staleAfter())))
              .orElse(true);
    }
    return new View(row, staff, props.eta().enabled(), locating);
  }

  @Scheduled(
      initialDelayString = "${pmart.tracking.purge-interval:PT1H}",
      fixedDelayString = "${pmart.tracking.purge-interval:PT1H}")
  public void purge() {
    store.purgeDone(clock.instant().minus(props.retention()));
  }

  static boolean allCenters(PmartPrincipal p) {
    return p.hasRole("OPERATOR_ADMIN") || p.hasRole("CS_AGENT") || p.hasRole("RIDER_OPS");
  }

  private static PmartException notFound(String deliveryId) {
    return PmartException.notFound("DELIVERY_NOT_FOUND", "배송을 찾을 수 없습니다: " + deliveryId);
  }

  private static @Nullable String text(JsonNode n, String field) {
    String s = n.path(field).asString("");
    return s.isBlank() ? null : s;
  }

  private static @Nullable LocalDate date(@Nullable String s) {
    try {
      return s == null ? null : LocalDate.parse(s);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
