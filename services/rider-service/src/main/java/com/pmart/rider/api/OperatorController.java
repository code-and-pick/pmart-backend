package com.pmart.rider.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.rider.api.generated.OperatorApi;
import com.pmart.rider.api.generated.model.AccountRestriction;
import com.pmart.rider.api.generated.model.ListRiderRestrictionReviews200Response;
import com.pmart.rider.api.generated.model.ListRiderRestrictions200Response;
import com.pmart.rider.api.generated.model.OperatingStatus;
import com.pmart.rider.api.generated.model.RestrictionReview;
import com.pmart.rider.api.generated.model.RestrictionReviewComplaintsInner;
import com.pmart.rider.api.generated.model.RestrictionReviewDecision;
import com.pmart.rider.api.generated.model.RiderPiiField;
import com.pmart.rider.api.generated.model.RiderPiiReveal;
import com.pmart.rider.api.generated.model.RiderPiiRevealRequest;
import com.pmart.rider.api.generated.model.RiderProfileUpdateDeliveryAreasInner;
import com.pmart.rider.api.generated.model.RiderRestrictionChange;
import com.pmart.rider.api.generated.model.RiderRestrictionRecord;
import com.pmart.rider.api.generated.model.StaffRiderDetail;
import com.pmart.rider.api.generated.model.StaffRiderDetailRestriction;
import com.pmart.rider.api.generated.model.StaffRiderSearchResult;
import com.pmart.rider.api.generated.model.StaffRiderSummary;
import com.pmart.rider.api.generated.model.VehicleType;
import com.pmart.rider.application.ComplaintService;
import com.pmart.rider.application.RiderService;
import com.pmart.rider.application.StaffRiderService;
import com.pmart.rider.domain.Rider;
import com.pmart.rider.infrastructure.RiderStore;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 OperatorApi — 계정 제한 걸기·풀기·이력(RS-1, 2026-11-14), 직원 라이더 찾기·상세·원문 열람(0.4.0, 2026-11-15 BOD-3·6).
 * 권한 판정은 RiderService·StaffRiderService.
 */
@RestController
public class OperatorController implements OperatorApi {

  private final RiderService riders;
  private final ComplaintService complaints;
  private final StaffRiderService staff;
  private final ObjectMapper json;

  public OperatorController(
      RiderService riders,
      ComplaintService complaints,
      StaffRiderService staff,
      ObjectMapper json) {
    this.riders = riders;
    this.complaints = complaints;
    this.staff = staff;
    this.json = json;
  }

