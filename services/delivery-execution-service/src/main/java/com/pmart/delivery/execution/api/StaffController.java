package com.pmart.delivery.execution.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.api.generated.StaffApi;
import com.pmart.delivery.execution.api.generated.model.ExecutionState;
import com.pmart.delivery.execution.api.generated.model.HelpCategory;
import com.pmart.delivery.execution.api.generated.model.HelpRequest;
import com.pmart.delivery.execution.api.generated.model.HelpResolution;
import com.pmart.delivery.execution.api.generated.model.ListHelpRequestsForStaff200Response;
import com.pmart.delivery.execution.api.generated.model.ListMyDeliveries200ResponsePage;
import com.pmart.delivery.execution.api.generated.model.SearchDeliveriesForStaff200Response;
import com.pmart.delivery.execution.api.generated.model.StaffDeliverySummary;
import com.pmart.delivery.execution.api.generated.model.StaffHelpRequestSummary;
import com.pmart.delivery.execution.application.HelpRequestService;
import com.pmart.delivery.execution.application.StaffQueries;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 StaffApi(0.4.0, 2026-11-15 — 백오피스 고객센터·라이더 운영, BOD-4·5): 직원 배송 찾기, 도움요청 대기 목록, 처리함 닫기. 권한 판정은
 * application.
 */
@RestController
public class StaffController implements StaffApi {

  private static final Set<String> HELP_STATES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED");
  private static final Set<String> TYPES = Set.of("FORWARD", "RETURN", "RESHIPMENT");

  private final StaffQueries queries;
  private final HelpRequestService helps;
  private final ObjectMapper mapper;

  private final com.pmart.delivery.execution.application.EarningAdjustments adjustments;

