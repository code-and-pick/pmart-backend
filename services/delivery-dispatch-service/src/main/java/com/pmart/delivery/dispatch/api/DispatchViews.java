package com.pmart.delivery.dispatch.api;

import com.pmart.delivery.dispatch.api.generated.model.Assignment;
import com.pmart.delivery.dispatch.api.generated.model.CreateDeliveryRequestArrivalWindow;
import com.pmart.delivery.dispatch.api.generated.model.Delivery;
import com.pmart.delivery.dispatch.api.generated.model.DeliveryListItem;
import com.pmart.delivery.dispatch.api.generated.model.DeliveryType;
import com.pmart.delivery.dispatch.api.generated.model.DispatchAttempt;
import com.pmart.delivery.dispatch.api.generated.model.DispatchState;
import com.pmart.delivery.dispatch.api.generated.model.Offer;
import com.pmart.delivery.dispatch.api.generated.model.OfferResponseResult;
import com.pmart.delivery.dispatch.api.generated.model.OfferSummary;
import com.pmart.delivery.dispatch.api.generated.model.PackageSpec;
import com.pmart.delivery.dispatch.application.DispatchProperties;
import com.pmart.delivery.dispatch.application.OfferService;
import com.pmart.delivery.dispatch.domain.SearchRules;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.AttemptRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.OfferRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 저장 행 → 계약 응답. 고객 주소는 동 이름까지만. */
@Component
class DispatchViews {

  /** 포장 합계(무게·봉투·주류). */
  record Packages(List<String> ids, @Nullable Integer weightG, int bags, boolean alcohol) {}

  private final ObjectMapper mapper;
  private final DispatchProperties props;

  DispatchViews(ObjectMapper mapper, DispatchProperties props) {
    this.mapper = mapper;
    this.props = props;
  }

  Packages packages(DeliveryRow d) {
    List<String> ids = new ArrayList<>();
    Integer weight = null;
    int bags = 0;
    boolean alcohol = false;
    for (JsonNode n : mapper.readTree(d.packagesJson())) {
      ids.add(n.path("packageId").asString());
      if (n.path("weightG").isNumber()) {
        weight = (weight == null ? 0 : weight) + n.path("weightG").asInt();
      }
      bags += n.path("bagCount").asInt(0);
      alcohol |= n.path("alcohol").asBoolean(false);
    }
    return new Packages(ids, weight, bags, alcohol);
  }

  Offer offer(OfferRow o, DeliveryRow d, Instant now) {
    Packages p = packages(d);
    long remaining =
        "SENT".equals(o.state())
            ? Math.max(0, o.expiresAt().getEpochSecond() - now.getEpochSecond())
            : 0;
    return new Offer()
        .offerId(o.offerId())
        .attemptId(o.attemptId())
        .deliveryId(o.deliveryId())
        .riderId(o.riderId())
        .radiusKm(BigDecimal.valueOf(o.radiusKm()))
        .sentAt(time(o.sentAt()))
        .expiresAt(time(o.expiresAt()))
        .remainingSec((int) remaining)
        .state(Offer.StateEnum.fromValue(o.state()))
        .viewedAt(time(o.viewedAt()))
        .source(Offer.SourceEnum.fromValue(o.source()))
        .bundleId(o.bundleId())
        .bundleSize(o.bundleSize())
        .stopSequence(o.stopSeq())
        .summary(
            new OfferSummary()
                .mfcId(d.mfcId())
                .dropAreaLabel(d.dropAreaLabel())
                .distanceKm(km(d.dropDistanceM()))
                .pickupDistanceKm(km(o.pickupDistanceM()))
                .pickupEtaSeconds(o.pickupDurationS())
                .weightG(p.weightG())
                .bagCount(p.bags())
                .containsAlcohol(p.alcohol())
                .deliveryMode(
                    d.deliveryMode() == null
                        ? null
                        : OfferSummary.DeliveryModeEnum.fromValue(d.deliveryMode()))
                .estimatedFee(o.fee()));
  }

