package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.StaffDropApi;
import com.pmart.delivery.execution.api.generated.model.GetDeliveryDropLocationForStaff200Response;
import com.pmart.delivery.execution.application.DropInfoService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 직원용 전달지(0.24.0, 2026-11-18 — 라이더 백오피스 관제). 개인정보라 no-store, 업무 규칙은 {@link DropInfoService}. */
@RestController
public class StaffDropController implements StaffDropApi {

  private final DropInfoService drops;
  private final HttpServletRequest request;

  public StaffDropController(DropInfoService drops, HttpServletRequest request) {
    this.drops = drops;
    this.request = request;
  }

  @Override
  public ResponseEntity<GetDeliveryDropLocationForStaff200Response> getDeliveryDropLocationForStaff(
      String deliveryId) {
    var d = drops.staffDropLocation(deliveryId, request.getHeader("X-Source-System"));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new GetDeliveryDropLocationForStaff200Response()
                .deliveryId(d.deliveryId())
                .kind(GetDeliveryDropLocationForStaff200Response.KindEnum.fromValue(d.kind()))
                .roadAddress(d.roadAddress())
                .detailAddress(d.detailAddress())
                .postalCode(d.postalCode())
                .lat(d.lat() == null ? null : BigDecimal.valueOf(d.lat()))
                .lng(d.lng() == null ? null : BigDecimal.valueOf(d.lng()))
                .coordinateSource(
                    d.coordinateSource() == null
                        ? null
                        : GetDeliveryDropLocationForStaff200Response.CoordinateSourceEnum.fromValue(
                            d.coordinateSource()))
                .piiAccessLogId(d.piiAccessLogId()));
  }
}
