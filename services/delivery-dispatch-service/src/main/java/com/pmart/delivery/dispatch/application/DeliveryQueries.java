package com.pmart.delivery.dispatch.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.AttemptRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.OfferRow;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 배송 배차 조회 — 운영자(그 센터, OPERATOR_ADMIN, 라이더 운영 RIDER_OPS, 고객센터 CS_AGENT — BOD-1·2 2026-11-15: 상담원은
 * "라이더 배정됐나요"에 답하려고 조회만)·라이더(배정받았거나 제안받은 배송)·서비스.
 */
@Service
public class DeliveryQueries {

  /**
   * @param showAssignment false 면 배정(라이더 ID 등)을 숨긴다 — 제안만 받았던 라이더(PC 1 검토 L4). 회수지 좌표(DD-2)도 같이
   * @param staff 직원·서비스(라이더가 아님) — 회수 배송의 원래 라이더 ID 는 이때만
   * @param originRiderId 회수 배송이면 원래 배송의 라이더
   */
  public record DeliveryView(
      DeliveryRow delivery,
      List<AttemptRow> attempts,
      List<OfferRow> offers,
      boolean showAssignment,
      boolean staff,
      @org.jspecify.annotations.Nullable String originRiderId) {}

  private final DispatchStore store;

  public DeliveryQueries(DispatchStore store) {
    this.store = store;
  }

  public DeliveryView dispatch(String deliveryId) {
    DeliveryRow d = get(deliveryId);
    List<AttemptRow> attempts = store.attempts(deliveryId);
    List<OfferRow> offers =
        attempts.stream().flatMap(a -> store.offersOfAttempt(a.attemptId()).stream()).toList();
    PmartPrincipal p =
        PmartPrincipal.current().orElseThrow(() -> OfferService.forbidden("인증이 필요합니다."));
    boolean showAssignment = true;
    switch (p.type()) {
      case OPERATOR -> {
        if (!p.canAccessCenter(d.mfcId())
            && !p.hasRole("OPERATOR_ADMIN")
            && !p.hasRole("RIDER_OPS")
            && !p.hasRole("CS_AGENT")) {
          throw OfferService.forbidden("이 센터 배송을 볼 수 없습니다.");
        }
      }
      case RIDER -> {
        boolean mine =
            p.subject().equals(d.riderId())
                || offers.stream().anyMatch(o -> o.riderId().equals(p.subject()));
        if (!mine) {
          throw OfferService.forbidden("본인 배송이 아닙니다.");
        }
        // 라이더에게는 남의 제안을 보여 주지 않는다
        offers = offers.stream().filter(o -> o.riderId().equals(p.subject())).toList();
        // 제안만 받았던 라이더에게는 누가 배정됐는지 보여 주지 않는다(L4)
        showAssignment = p.subject().equals(d.riderId());
      }
      case SERVICE -> {}
      default -> throw OfferService.forbidden("볼 수 없습니다.");
    }
    String originRiderId =
        d.originDeliveryId() == null
            ? null
            : store.delivery(d.originDeliveryId()).map(DeliveryRow::riderId).orElse(null);
    return new DeliveryView(
        d,
        attempts,
        offers,
        showAssignment,
        p.type() != PmartPrincipal.PrincipalType.RIDER,
        originRiderId);
  }

  public DeliveryRow assignment(String deliveryId) {
    return get(deliveryId);
  }

  /** 라이더가 맡고 있는 배송(서비스 간 — rider-auth 로그인 끊기 시점, 0.3.3). */
  public List<String> activeDeliveries(String riderId) {
    return store.activeDeliveryIds(riderId);
  }

  private DeliveryRow get(String deliveryId) {
    return store
        .delivery(deliveryId)
        .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
  }
}
