package com.pmart.delivery.dispatch.api;

import com.pmart.delivery.dispatch.api.generated.DeliveriesApi;
import com.pmart.delivery.dispatch.api.generated.model.Delivery;
import com.pmart.delivery.dispatch.application.DeliveryQueries;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 DeliveriesApi — 배송 배차 조회. 배차 취소(assignment-cancellations)는 아직 501(DD-1 다음 묶음). */
@RestController
public class DeliveriesController implements DeliveriesApi {

  private final DeliveryQueries queries;
  private final DispatchViews views;

  public DeliveriesController(DeliveryQueries queries, DispatchViews views) {
    this.queries = queries;
    this.views = views;
  }

  @Override
  public ResponseEntity<Delivery> getDeliveryDispatch(String deliveryId) {
    var v = queries.dispatch(deliveryId);
    return ResponseEntity.ok(views.delivery(v.delivery(), v.attempts(), v.offers(), Instant.now()));
  }
}
