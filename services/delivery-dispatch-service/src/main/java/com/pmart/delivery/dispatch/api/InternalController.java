package com.pmart.delivery.dispatch.api;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.api.generated.InternalApi;
import com.pmart.delivery.dispatch.api.generated.model.Assignment;
import com.pmart.delivery.dispatch.api.generated.model.CancelRedeliveryRequest;
import com.pmart.delivery.dispatch.api.generated.model.CreateDispatchRequest202Response;
import com.pmart.delivery.dispatch.api.generated.model.GetDeliveryRoutePoints200Response;
import com.pmart.delivery.dispatch.api.generated.model.GetRiderActiveDeliveries200Response;
import com.pmart.delivery.dispatch.api.generated.model.LatLng;
import com.pmart.delivery.dispatch.api.generated.model.ReleaseRedeliveryRequest;
import com.pmart.delivery.dispatch.application.DeliveryQueries;
import com.pmart.delivery.dispatch.application.RedeliveryCommands;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi — 배정 상태 조회(orchestration S3·execution). 배송 생성(POST /internal/deliveries)은 MFC 사건으로
 * 하므로 아직 501(회수·재배송 DD-2 때).
 */
@RestController
public class InternalController implements InternalApi {

  private final DeliveryQueries queries;
  private final DispatchViews views;
  private final RedeliveryCommands redelivery;

  public InternalController(
      DeliveryQueries queries, DispatchViews views, RedeliveryCommands redelivery) {
    this.queries = queries;
    this.views = views;
    this.redelivery = redelivery;
  }

  /** 오배송 다시 배송 결제됨 → 배차 풀기(ADR-0065 4단계, orchestration). 같은 요청은 같은 결과(행 상태로 멱등). */
  @Override
  public ResponseEntity<CreateDispatchRequest202Response> releaseRedelivery(
      String idempotencyKey,
      String xSourceSystem,
      String deliveryId,
      ReleaseRedeliveryRequest req) {
    return ResponseEntity.ok(
        applied(idempotencyKey, redelivery.release(deliveryId, req.getCaseId())));
  }

  /** 오배송 다시 배송 결제 안 됨 → 픽업 전 취소 + MFC 회수(ADR-0065 4단계). resultId = 회수 배송 ID. */
  @Override
  public ResponseEntity<CreateDispatchRequest202Response> cancelRedelivery(
      String idempotencyKey, String xSourceSystem, String deliveryId, CancelRedeliveryRequest req) {
    return ResponseEntity.ok(
        applied(
            idempotencyKey,
            redelivery.cancel(deliveryId, req.getCaseId(), req.getReason().getValue())));
  }

  private static CreateDispatchRequest202Response applied(String requestId, String resultId) {
    return new CreateDispatchRequest202Response()
        .status(CreateDispatchRequest202Response.StatusEnum.APPLIED)
        .requestId(requestId)
        .resultId(resultId)
        .occurredAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
  }

  @Override
  public ResponseEntity<Assignment> getDeliveryAssignment(String deliveryId) {
    var d = queries.assignment(deliveryId);
    if (d.riderId() == null) {
      throw PmartException.notFound("ASSIGNMENT_NOT_FOUND", "아직 배정되지 않았습니다.");
    }
    // 내부 조회에만 주문·센터(orchestration 재발송 사가가 배송으로 주문을 찾는다 — 2026-11-16)
    return ResponseEntity.ok(views.assignment(d).orderId(d.orderId()).mfcId(d.mfcId()));
  }

  /** TR-1: tracking ETA 계산용 픽업지·도착지 좌표(서비스 간, 저장·로그 금지 — no-store). */
  @Override
  public ResponseEntity<GetDeliveryRoutePoints200Response> getDeliveryRoutePoints(
      String deliveryId) {
    var d = queries.assignment(deliveryId);
    Double pickupLat = d.pickupLat() != null ? d.pickupLat() : d.mfcLat();
    Double pickupLng = d.pickupLat() != null ? d.pickupLng() : d.mfcLng();
    if (pickupLat == null || pickupLng == null || d.dropLat() == null || d.dropLng() == null) {
      throw PmartException.notFound("ROUTE_POINTS_NOT_READY", "배송 좌표가 아직 준비되지 않았습니다.");
    }
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(
            new GetDeliveryRoutePoints200Response()
                .deliveryId(deliveryId)
                .pickup(new LatLng().lat(pickupLat).lng(pickupLng))
                .drop(new LatLng().lat(d.dropLat()).lng(d.dropLng()))
                .pickupToDropS(d.dropDurationS()));
  }

  @Override
  public ResponseEntity<GetRiderActiveDeliveries200Response> getRiderActiveDeliveries(
      String riderId) {
    return ResponseEntity.ok(
        new GetRiderActiveDeliveries200Response()
            .riderId(riderId)
            .deliveryIds(queries.activeDeliveries(riderId)));
  }
}
