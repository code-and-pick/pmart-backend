package com.pmart.cases.api;

import com.pmart.cases.api.generated.RiderComplaintsApi;
import com.pmart.cases.api.generated.model.ListCases200ResponsePage;
import com.pmart.cases.api.generated.model.ListRiderComplaints200Response;
import com.pmart.cases.api.generated.model.RecordRiderComplaintRequest;
import com.pmart.cases.api.generated.model.RiderComplaint;
import com.pmart.cases.api.generated.model.RiderComplaintSummary;
import com.pmart.cases.api.generated.model.RiderComplaintType;
import com.pmart.cases.api.generated.model.StatusCounts;
import com.pmart.cases.api.generated.model.StatusCountsCountsInner;
import com.pmart.cases.application.RiderComplaintService;
import com.pmart.cases.infrastructure.RiderComplaintStore.ComplaintRow;
import com.pmart.cases.infrastructure.RiderComplaintStore.Filter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 라이더 컴플레인(0.3.0, R-5). 요청·응답 변환만 — 규칙은 {@link RiderComplaintService}. */
@RestController
public class RiderComplaintsController implements RiderComplaintsApi {

  private final RiderComplaintService service;

  public RiderComplaintsController(RiderComplaintService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<RiderComplaint> recordRiderComplaint(
      String idempotencyKey, String xSourceSystem, RecordRiderComplaintRequest req) {
    ComplaintRow row =
        service.record(
            idempotencyKey,
            xSourceSystem,
            new RiderComplaintService.RecordCommand(
                req.getDeliveryId(),
                req.getComplaintType().getValue(),
                req.getOrderId(),
                req.getNote(),
                req.getInquiryId()));
    return ResponseEntity.status(HttpStatus.CREATED).body(detail(row));
  }

  @Override
  public ResponseEntity<ListRiderComplaints200Response> listRiderComplaints(
      Integer size,
      @Nullable String cursor,
      @Nullable String riderId,
      @Nullable String deliveryId,
      @Nullable RiderComplaintType complaintType,
      @Nullable OffsetDateTime from,
      @Nullable OffsetDateTime to,
      @Nullable String centerId) {
    var page =
        service.list(
            new Filter(
                riderId,
                deliveryId,
                complaintType == null ? null : complaintType.getValue(),
                from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant(),
                blankToNull(centerId)),
            cursor,
            size);
    var out = new ListRiderComplaints200Response();
    out.setItems(page.items().stream().map(RiderComplaintsController::summary).toList());
    var p = new ListCases200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    out.setPage(p);
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<StatusCounts> countRiderComplaints(
      @Nullable String riderId,
      @Nullable String deliveryId,
      @Nullable OffsetDateTime from,
      @Nullable OffsetDateTime to,
      @Nullable String centerId) {
    var counts =
        service.countsByType(
            new Filter(
                riderId,
                deliveryId,
                null,
                from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant(),
                blankToNull(centerId)));
    var out = new StatusCounts();
    out.setCounts(
        counts.entrySet().stream()
            .map(
                e -> {
                  var row = new StatusCountsCountsInner();
                  row.setKey(e.getKey());
                  row.setCount(e.getValue());
                  row.setCapped(false);
                  return row;
                })
            .toList());
    out.setAsOf(OffsetDateTime.now(java.time.ZoneOffset.UTC));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<RiderComplaint> getRiderComplaint(String complaintId) {
    return ResponseEntity.ok(detail(service.get(complaintId)));
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static RiderComplaintSummary summary(ComplaintRow r) {
    var s = new RiderComplaintSummary();
    s.setComplaintId(r.complaintId());
    s.setCaseId(r.caseId());
    s.setRiderId(r.riderId());
    s.setDeliveryId(r.deliveryId());
    s.setOrderId(r.orderId());
    s.setInquiryId(r.inquiryId());
    s.setCenterId(r.centerId());
    s.setComplaintType(RiderComplaintType.fromValue(r.complaintType()));
    s.setSource(r.source());
    s.setRecordedBy(r.recordedBy());
    s.setRecordedAt(r.recordedAt().atOffset(ZoneOffset.UTC));
    return s;
  }

  private static RiderComplaint detail(ComplaintRow r) {
    var d = new RiderComplaint();
    d.setComplaintId(r.complaintId());
    d.setCaseId(r.caseId());
    d.setRiderId(r.riderId());
    d.setDeliveryId(r.deliveryId());
    d.setOrderId(r.orderId());
    d.setInquiryId(r.inquiryId());
    d.setCenterId(r.centerId());
    d.setComplaintType(RiderComplaintType.fromValue(r.complaintType()));
    d.setSource(r.source());
    d.setRecordedBy(r.recordedBy());
    d.setRecordedAt(r.recordedAt().atOffset(ZoneOffset.UTC));
    d.setNote(r.note());
    return d;
  }
}
