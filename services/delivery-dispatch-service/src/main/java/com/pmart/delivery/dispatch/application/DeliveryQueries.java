package com.pmart.delivery.dispatch.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.AttemptRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.OfferRow;
import java.util.List;
import org.springframework.stereotype.Service;

/** 배송 배차 조회 — 운영자(그 센터 또는 OPERATOR_ADMIN)·라이더(배정받았거나 제안받은 배송)·서비스. */
@Service
public class DeliveryQueries {

  public record DeliveryView(
      DeliveryRow delivery, List<AttemptRow> attempts, List<OfferRow> offers) {}

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
    switch (p.type()) {
      case OPERATOR -> {
        if (!p.canAccessCenter(d.mfcId()) && !p.hasRole("OPERATOR_ADMIN")) {
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
      }
      case SERVICE -> {}
      default -> throw OfferService.forbidden("볼 수 없습니다.");
    }
    return new DeliveryView(d, attempts, offers);
  }

  public DeliveryRow assignment(String deliveryId) {
    return get(deliveryId);
  }

  private DeliveryRow get(String deliveryId) {
    return store
        .delivery(deliveryId)
        .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
  }
}
