package com.pmart.rider.api;

import com.pmart.rider.api.generated.AppealsApi;
import com.pmart.rider.api.generated.model.DecideRiderAppealRequest;
import com.pmart.rider.api.generated.model.ListMyRiderAppeals200Response;
import com.pmart.rider.api.generated.model.RiderAppeal;
import com.pmart.rider.api.generated.model.SubmitRiderAppealRequest;
import com.pmart.rider.application.AppealService;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 라이더 소명·이의 신청(2026-11-18) — 규칙은 {@link AppealService}. */
@RestController
public class AppealsController implements AppealsApi {

  private final AppealService appeals;

  public AppealsController(AppealService appeals) {
    this.appeals = appeals;
  }

  @Override
  public ResponseEntity<RiderAppeal> submitRiderAppeal(
      String idempotencyKey, String xSourceSystem, SubmitRiderAppealRequest req) {
    return ResponseEntity.ok(
        view(
            appeals.submit(
                idempotencyKey,
                req.getTargetType().getValue(),
                req.getTargetRef(),
                req.getBody())));
  }

  @Override
  public ResponseEntity<ListMyRiderAppeals200Response> listMyRiderAppeals() {
    return ResponseEntity.ok(
        new ListMyRiderAppeals200Response()
            .items(appeals.mine().stream().map(AppealsController::view).toList()));
  }

  @Override
  public ResponseEntity<ListMyRiderAppeals200Response> listRiderAppeals(
      @Nullable String state, @Nullable String riderId, @Nullable String targetType) {
    return ResponseEntity.ok(
        new ListMyRiderAppeals200Response()
            .items(
                appeals.forStaff(state, blank(riderId), targetType).stream()
                    .map(AppealsController::view)
                    .toList()));
  }

  @Override
  public ResponseEntity<RiderAppeal> decideRiderAppeal(
      String appealId, String idempotencyKey, String xSourceSystem, DecideRiderAppealRequest req) {
    return ResponseEntity.ok(
        view(
            appeals.decide(appealId, idempotencyKey, req.getDecision().getValue(), req.getNote())));
  }

  private static RiderAppeal view(AppealService.Appeal a) {
    return new RiderAppeal()
        .appealId(a.appealId())
        .riderId(a.riderId())
        .targetType(RiderAppeal.TargetTypeEnum.fromValue(a.targetType()))
        .targetRef(a.targetRef())
        .restriction(a.restriction())
        .body(a.body())
        .state(RiderAppeal.StateEnum.fromValue(a.state()))
        .submittedAt(a.submittedAt().atOffset(ZoneOffset.UTC))
        .decidedBy(a.decidedBy())
        .decidedAt(a.decidedAt() == null ? null : a.decidedAt().atOffset(ZoneOffset.UTC))
        .decisionNote(a.decisionNote())
        .adjustmentRequired(a.adjustmentRequired());
  }

  private static @Nullable String blank(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
