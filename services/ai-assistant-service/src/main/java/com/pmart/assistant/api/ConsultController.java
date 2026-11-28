package com.pmart.assistant.api;

import com.pmart.assistant.api.generated.ConsultApi;
import com.pmart.assistant.api.generated.model.ConsultGuide;
import com.pmart.assistant.application.ConsultGuideService;
import com.pmart.common.security.PmartPrincipal;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 ConsultApi 구현(ADR-0093 상담 도우미). 변환만. */
@RestController
public class ConsultController implements ConsultApi {

  private final ConsultGuideService service;

  public ConsultController(ConsultGuideService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<ConsultGuide> getConsultGuide(
      String xSourceSystem,
      String category,
      @Nullable Boolean memberFound,
      @Nullable String orderStatus) {
    var g =
        service.guide(
            PmartPrincipal.current().orElse(null),
            xSourceSystem,
            category,
            memberFound,
            orderStatus);
    return ResponseEntity.ok(
        new ConsultGuide()
            .category(g.category())
            .memberFound(g.memberFound())
            .orderStatus(g.orderStatus())
            .identity(g.identity() == null ? null : AssistantController.citation(g.identity()))
            .policies(g.policies().stream().map(AssistantController::citation).toList())
            .indexSnapshotId(g.snapshotId()));
  }
}