  @Override
  public ResponseEntity<StaffRiderSearchResult> searchRidersForStaff(
      @Nullable String riderId,
      @Nullable String phone,
      @Nullable String riderNumber,
      @Nullable String riderIds,
      @Nullable AccountRestriction restriction,
      @Nullable OperatingStatus operatingStatus,
      @Nullable String operatingMfcId,
      @Nullable String mfcId,
      @Nullable VehicleType vehicleType,
      @Nullable Boolean withdrawn,
      String sort,
      Integer page,
      Integer size) {
    if (!"JOINED_DESC".equals(sort) && !"STATE_CHANGED_DESC".equals(sort)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "sort 는 JOINED_DESC·STATE_CHANGED_DESC 입니다.");
    }
    List<Rider> rows =
        staff.search(
            new RiderStore.Search(
                blankToNull(riderId),
                phone == null || phone.isBlank() ? null : StaffRiderService.normalizePhone(phone),
                restriction == null ? null : restriction.getValue(),
                operatingStatus == null ? null : operatingStatus.getValue(),
                blankToNull(operatingMfcId),
                vehicleType == null ? null : vehicleType.getValue(),
                "STATE_CHANGED_DESC".equals(sort),
                riderNumberQuery(riderNumber),
                ids(riderIds),
                Boolean.TRUE.equals(withdrawn)
                    ? RiderStore.Search.Scope.WITHDRAWN
                    : RiderStore.Search.Scope.ACTIVE,
                blankToNull(mfcId)),
            size + 1, // 한 줄 더 읽어 다음 쪽이 있는지 본다
            page * size); // 건너뛸 수는 쪽 크기 기준(page ≤ 1000, size ≤ 50 — 계약)
    boolean more = rows.size() > size;
    List<Rider> items = more ? rows.subList(0, size) : rows;
    return ResponseEntity.ok(
        new StaffRiderSearchResult()
            .items(items.stream().map(OperatorController::summary).toList())
            .page(page)
            .size(items.size())
            .hasNext(more));
  }

  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.RiderDisplayNames> getRiderDisplayNames(
      String riderIds) {
    List<String> list = ids(riderIds);
    if (list == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "riderIds 가 비었습니다.");
    }
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.RiderDisplayNames()
            .items(
                staff.displayNames(list).stream()
                    .map(
                        r ->
                            new com.pmart.rider.api.generated.model.RiderDisplayNamesItemsInner()
                                .riderId(r.riderId())
                                .riderNumber(r.riderNumber())
                                .nameMasked(
                                    r.name() == null || r.name().isBlank()
                                        ? null
                                        : RiderViews.maskName(r.name()))
                                .withdrawn(r.withdrawnAt() != null))
                    .toList()));
  }

  @Override
  public ResponseEntity<StaffRiderDetail> getRiderForStaff(
      String riderId, @Nullable Boolean includeWithdrawn) {
    Rider r = staff.detail(riderId, Boolean.TRUE.equals(includeWithdrawn));
    return ResponseEntity.ok(
        new StaffRiderDetail()
            .riderId(r.riderId())
            .riderNumber(r.riderNumber())
            .version(r.version())
            // 가입 승인(2026-11-17 — 정책 전까지 자동 승인)
            .signupStatus(com.pmart.rider.api.generated.model.SignupStatus.APPROVED)
            .signupDecidedBy("AUTO")
            // 정산계좌는 있는지·은행 이름만(2026-11-18 요청 #2)
            .bankAccountRegistered(
                r.bankAccountNumber() != null && !r.bankAccountNumber().isBlank())
            .bankName(
                r.bankCode() == null
                    ? null
                    : com.pmart.rider.domain.BankCodes.ALL.get(r.bankCode()))
            .consents(
                staff.consents(r.riderId()).stream()
                    .map(
                        c ->
                            new com.pmart.rider.api.generated.model.StaffRiderDetailConsentsInner()
                                .consentType(c.consentType())
                                .version(c.version())
                                .agreedAt(RiderViews.time(c.agreedAt())))
                    .toList())
            .nameMasked(r.name() == null ? null : RiderViews.maskName(r.name()))
            .phoneMasked(r.phone() == null ? "" : RiderViews.maskPhone(r.phone()))
            .vehicleType(VehicleType.fromValue(r.vehicleType()))
            .deliveryAreas(
                r.deliveryAreas().stream()
                    .map(
                        a ->
                            new RiderProfileUpdateDeliveryAreasInner()
                                .mfcId(a.mfcId())
                                .zoneId(a.zoneId().isEmpty() ? null : a.zoneId()))
                    .toList())
            .operating(RiderViews.operating(r))
            .settings(RiderViews.settings(r))
            .restriction(
                new StaffRiderDetailRestriction()
                    .state(AccountRestriction.fromValue(r.restriction().name()))
                    .reason(r.restrictionReason())
                    .until(RiderViews.time(r.restrictionUntil())))
            .lastHeartbeatAt(RiderViews.time(r.lastHeartbeatAt()))
            .joinedAt(RiderViews.time(r.joinedAt()))
            .withdrawnAt(RiderViews.time(r.withdrawnAt())));
  }

  @Override
  public ResponseEntity<RiderPiiReveal> revealRiderPiiForStaff(
      String riderId, String idempotencyKey, String xSourceSystem, RiderPiiRevealRequest body) {
    Set<StaffRiderService.PiiField> fields = EnumSet.noneOf(StaffRiderService.PiiField.class);
    if (body.getFields() != null) {
      body.getFields().forEach(f -> fields.add(StaffRiderService.PiiField.valueOf(f.getValue())));
    }
    StaffRiderService.Reveal v =
        staff.reveal(riderId, idempotencyKey, xSourceSystem, body.getReason(), fields);
    return ResponseEntity.ok(
        new RiderPiiReveal()
            .riderId(v.riderId())
            .piiAccessLogId(v.piiAccessLogId())
            .recordedAt(RiderViews.time(v.recordedAt()))
            .fields(v.fields().stream().map(f -> RiderPiiField.fromValue(f.name())).toList())
            .name(v.name())
            .phone(v.phone()));
  }

  private static StaffRiderSummary summary(Rider r) {
    return new StaffRiderSummary()
        .riderId(r.riderId())
        .riderNumber(r.riderNumber())
        .version(r.version())
        .nameMasked(r.name() == null ? null : RiderViews.maskName(r.name()))
        .phoneMasked(r.phone() == null ? "" : RiderViews.maskPhone(r.phone()))
        .vehicleType(VehicleType.fromValue(r.vehicleType()))
        .operatingStatus(OperatingStatus.fromValue(r.operatingStatus().name()))
        .newDispatchEnabled(r.newDispatchEnabled())
        .operatingMfcId(r.operatingMfcId())
        .restriction(AccountRestriction.fromValue(r.restriction().name()))
        .restrictionUntil(RiderViews.time(r.restrictionUntil()))
        .stateChangedAt(RiderViews.time(r.stateChangedAt()))
        .withdrawnAt(RiderViews.time(r.withdrawnAt()))
        .joinedAt(RiderViews.time(r.joinedAt()))
        // 가입 승인(2026-11-17 — 정책 전까지 자동 승인)
        .signupStatus(com.pmart.rider.api.generated.model.SignupStatus.APPROVED);
  }

  /** 대시보드 라이더 수(2026-11-17). */
  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.GetRiderStatsForStaff200Response>
      getRiderStatsForStaff(@Nullable String mfcId) {
    var s = staff.stats(blankToNull(mfcId));
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.GetRiderStatsForStaff200Response()
            .total(s.total())
            .onDuty(s.onDuty())
            .onDutyAcceptingNew(s.onDutyAcceptingNew())
            .byRestriction(
                new com.pmart.rider.api.generated.model
                        .GetRiderStatsForStaff200ResponseByRestriction()
                    .NORMAL(s.normal())
                    .DISPATCH_RESTRICTED(s.dispatchRestricted())
                    .SUSPENDED(s.suspended())
                    .TERMINATED(s.terminated()))
            .joinedToday(s.joinedToday())
            .asOf(RiderViews.time(s.asOf())));
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  /**
   * 라이더 번호 찾기 입력(ADR-0085): {@code R4785}·{@code r4785}·{@code 4785} 를 같은 번호로 본다(공백 무시). 그 밖은 그대로
   * 완전 일치(없으면 빈 결과).
   */
  static @Nullable String riderNumberQuery(@Nullable String s) {
    String v = blankToNull(s);
    if (v == null) {
      return null;
    }
    v = v.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    return v.matches("[0-9]{4,5}") ? "R" + v : v;
  }

  @Override
  public ResponseEntity<RiderRestrictionRecord> changeRiderRestriction(
      String riderId,
      String idempotencyKey,
      String xSourceSystem,
      RiderRestrictionChange body,
      @Nullable Integer xExpectedVersion) {
    RiderStore.RestrictionRow row =
        riders.changeRestriction(
            riderId,
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            new RiderService.RestrictionChange(
                body.getRestriction().getValue(),
                body.getReason(),
                body.getUntil() == null ? null : body.getUntil().toInstant(),
                body.getEvidenceRefs() == null ? List.of() : List.copyOf(body.getEvidenceRefs()),
                body.getApprovalRef()));
    return ResponseEntity.ok(record(row));
  }

  @Override
  public ResponseEntity<ListRiderRestrictions200Response> listRiderRestrictions(String riderId) {
    RiderService.Restrictions r = riders.restrictions(riderId);
    return ResponseEntity.ok(
        new ListRiderRestrictions200Response()
            .current(AccountRestriction.fromValue(r.current().name()))
            .version(r.version())
            .items(r.items().stream().map(this::record).toList()));
  }

  @Override
  public ResponseEntity<com.pmart.rider.api.generated.model.ListRiderWithdrawalsForStaff200Response>
      listRiderWithdrawalsForStaff(
          @Nullable String state,
          @Nullable String riderNumber,
          @Nullable String mfcId,
          Integer page,
          Integer size) {
    List<RiderStore.StaffWithdrawal> rows =
        staff.withdrawals(
            state, riderNumberQuery(riderNumber), blankToNull(mfcId), size + 1, page * size);
    boolean more = rows.size() > size;
    List<RiderStore.StaffWithdrawal> items = more ? rows.subList(0, size) : rows;
    return ResponseEntity.ok(
        new com.pmart.rider.api.generated.model.ListRiderWithdrawalsForStaff200Response()
            .items(
                items.stream()
                    .map(
                        w ->
                            new com.pmart.rider.api.generated.model.StaffRiderWithdrawal()
                                .riderId(w.riderId())
                                .riderNumber(w.riderNumber())
                                .state(
                                    com.pmart.rider.api.generated.model.StaffRiderWithdrawal
                                        .StateEnum.fromValue(w.state()))
                                .blockers(
                                    w.blockers().stream()
                                        .map(
                                            com.pmart.rider.api.generated.model.WithdrawalBlocker
                                                ::fromValue)
                                        .toList())
                                .reason(w.reason())
                                .requestedAt(RiderViews.time(w.requestedAt()))
                                .completedAt(RiderViews.time(w.completedAt())))
                    .toList())
            .page(page)
            .size(items.size())
            .hasNext(more));
  }

  @Override
  public ResponseEntity<ListRiderRestrictionReviews200Response> listRiderRestrictionReviews(
      String state, @Nullable String riderId) {
    return ResponseEntity.ok(
        new ListRiderRestrictionReviews200Response()
            .items(
                complaints.reviews(state, riderId).stream()
                    .map(OperatorController::review)
                    .toList()));
  }

  @Override
  public ResponseEntity<RestrictionReview> decideRiderRestrictionReview(
      String reviewId,
      String idempotencyKey,
      String xSourceSystem,
      RestrictionReviewDecision body) {
    return ResponseEntity.ok(
        review(
            complaints.decide(
                reviewId,
                idempotencyKey,
                xSourceSystem,
                body.getDecision().getValue(),
                body.getNote())));
  }

  private static RestrictionReview review(ComplaintService.Review v) {
    var r = v.review();
    return new RestrictionReview()
        .reviewId(r.reviewId())
        .riderId(r.riderId())
        .complaintType(RestrictionReview.ComplaintTypeEnum.fromValue(r.complaintType()))
        .state(RestrictionReview.StateEnum.fromValue(r.state()))
        .complaints(
            v.complaints().stream()
                .map(
                    c ->
                        new RestrictionReviewComplaintsInner()
                            .complaintId(c.complaintId())
                            .deliveryId(c.deliveryId())
                            .recordedAt(RiderViews.time(c.recordedAt())))
                .toList())
        .openedAt(RiderViews.time(r.openedAt()))
        .decidedBy(r.decidedBy())
        .decidedAt(RiderViews.time(r.decidedAt()))
        .note(r.decisionNote());
  }

  private RiderRestrictionRecord record(RiderStore.RestrictionRow r) {
    return new RiderRestrictionRecord()
        .riderId(r.riderId())
        .restriction(AccountRestriction.fromValue(r.restriction()))
        .previous(AccountRestriction.fromValue(r.previous()))
        .reason(r.reason())
        .until(RiderViews.time(r.until()))
        .evidenceRefs(json.readValue(r.evidenceRefsJson(), new TypeReference<List<String>>() {}))
        .approvalRef(r.approvalRef())
        .changedBy(r.changedBy())
        .changedAt(RiderViews.time(r.changedAt()))
        .version(r.riderVersion());
  }

  /** 쉼표 구분 라이더 ID(0.12.0) — 비면 null, 50개 넘으면 400. */
  private static java.util.@Nullable List<String> ids(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    List<String> out =
        java.util.Arrays.stream(raw.split(","))
            .map(String::strip)
            .filter(x -> !x.isEmpty())
            .distinct()
            .toList();
    if (out.size() > 50) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "riderIds 는 50개까지입니다.");
    }
    return out.isEmpty() ? null : out;
  }
}
