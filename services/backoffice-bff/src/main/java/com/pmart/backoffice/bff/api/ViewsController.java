package com.pmart.backoffice.bff.api;

import com.pmart.backoffice.bff.api.generated.ViewsApi;
import com.pmart.backoffice.bff.api.generated.model.GetApprovalInboxView200Response;
import com.pmart.backoffice.bff.api.generated.model.GetCaseInboxView200Response;
import com.pmart.backoffice.bff.api.generated.model.GetLiveDeliveryView200Response;
import com.pmart.backoffice.bff.api.generated.model.GetMetricsView200Response;
import com.pmart.backoffice.bff.api.generated.model.GetPackingHandoverView200Response;
import com.pmart.backoffice.bff.api.generated.model.GetPendingWorkView200Response;
import com.pmart.backoffice.bff.api.generated.model.GetUnifiedLookupView200Response;
import com.pmart.backoffice.bff.application.ApprovalInboxView;
import com.pmart.backoffice.bff.application.CaseInboxView;
import com.pmart.backoffice.bff.application.LiveDeliveryView;
import com.pmart.backoffice.bff.application.MetricsView;
import com.pmart.backoffice.bff.application.PackingHandoverView;
import com.pmart.backoffice.bff.application.PendingWorkView;
import com.pmart.backoffice.bff.application.UnifiedLookupView;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 ViewsApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에 두고
 * 여기서는 요청·응답 변환만 한다. 구현: 승인함(2026-11-03, BR-40), 처리 대기(2026-11-16, BO-12), 통합 조회(2026-11-16,
 * BO-11), 실시간 배송 현황(2026-11-16, 배송 원문 §10.1), 사건함(2026-11-16, 0.5.0), 지표 CASE_SLA(2026-11-18,
 * 0.13.0).
 */
@RestController
public class ViewsController implements ViewsApi {

  private final ApprovalInboxView approvalInbox;
  private final PendingWorkView pendingWork;
  private final UnifiedLookupView unifiedLookup;
  private final LiveDeliveryView liveDelivery;
  private final CaseInboxView caseInbox;
  private final PackingHandoverView packingHandover;
  private final MetricsView metrics;
  private final ObjectMapper mapper;

  public ViewsController(
      ApprovalInboxView approvalInbox,
      PendingWorkView pendingWork,
      UnifiedLookupView unifiedLookup,
      LiveDeliveryView liveDelivery,
      CaseInboxView caseInbox,
      PackingHandoverView packingHandover,
      MetricsView metrics,
      ObjectMapper mapper) {
    this.approvalInbox = approvalInbox;
    this.pendingWork = pendingWork;
    this.unifiedLookup = unifiedLookup;
    this.liveDelivery = liveDelivery;
    this.caseInbox = caseInbox;
    this.packingHandover = packingHandover;
    this.metrics = metrics;
    this.mapper = mapper;
  }

  @Override
  public ResponseEntity<GetCaseInboxView200Response> getCaseInboxView(
      @Nullable String state,
      @Nullable String kind,
      @Nullable String assignee,
      @Nullable String assigneeGroup,
      @Nullable Boolean unassigned,
      @Nullable Boolean overdue,
      @Nullable String centerId,
      @Nullable String priority,
      Integer size,
      @Nullable String cursor) {
    return ResponseEntity.ok(
        mapper.convertValue(
            caseInbox.view(
                new CaseInboxView.Filter(
                    state,
                    kind,
                    assignee,
                    assigneeGroup,
                    unassigned,
                    overdue,
                    centerId,
                    priority,
                    size,
                    cursor)),
            GetCaseInboxView200Response.class));
  }

  @Override
  public ResponseEntity<GetUnifiedLookupView200Response> getUnifiedLookupView(
      String q, @Nullable String kind) {
    return ResponseEntity.ok(
        mapper.convertValue(unifiedLookup.view(q, kind), GetUnifiedLookupView200Response.class));
  }

  @Override
  public ResponseEntity<GetLiveDeliveryView200Response> getLiveDeliveryView(
      @Nullable String mfcId, @Nullable String state, Integer size, @Nullable String cursor) {
    return ResponseEntity.ok(
        mapper.convertValue(
            liveDelivery.view(mfcId, state, size == null ? 50 : size, cursor),
            GetLiveDeliveryView200Response.class));
  }

  @Override
  public ResponseEntity<GetPackingHandoverView200Response> getPackingHandoverView(
      @Nullable String mfcId, @Nullable List<String> state, Integer size, @Nullable String cursor) {
    return ResponseEntity.ok(
        mapper.convertValue(
            packingHandover.view(mfcId, state, size == null ? 30 : size, cursor),
            GetPackingHandoverView200Response.class));
  }

  @Override
  public ResponseEntity<GetMetricsView200Response> getMetricsView(
      String metric,
      LocalDate from,
      LocalDate to,
      @Nullable String centerId,
      @Nullable String granularity) {
    return ResponseEntity.ok(
        mapper.convertValue(
            metrics.view(metric, from, to, centerId, granularity),
            GetMetricsView200Response.class));
  }

  @Override
  public ResponseEntity<GetPendingWorkView200Response> getPendingWorkView() {
    return ResponseEntity.ok(
        mapper.convertValue(pendingWork.view(), GetPendingWorkView200Response.class));
  }

  @Override
  public ResponseEntity<GetApprovalInboxView200Response> getApprovalInboxView(
      @Nullable String approvalType,
      @Nullable String reviewState,
      @Nullable String centerId,
      Integer size,
      @Nullable String cursor) {
    return ResponseEntity.ok(
        mapper.convertValue(
            approvalInbox.view(approvalType, reviewState, centerId, size, cursor),
            GetApprovalInboxView200Response.class));
  }
}
