package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.WorkforceApi;
import com.pmart.cdc.outbound.api.generated.model.AssignShiftRequest;
import com.pmart.cdc.outbound.api.generated.model.GetStaffing200Response;
import com.pmart.cdc.outbound.api.generated.model.ListShiftAssignments200Response;
import com.pmart.cdc.outbound.api.generated.model.ListShifts200Response;
import com.pmart.cdc.outbound.api.generated.model.ListShifts200ResponseShiftsInner;
import com.pmart.cdc.outbound.api.generated.model.ShiftAssignment;
import com.pmart.cdc.outbound.api.generated.model.StaffingCell;
import com.pmart.cdc.outbound.api.generated.model.WorkRole;
import com.pmart.cdc.outbound.application.Workforce;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 WorkforceApi(0.29.0 WF-2) — 변환만, 규칙은 {@link Workforce}. */
@RestController
public class WorkforceController implements WorkforceApi {

  private final Workforce workforce;

  public WorkforceController(Workforce workforce) {
    this.workforce = workforce;
  }

  @Override
  public ResponseEntity<ListShifts200Response> listShifts(String cdcId) {
    var s = workforce.shifts(cdcId);
    return ResponseEntity.ok(
        new ListShifts200Response()
            .shifts(
                new ArrayList<>(
                    s.shifts().stream()
                        .map(
                            x ->
                                new ListShifts200ResponseShiftsInner()
                                    .shiftCode(x.shiftCode())
                                    .name(x.name())
                                    .startTime(x.startTime())
                                    .endTime(x.endTime())
                                    .breaks(new ArrayList<>(x.breaks()))
                                    .source(x.source()))
                        .toList()))
            .plan(new ArrayList<>(s.plan().stream().map(WorkforceController::cell).toList())));
  }

  @Override
  public ResponseEntity<ListShiftAssignments200Response> listShiftAssignments(
      String cdcId, LocalDate date, @Nullable String shiftCode) {
    var items =
        workforce
            .assignments(
                cdcId, date, shiftCode == null || shiftCode.isBlank() ? null : shiftCode.strip())
            .stream()
            .map(WorkforceController::dto)
            .toList();
    return ResponseEntity.ok(new ListShiftAssignments200Response().items(new ArrayList<>(items)));
  }

  @Override
  public ResponseEntity<ShiftAssignment> assignShift(
      String cdcId, String idempotencyKey, String xSourceSystem, AssignShiftRequest b) {
    return ResponseEntity.ok(
        dto(
            workforce.assign(
                cdcId,
                b.getWorkDate(),
                b.getShiftCode(),
                b.getWorkerId(),
                b.getFloor().getValue(),
                b.getRole().getValue())));
  }

  @Override
  public ResponseEntity<ShiftAssignment> checkInShift(
      String cdcId, String assignmentId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(dto(workforce.checkIn(cdcId, assignmentId)));
  }

  @Override
  public ResponseEntity<ShiftAssignment> checkOutShift(
      String cdcId, String assignmentId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(dto(workforce.checkOut(cdcId, assignmentId)));
  }

  @Override
  public ResponseEntity<GetStaffing200Response> getStaffing(String cdcId, LocalDate date) {
    return ResponseEntity.ok(
        new GetStaffing200Response()
            .date(date)
            .cells(
                new ArrayList<>(
                    workforce.staffing(cdcId, date).stream()
                        .map(WorkforceController::cell)
                        .toList())));
  }

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  static StaffingCell cell(Workforce.Cell c) {
    return new StaffingCell()
        .shiftCode(c.shiftCode())
        .floor(c.floor())
        .role(WorkRole.fromValue(c.role()))
        .planned(c.planned())
        .assigned(c.assigned())
        .checkedIn(c.checkedIn());
  }

  static ShiftAssignment dto(Workforce.Assignment a) {
    return new ShiftAssignment()
        .assignmentId(a.assignmentId())
        .workDate(a.workDate())
        .shiftCode(a.shiftCode())
        .workerId(a.workerId())
        .floor(a.floor())
        .role(WorkRole.fromValue(a.role()))
        .assignedBy(a.assignedBy())
        .checkInAt(time(a.checkInAt()))
        .checkOutAt(time(a.checkOutAt()));
  }
}
