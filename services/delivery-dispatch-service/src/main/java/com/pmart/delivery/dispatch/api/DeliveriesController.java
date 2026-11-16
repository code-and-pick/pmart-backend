package com.pmart.delivery.dispatch.api;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.api.generated.DeliveriesApi;
import com.pmart.delivery.dispatch.api.generated.model.CreateAssignmentCancellationRequest;
import com.pmart.delivery.dispatch.api.generated.model.CreateDispatchRequest202Response;
import com.pmart.delivery.dispatch.api.generated.model.Delivery;
import com.pmart.delivery.dispatch.api.generated.model.GetAssignmentCancellation200Response;
import com.pmart.delivery.dispatch.application.CancellationService;
import com.pmart.delivery.dispatch.application.DeliveryQueries;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 DeliveriesApi — 배송 배차 조회, 배차 취소(원문 §5.4 — 픽업 전 재배차 / 픽업 후 지원센터). */
@RestController
public class DeliveriesController implements DeliveriesApi {

  private final DeliveryQueries queries;
  private final CancellationService cancellations;
  private final DispatchViews views;

  public DeliveriesController(
      DeliveryQueries queries, CancellationService cancellations, DispatchViews views) {
    this.queries = queries;
    this.cancellations = cancellations;
    this.views = views;
  }

  @Override
  public ResponseEntity<Delivery> getDeliveryDispatch(String deliveryId) {
    var v = queries.dispatch(deliveryId);
    return ResponseEntity.ok(
        views.delivery(
            v.delivery(),
            v.attempts(),
            v.offers(),
            v.showAssignment(),
            v.staff(),
            v.originRiderId(),
            Instant.now()));
  }

  @Override
  public ResponseEntity<CreateDispatchRequest202Response> createAssignmentCancellation(
      String deliveryId,
      String idempotencyKey,
      String xSourceSystem,
      CreateAssignmentCancellationRequest body,
      @Nullable Integer xExpectedVersion) {
    var r =
        cancellations.cancel(
            deliveryId,
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new CancellationService.Cancel(
                body.getRequestedBy().getValue(),
                body.getReason().getValue(),
                body.getReasonDetail(),
                !Boolean.FALSE.equals(body.getReassign()),
                body.getApprovalRef()));
    if ("REJECTED_PICKED_UP".equals(r.state())) {
      // 기록은 남기고(같은 키는 같은 답) 픽업 뒤는 지원센터로
      throw new PmartException(
          "PICKED_UP_CASE_REQUIRED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "픽업 뒤 배차 취소는 지원센터에서 상품 위치·회수·재인계를 확인한 뒤 처리합니다.",
          false,
          List.of());
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(
            new CreateDispatchRequest202Response()
                .status(CreateDispatchRequest202Response.StatusEnum.APPLIED)
                .requestId(r.requestId())
                .resultId(r.reassignAttemptId() == null ? r.deliveryId() : r.reassignAttemptId())
                .version(r.version())
                .retryable(false)
                .occurredAt(DispatchViews.time(r.occurredAt())));
  }

  @Override
  public ResponseEntity<GetAssignmentCancellation200Response> getAssignmentCancellation(
      String deliveryId, String requestId) {
    var c = cancellations.result(deliveryId, requestId);
    return ResponseEntity.ok(
        new GetAssignmentCancellation200Response()
            .requestId(c.requestId())
            .state(GetAssignmentCancellation200Response.StateEnum.fromValue(c.state()))
            .reassignAttemptId(c.reassignAttemptId()));
  }
}
