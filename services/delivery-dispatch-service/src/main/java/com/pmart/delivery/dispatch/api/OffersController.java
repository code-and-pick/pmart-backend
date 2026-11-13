package com.pmart.delivery.dispatch.api;

import com.pmart.delivery.dispatch.api.generated.OffersApi;
import com.pmart.delivery.dispatch.api.generated.model.ListMyOffers200Response;
import com.pmart.delivery.dispatch.api.generated.model.ListMyOffers200ResponsePage;
import com.pmart.delivery.dispatch.api.generated.model.Offer;
import com.pmart.delivery.dispatch.api.generated.model.OfferResponseResult;
import com.pmart.delivery.dispatch.api.generated.model.RespondToOfferRequest;
import com.pmart.delivery.dispatch.application.OfferService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 OffersApi — AI 추천 제안 목록·조회·응답(라이더). 규칙은 {@link OfferService}. */
@RestController
public class OffersController implements OffersApi {

  private final OfferService offers;
  private final DispatchViews views;

  public OffersController(OfferService offers, DispatchViews views) {
    this.offers = offers;
    this.views = views;
  }

  @Override
  public ResponseEntity<ListMyOffers200Response> listMyOffers(
      @Nullable String state, @Nullable Integer size, @Nullable String cursor) {
    var r = offers.myOffers(state, size == null ? 20 : size, cursor);
    return ResponseEntity.ok(
        new ListMyOffers200Response()
            .items(
                r.items().stream()
                    .map(v -> views.offer(v.offer(), v.delivery(), r.serverTime()))
                    .toList())
            .page(
                new ListMyOffers200ResponsePage().nextCursor(r.nextCursor()).size(r.items().size()))
            .serverTime(DispatchViews.time(r.serverTime()))
            .pollAfterSec(r.pollAfterSec()));
  }

  @Override
  public ResponseEntity<Offer> getOffer(String offerId) {
    var v = offers.offer(offerId);
    return ResponseEntity.ok(views.offer(v.offer(), v.delivery(), java.time.Instant.now()));
  }

  @Override
  public ResponseEntity<OfferResponseResult> respondToOffer(
      String offerId, String idempotencyKey, String xSourceSystem, RespondToOfferRequest body) {
    return ResponseEntity.ok(
        views.result(
            offers.respond(
                offerId,
                idempotencyKey,
                xSourceSystem,
                body.getResponse().getValue(),
                body.getRejectReason() == null ? null : body.getRejectReason().getValue())));
  }
}
