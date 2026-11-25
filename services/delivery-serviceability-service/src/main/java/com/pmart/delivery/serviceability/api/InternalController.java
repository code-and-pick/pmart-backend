package com.pmart.delivery.serviceability.api;

import com.pmart.delivery.serviceability.api.generated.InternalApi;
import com.pmart.delivery.serviceability.api.generated.model.ServiceabilityVersion;
import com.pmart.delivery.serviceability.application.ServiceabilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 현재·지정 매핑 버전 조회(내부 호출). 변환만 한다. */
@RestController
public class InternalController implements InternalApi {

  private final ServiceabilityService service;
  private final VersionMapping mapping;

  public InternalController(ServiceabilityService service, VersionMapping mapping) {
    this.service = service;
    this.mapping = mapping;
  }

  @Override
  public ResponseEntity<ServiceabilityVersion> getCurrentServiceabilityVersion() {
    return ResponseEntity.ok(mapping.view(service.current(), false));
  }

  @Override
  public ResponseEntity<ServiceabilityVersion> getServiceabilityVersion(
      Integer version, Boolean includeGeometry) {
    return ResponseEntity.ok(
        mapping.view(service.version(version), Boolean.TRUE.equals(includeGeometry)));
  }

  /** 배차용 주소 → 좌표(2026-11-13 DD-1). 판정과 같은 정확 일치 변환. */
  @Override
  public ResponseEntity<com.pmart.delivery.serviceability.api.generated.model.AddressLookupResult>
      searchAddressGeocodesInternal(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.delivery.serviceability.api.generated.model.SearchAddressGeocodesInternalRequest
              body) {
    return ResponseEntity.ok(GeocodingController.view(service.geocodeExact(body.getQuery())));
  }

  /** 좌표 → 들어가는 발효 권역(2026-11-25 rider 배달 지역 바꾸기). 좌표는 본문으로만, 로그 없음. */
  @Override
  public ResponseEntity<
          com.pmart.delivery.serviceability.api.generated.model.LookupZonesInternal200Response>
      lookupZonesInternal(
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.delivery.serviceability.api.generated.model.LookupZonesInternalRequest body) {
    var r = service.lookupZones(body.getLat().doubleValue(), body.getLng().doubleValue());
    return ResponseEntity.ok(
        new com.pmart.delivery.serviceability.api.generated.model.LookupZonesInternal200Response()
            .version(r.version())
            .servingMfcId(r.servingMfcId())
            .zones(
                r.zones().stream()
                    .map(
                        z ->
                            new com.pmart.delivery.serviceability.api.generated.model
                                    .LookupZonesInternal200ResponseZonesInner()
                                .zoneId(z.zoneId())
                                .mfcId(z.mfcId()))
                    .toList()));
  }
}
