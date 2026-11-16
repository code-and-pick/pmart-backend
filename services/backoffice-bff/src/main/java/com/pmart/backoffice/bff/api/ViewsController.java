package com.pmart.backoffice.bff.api;

import com.pmart.backoffice.bff.api.generated.ViewsApi;
import com.pmart.backoffice.bff.api.generated.model.GetApprovalInboxView200Response;
import com.pmart.backoffice.bff.api.generated.model.GetLiveDeliveryView200Response;
import com.pmart.backoffice.bff.api.generated.model.GetPendingWorkView200Response;
import com.pmart.backoffice.bff.api.generated.model.GetUnifiedLookupView200Response;
import com.pmart.backoffice.bff.application.ApprovalInboxView;
import com.pmart.backoffice.bff.application.LiveDeliveryView;
import com.pmart.backoffice.bff.application.PendingWorkView;
import com.pmart.backoffice.bff.application.UnifiedLookupView;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 ViewsApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에 두고
 * 여기서는 요청·응답 변환만 한다. 구현: 승인함(2026-11-03, BR-40), 처리 대기(2026-11-16, BO-12), 통합 조회(2026-11-16,
 * BO-11), 실시간 배송 현황(2026-11-16, 배송 원문 §10.1).
 */
@RestController
public class ViewsController implements ViewsApi {

  private final ApprovalInboxView approvalInbox;
  private final PendingWorkView pendingWork;
  private final UnifiedLookupView unifiedLookup;
  private final LiveDeliveryView liveDelivery;
  private final ObjectMapper mapper;

  public ViewsController(
      ApprovalInboxView approvalInbox,
      PendingWorkView pendingWork,
      UnifiedLookupView unifiedLookup,
      LiveDeliveryView liveDelivery,
      ObjectMapper mapper) {
    this.approvalInbox = approvalInbox;
    this.pendingWork = pendingWork;
    this.unifiedLookup = unifiedLookup;
    this.liveDelivery = liveDelivery;
    this.mapper = mapper;
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
