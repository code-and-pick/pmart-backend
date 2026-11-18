package com.pmart.delivery.dispatch.application;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.domain.Geo;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import com.pmart.delivery.dispatch.infrastructure.Upstreams;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 사고 지점까지 간 거리(2026-11-18 사용자 "사고나면 배달료는 이동시점까지의 거리를 계산해서 지급" → "원래 금액을 거리 비율로"). execution 이
 * 사고(RIDER_INCIDENT) 실패를 확정할 때 부른다 — 수행료 = 수락 금액 × (이 거리 ÷ MFC → 고객 거리).
 *
 * <p>사고 지점 = 실패를 등록한 시각에 가장 가까운 라이더 위치 점(tracking location-at, 앞뒤 3분). 없으면(라이더가 등록하지 못해 지원센터가 직접
 * 확정하는 경우 등) 마지막으로 받은 위치 — 픽업 뒤에 받은 점일 때만. 거리 = MFC → 사고 지점 도로 거리(배차가 쓰는 같은 거리 행렬). 잴 수 없으면 404 —
 * execution 은 산정 불가(지원센터 조정)로 둔다.
 */
@Service
public class IncidentDistance {

  /** 잰 거리. source = LOCATION_AT(등록 시각의 점) / LAST_LOCATION(마지막 위치). */
  public record Measured(String deliveryId, int distanceM, String source, Instant recordedAt) {}

  private final DispatchStore store;
  private final Upstreams upstreams;

  public IncidentDistance(DispatchStore store, Upstreams upstreams) {
    this.store = store;
    this.upstreams = upstreams;
  }

  public Measured measure(String deliveryId, Instant at) {
    DeliveryRow d =
        store
            .delivery(deliveryId)
            .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송을 찾지 못했습니다."));
    if (d.riderId() == null || d.pickedUpAt() == null) {
      throw PmartException.notFound("INCIDENT_POINT_NOT_FOUND", "픽업한 라이더가 없는 배송입니다.");
    }
    if (d.mfcLat() == null || d.mfcLng() == null) {
      throw PmartException.notFound("NO_MFC_COORDINATES", "센터 좌표가 없습니다.");
    }
    Geo point;
    String source;
    Instant recordedAt;
    Optional<Upstreams.PointAt> near = upstreams.locationAt(d.riderId(), at);
    if (near.isPresent()) {
      point = near.get().at();
      source = "LOCATION_AT";
      recordedAt = near.get().recordedAt();
    } else {
      Upstreams.Location last =
          upstreams
              .location(d.riderId())
              .filter(l -> !l.receivedAt().isBefore(d.pickedUpAt()))
              .orElseThrow(
                  () ->
                      PmartException.notFound(
                          "INCIDENT_POINT_NOT_FOUND", "사고 시각 근처·픽업 뒤 라이더 위치가 없습니다."));
      point = last.at();
      source = "LAST_LOCATION";
      recordedAt = last.receivedAt();
    }
    Upstreams.Leg leg =
        upstreams.matrix(List.of(new Geo(d.mfcLat(), d.mfcLng())), List.of(point))[0][0];
    if (!leg.ok()) {
      throw PmartException.notFound("NO_ROUTE", "센터에서 사고 지점까지 길을 찾지 못했습니다.");
    }
    return new Measured(deliveryId, leg.distanceM(), source, recordedAt);
  }
}
