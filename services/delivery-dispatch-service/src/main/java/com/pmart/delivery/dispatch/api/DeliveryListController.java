package com.pmart.delivery.dispatch.api;

import com.pmart.delivery.dispatch.api.generated.DeliveryListApi;
import com.pmart.delivery.dispatch.api.generated.model.ListDeliveryList200Response;
import com.pmart.delivery.dispatch.api.generated.model.OfferResponseResult;
import com.pmart.delivery.dispatch.application.OfferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 DeliveryListApi — 일반 배차모드 배달리스트·수락(원문 §2.4). */
@RestController
public class DeliveryListController implements DeliveryListApi {

  private final OfferService offers;
  private final DispatchViews views;

  public DeliveryListController(OfferService offers, DispatchViews views) {
    this.offers = offers;
    this.views = views;
  }

  @Override
  public ResponseEntity<ListDeliveryList200Response> listDeliveryList() {
    var r = offers.deliveryList();
    return ResponseEntity.ok(
        new ListDeliveryList200Response()
            .items(r.items().stream().map(views::listItem).toList())
            .serverTime(DispatchViews.time(r.serverTime()))
            .pollAfterSec(r.pollAfterSec()));
  }

  @Override
  public ResponseEntity<OfferResponseResult> acceptFromDeliveryList(
      String deliveryId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(
        views.result(offers.acceptFromList(deliveryId, idempotencyKey, xSourceSystem)));
  }
}
