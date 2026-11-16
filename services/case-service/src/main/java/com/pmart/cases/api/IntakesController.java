package com.pmart.cases.api;

import com.pmart.cases.api.generated.IntakesApi;
import com.pmart.cases.api.generated.model.AcceptShiftHandoverRequest;
import com.pmart.cases.api.generated.model.CreateShiftHandoverRequest;
import com.pmart.cases.api.generated.model.ListCases200ResponsePage;
import com.pmart.cases.api.generated.model.ListShiftHandovers200Response;
import com.pmart.cases.api.generated.model.ShiftHandover;
import com.pmart.cases.api.generated.model.ShiftHandoverItemsInner;
import com.pmart.cases.api.generated.model.ShiftHandoverTeamSnapshotInner;
import com.pmart.cases.application.ShiftHandoverService;
import com.pmart.cases.infrastructure.ShiftHandoverStore.HandoverRow;
import com.pmart.cases.infrastructure.ShiftHandoverStore.ItemRow;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 IntakesApi — 지금은 교대 인수인계(0.9.0, 2026-11-16 BO-27)만. CS 접수·도움요청 접수는 인터페이스 default 501. 규칙은
 * {@link ShiftHandoverService}, 여기서는 요청·응답 변환만.
 */
@RestController
public class IntakesController implements IntakesApi {

  private final ShiftHandoverService handovers;

  public IntakesController(ShiftHandoverService handovers) {
    this.handovers = handovers;
  }

  @Override
  public ResponseEntity<ShiftHandover> createShiftHandover(
      String idempotencyKey, String xSourceSystem, CreateShiftHandoverRequest body) {
    ShiftHandoverService.View v =
        handovers.create(
            idempotencyKey,
            xSourceSystem,
            new ShiftHandoverService.Create(
                body.getGroup().getValue(),
                body.getCenterId(),
                body.getFromOperatorId(),
                body.getToOperatorId(),
                body.getItems().stream()
                    .map(
                        i ->
                            new ShiftHandoverService.Item(
                                i.getCaseId(),
                                i.getNextAction(),
                                i.getCheckDueAt() == null ? null : i.getCheckDueAt().toInstant()))
                    .toList(),
                body.getNote()));
    return ResponseEntity.created(URI.create("/api/v1/shift-handovers/" + v.row().handoverId()))
        .body(toModel(v));
  }

  @Override
  public ResponseEntity<ShiftHandover> acceptShiftHandover(
      String shiftHandoverId,
      String idempotencyKey,
      String xSourceSystem,
      AcceptShiftHandoverRequest body) {
    List<String> excluded =
        body == null || body.getExcludedCaseIds() == null ? List.of() : body.getExcludedCaseIds();
    return ResponseEntity.ok(
        toModel(handovers.accept(idempotencyKey, xSourceSystem, shiftHandoverId, excluded)));
  }

  @Override
  public ResponseEntity<ShiftHandover> cancelShiftHandover(
      String shiftHandoverId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(
        toModel(handovers.cancel(idempotencyKey, xSourceSystem, shiftHandoverId)));
  }

  @Override
  public ResponseEntity<ShiftHandover> getShiftHandover(String shiftHandoverId) {
    return ResponseEntity.ok(toModel(handovers.get(shiftHandoverId)));
  }

  @Override
  public ResponseEntity<ListShiftHandovers200Response> listShiftHandovers(
      @Nullable String state, @Nullable Boolean unreceived, Integer size, @Nullable String cursor) {
    ShiftHandoverService.Page page =
        handovers.list(state, Boolean.TRUE.equals(unreceived), size == null ? 20 : size, cursor);
    return ResponseEntity.ok(
        new ListShiftHandovers200Response()
            .items(page.items().stream().map(IntakesController::toModel).toList())
            .page(new ListCases200ResponsePage().nextCursor(page.nextCursor())));
  }

  private static ShiftHandover toModel(ShiftHandoverService.View v) {
    HandoverRow h = v.row();
    ShiftHandover m =
        new ShiftHandover()
            .shiftHandoverId(h.handoverId())
            .group(h.group())
            .centerId(h.centerId())
            .fromOperatorId(h.fromOperatorId())
            .toOperatorId(h.toOperatorId())
            .createdBy(h.createdBy())
            .state(ShiftHandover.StateEnum.fromValue(h.state()))
            .unreceived(v.unreceived())
            .note(h.note())
            .createdAt(odt(h.createdAt()))
            .completedAt(odt(h.completedAt()))
            .cancelledAt(odt(h.cancelledAt()));
    for (ItemRow i : v.items()) {
      m.addItemsItem(
          new ShiftHandoverItemsInner()
              .caseId(i.caseId())
              .nextAction(i.nextAction())
              .checkDueAt(odt(i.checkDueAt()))
              .caseStateAt(i.caseStateAt())
              .priorityAt(i.priorityAt())
              .openObligationsAt(i.openObligationsAt())
              .outcome(ShiftHandoverItemsInner.OutcomeEnum.fromValue(i.outcome()))
              .resolvedAt(odt(i.resolvedAt())));
    }
    for (Map<String, Object> s : h.teamSnapshot()) {
      m.addTeamSnapshotItem(
          new ShiftHandoverTeamSnapshotInner()
              .caseId(str(s.get("caseId")))
              .kind(str(s.get("kind")))
              .state(str(s.get("state")))
              .priority(str(s.get("priority")))
              .assigneeId(str(s.get("assigneeId")))
              .openObligationCount(
                  s.get("openObligationCount") instanceof Number n ? n.intValue() : 0));
    }
    return m;
  }

  private static @Nullable String str(@Nullable Object o) {
    return o == null ? null : o.toString();
  }

  private static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
