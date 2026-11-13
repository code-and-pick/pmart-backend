package com.pmart.delivery.dispatch.api;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.api.generated.InternalApi;
import com.pmart.delivery.dispatch.api.generated.model.Assignment;
import com.pmart.delivery.dispatch.application.DeliveryQueries;
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

  public InternalController(DeliveryQueries queries, DispatchViews views) {
    this.queries = queries;
    this.views = views;
  }

  @Override
  public ResponseEntity<Assignment> getDeliveryAssignment(String deliveryId) {
    var d = queries.assignment(deliveryId);
    if (d.riderId() == null) {
      throw PmartException.notFound("ASSIGNMENT_NOT_FOUND", "아직 배정되지 않았습니다.");
    }
    return ResponseEntity.ok(views.assignment(d));
  }
}