  DeliveryListItem listItem(OfferService.ListItem i) {
    DeliveryRow d = i.delivery();
    Packages p = packages(d);
    return new DeliveryListItem()
        .deliveryId(d.deliveryId())
        .mfcId(d.mfcId())
        .dropAreaLabel(d.dropAreaLabel())
        .deliveryMode(
            d.deliveryMode() == null
                ? null
                : DeliveryListItem.DeliveryModeEnum.fromValue(d.deliveryMode()))
        .straightDistanceKm(BigDecimal.valueOf(i.straightKm()).setScale(2, RoundingMode.HALF_UP))
        .distanceKm(km(d.dropDistanceM()))
        .estimatedFee(i.fee())
        .weightG(p.weightG())
        .bagCount(p.bags())
        .containsAlcohol(p.alcohol())
        .readyAt(time(i.readyAt()));
  }

  OfferResponseResult result(OfferService.OfferResult r) {
    return new OfferResponseResult()
        .offerId(r.offerId())
        .outcome(OfferResponseResult.OutcomeEnum.fromValue(r.outcome()))
        .lateReason(
            r.lateReason() == null
                ? null
                : OfferResponseResult.LateReasonEnum.fromValue(r.lateReason()))
        .deliveryId(r.deliveryId())
        .assignmentId(r.assignmentId())
        .consecutiveRejections(r.consecutiveRejections())
        .rejectionNotice(r.rejectionNotice())
        .decidedAt(time(r.decidedAt()));
  }

  Delivery delivery(DeliveryRow d, List<AttemptRow> attempts, List<OfferRow> offers, Instant now) {
    Packages p = packages(d);
    Delivery out =
        new Delivery()
            .deliveryId(d.deliveryId())
            .version(d.version())
            .type(DeliveryType.fromValue(d.type()))
            .orderId(d.orderId())
            .shipmentId(d.shipmentId())
            .mfcId(d.mfcId())
            .state(DispatchState.fromValue(d.state()))
            .packageSpec(
                new PackageSpec()
                    .weightG(p.weightG())
                    .bagCount(p.bags())
                    .containsAlcohol(p.alcohol())
                    .packageIds(p.ids()))
            .assignment(d.riderId() == null ? null : assignment(d))
            .createdAt(time(d.createdAt()))
            .updatedAt(time(d.updatedAt()));
    if (d.arrivalDate() != null) {
      out.arrivalWindow(
          new CreateDeliveryRequestArrivalWindow()
              .date(d.arrivalDate())
              .start(d.arrivalStart())
              .end(d.arrivalEnd()));
    }
    for (AttemptRow a : attempts) {
      DispatchAttempt v =
          new DispatchAttempt()
              .attemptId(a.attemptId())
              .deliveryId(a.deliveryId())
              .trigger(
                  DispatchAttempt.TriggerEnum.fromValue(a.retryCount() > 0 ? "RETRY" : a.trigger()))
              .state(DispatchAttempt.StateEnum.fromValue(a.state()))
              .retryCount(a.retryCount())
              .startedAt(time(a.startedAt()))
              .deadlineAt(time(a.nextRunAt()))
              .failureReason(
                  a.failureReason() == null
                      ? null
                      : DispatchAttempt.FailureReasonEnum.fromValue(a.failureReason()));
      v.setCurrentRadiusKm(BigDecimal.valueOf(SearchRules.radiusKm(props.radiiKm(), a.stage())));
      for (OfferRow o : offers) {
        if (o.attemptId().equals(a.attemptId())) {
          v.addOffersItem(offer(o, d, now));
        }
      }
      out.addAttemptsItem(v);
    }
    return out;
  }

  Assignment assignment(DeliveryRow d) {
    Assignment.StateEnum state =
        switch (d.state()) {
          case "PICKED_UP" -> Assignment.StateEnum.PICKED_UP;
          case "CANCELLED_BEFORE_PICKUP" -> Assignment.StateEnum.CANCELLED;
          case "CLOSED" -> Assignment.StateEnum.COMPLETED;
          default -> Assignment.StateEnum.ASSIGNED;
        };
    return new Assignment()
        .assignmentId(d.assignmentId())
        .deliveryId(d.deliveryId())
        .riderId(d.riderId())
        .state(state)
        .assignedAt(time(d.assignedAt()))
        .bundleId(d.bundleId())
        .stopSequence(d.stopSeq())
        .pickedUpAt(time(d.pickedUpAt()))
        .cancelledAt(time(d.cancelledAt()))
        .version(d.assignmentVersion());
  }

  static @Nullable BigDecimal km(@Nullable Integer meters) {
    return meters == null
        ? null
        : BigDecimal.valueOf(meters).movePointLeft(3).setScale(2, RoundingMode.HALF_UP);
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