  public StaffController(
      StaffQueries queries,
      HelpRequestService helps,
      ObjectMapper mapper,
      com.pmart.delivery.execution.application.EarningAdjustments adjustments) {
    this.adjustments = adjustments;
    this.queries = queries;
    this.helps = helps;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<SearchDeliveriesForStaff200Response> searchDeliveriesForStaff(
      @Nullable String orderId,
      @Nullable String riderId,
      @Nullable String mfcId,
      @Nullable ExecutionState state,
      @Nullable String type,
      String sort,
      Integer size,
      @Nullable String cursor) {
    if (type != null && !TYPES.contains(type)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "type 이 올바르지 않습니다.");
    }
    if (!"NEWEST".equals(sort) && !"OLDEST".equals(sort)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "sort 는 NEWEST·OLDEST 입니다.");
    }
    int offset = offset(cursor);
    List<DeliveryRow> rows =
        queries.deliveries(
            new ExecutionStore.DeliveryFilter(
                blankToNull(orderId),
                blankToNull(riderId),
                blankToNull(mfcId),
                state == null ? null : state.getValue(),
                type,
                "OLDEST".equals(sort)),
            size + 1,
            offset);
    boolean more = rows.size() > size;
    List<DeliveryRow> page = more ? rows.subList(0, size) : rows;
    return ResponseEntity.ok(
        new SearchDeliveriesForStaff200Response()
            .items(page.stream().map(StaffController::delivery).toList())
            .page(page(more, offset, size, page.size())));
  }

  @Override
  public ResponseEntity<ListHelpRequestsForStaff200Response> listHelpRequestsForStaff(
      String state,
      @Nullable HelpCategory category,
      @Nullable String mfcId,
      @Nullable String riderId,
      @Nullable OffsetDateTime from,
      @Nullable OffsetDateTime to,
      Integer size,
      @Nullable String cursor) {
    if (!HELP_STATES.contains(state)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "state 는 OPEN·IN_PROGRESS·RESOLVED 입니다.");
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "from 은 to 보다 앞이어야 합니다.");
    }
    int offset = offset(cursor);
    List<ExecutionStore.StaffHelpRow> rows =
        helps.staffList(
            new ExecutionStore.HelpFilter(
                state,
                category == null ? null : category.getValue(),
                blankToNull(mfcId),
                blankToNull(riderId),
                from == null ? null : from.toInstant(),
                to == null ? null : to.toInstant()),
            size + 1,
            offset);
    boolean more = rows.size() > size;
    List<ExecutionStore.StaffHelpRow> page = more ? rows.subList(0, size) : rows;
    return ResponseEntity.ok(
        new ListHelpRequestsForStaff200Response()
            .items(page.stream().map(StaffController::help).toList())
            .page(page(more, offset, size, page.size())));
  }

  @Override
  public ResponseEntity<HelpRequest> resolveHelpRequest(
      String deliveryId,
      String requestId,
      String idempotencyKey,
      String xSourceSystem,
      HelpResolution body) {
    return ResponseEntity.ok(
        helpRequest(
            helps.resolve(deliveryId, requestId, idempotencyKey, xSourceSystem, body.getNote()),
            mapper));
  }

  /** 대화 메시지 가림(DE-2, 관리자). */
  @Override
  public ResponseEntity<com.pmart.delivery.execution.api.generated.model.HelpMessage>
      maskHelpRequestMessage(
          String deliveryId,
          String requestId,
          String messageId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.delivery.execution.api.generated.model.MaskHelpRequestMessageRequest body) {
    HelpRequestService.Thread t = helps.mask(deliveryId, requestId, messageId, body.getReason());
    return ResponseEntity.ok(ExecutionController.message(t.messages().get(0), true, mapper));
  }

  /** 도움요청 단건 응답 — 라이더에게는 처리한 직원·메모를 숨긴다. */
  static HelpRequest helpRequest(HelpRequestService.HelpView v, ObjectMapper mapper) {
    ExecutionStore.HelpRow h = v.help();
    return new HelpRequest()
        .requestId(h.requestId())
        .deliveryId(h.deliveryId())
        .incidentId(h.incidentId())
        .caseId(h.caseId())
        .state(HelpRequest.StateEnum.fromValue(h.state()))
        .category(h.category())
        .stage(ExecutionState.fromValue(h.stage()))
        .detail(h.detail())
        .evidenceRefs(mapper.readValue(h.evidenceRefsJson(), new TypeReference<List<String>>() {}))
        .createdAt(ExecutionViews.time(h.createdAt()))
        .resolvedAt(ExecutionViews.time(h.resolvedAt()))
        .resolvedBy(v.staff() ? h.resolvedBy() : null)
        .resolutionNote(v.staff() ? h.resolutionNote() : null);
  }

  private static StaffHelpRequestSummary help(ExecutionStore.StaffHelpRow r) {
    ExecutionStore.HelpRow h = r.help();
    return new StaffHelpRequestSummary()
        .incidentId(h.incidentId())
        .requestId(h.requestId())
        .deliveryId(h.deliveryId())
        .orderId(r.orderId())
        .mfcId(r.mfcId())
        .riderId(h.riderId())
        .category(h.category())
        .stage(ExecutionState.fromValue(h.stage()))
        .state(StaffHelpRequestSummary.StateEnum.fromValue(h.state()))
        .caseId(h.caseId())
        .photoCount(r.evidenceCount())
        .createdAt(ExecutionViews.time(h.createdAt()))
        .resolvedAt(ExecutionViews.time(h.resolvedAt()));
  }

  private static StaffDeliverySummary delivery(DeliveryRow d) {
    return new StaffDeliverySummary()
        .deliveryId(d.deliveryId())
        .version(d.version())
        .type(StaffDeliverySummary.TypeEnum.fromValue(d.type()))
        .orderId(d.orderId())
        .mfcId(d.mfcId())
        .riderId(d.riderId())
        .state(ExecutionState.fromValue(d.state()))
        .assignedAt(ExecutionViews.time(d.assignedAt()))
        .pickedUpAt(ExecutionViews.time(d.pickedUpAt()))
        .completedAt(ExecutionViews.time(d.completedAt()))
        .createdAt(ExecutionViews.time(d.createdAt()));
  }

  private static ListMyDeliveries200ResponsePage page(
      boolean more, int offset, int size, int count) {
    return new ListMyDeliveries200ResponsePage()
        .nextCursor(more ? String.valueOf(offset + size) : null)
        .size(count);
  }

  /** cursor = 건너뛸 수(내 배송 목록과 같은 방식). */
  private static int offset(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      int n = Integer.parseInt(cursor);
      if (n < 0 || n > 100_000) {
        throw new NumberFormatException();
      }
      return n;
    } catch (NumberFormatException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
    }
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  // ---- 산정 불가 수행료 조정(2026-11-17)

  @Override
  public ResponseEntity<
          com.pmart.delivery.execution.api.generated.model.ListEarningAdjustmentQueue200Response>
      listEarningAdjustmentQueue(@Nullable String riderId, Integer size, @Nullable String cursor) {
    int offset = offset(cursor);
    var rows = adjustments.queue(blankToNull(riderId), size + 1, offset);
    boolean more = rows.size() > size;
    var page = more ? rows.subList(0, size) : rows;
    return ResponseEntity.ok(
        new com.pmart.delivery.execution.api.generated.model.ListEarningAdjustmentQueue200Response()
            .items(page.stream().map(ExecutionViews::earning).toList())
            .page(page(more, offset, size, page.size())));
  }

  @Override
  public ResponseEntity<com.pmart.delivery.execution.api.generated.model.EarningAdjustmentResult>
      adjustRiderEarning(
          String earningId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.delivery.execution.api.generated.model.AdjustRiderEarningRequest body) {
    var a =
        adjustments.adjust(
            earningId, idempotencyKey, xSourceSystem, body.getFault().getValue(), body.getReason());
    return ResponseEntity.ok(
        new com.pmart.delivery.execution.api.generated.model.EarningAdjustmentResult()
            .adjustmentId(a.adjustmentId())
            .decision(
                com.pmart.delivery.execution.api.generated.model.EarningAdjustmentResult
                    .DecisionEnum.fromValue(a.decision()))
            .fault(
                com.pmart.delivery.execution.api.generated.model.EarningFault.fromValue(a.fault()))
            .earning(ExecutionViews.earning(a.earning())));
  }
}
