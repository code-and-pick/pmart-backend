package com.pmart.delivery.serviceability.api;

import com.pmart.delivery.serviceability.api.generated.GeocodingApi;
import com.pmart.delivery.serviceability.api.generated.model.AddressLookup;
import com.pmart.delivery.serviceability.api.generated.model.AddressLookupResult;
import com.pmart.delivery.serviceability.application.Geocoder;
import com.pmart.delivery.serviceability.application.ServiceabilityService;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 GeocodingApi — 주소 ↔ 좌표(ADR-0023). 고객·비회원(guest-paths). 변환만 한다. */
@RestController
public class GeocodingController implements GeocodingApi {

  private final ServiceabilityService service;

  public GeocodingController(ServiceabilityService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<AddressLookupResult> searchAddressGeocodes(String query) {
    return ResponseEntity.ok(view(service.geocode(query)));
  }

  @Override
  public ResponseEntity<AddressLookupResult> searchAddressReverseGeocodes(
      BigDecimal lat, BigDecimal lng) {
    return ResponseEntity.ok(view(service.reverseGeocode(lat.doubleValue(), lng.doubleValue())));
  }

  static AddressLookupResult view(List<Geocoder.Place> places) {
    return new AddressLookupResult()
        .results(
            places.stream()
                .map(
                    p ->
                        new AddressLookup()
                            .roadAddress(p.roadAddress())
                            .jibunAddress(p.jibunAddress())
                            .zoneNo(p.zoneNo())
                            .buildingName(p.buildingName())
                            .lat(BigDecimal.valueOf(p.lat()))
                            .lng(BigDecimal.valueOf(p.lng())))
                .toList());
  }
}
