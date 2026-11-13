package com.pmart.delivery.tracking.api;

import com.pmart.delivery.tracking.api.generated.model.GpsQuality;
import com.pmart.delivery.tracking.api.generated.model.RiderLocation;
import com.pmart.delivery.tracking.infrastructure.LocationStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class TrackingViews {

  private TrackingViews() {}

  static RiderLocation location(LocationStore.Latest l, long staleSeconds) {
    return new RiderLocation()
        .riderId(l.riderId())
        .lat(BigDecimal.valueOf(l.lat()))
        .lng(BigDecimal.valueOf(l.lng()))
        .accuracyM(l.accuracyM() == null ? null : BigDecimal.valueOf(l.accuracyM()))
        .recordedAt(OffsetDateTime.ofInstant(l.recordedAt(), ZoneOffset.UTC))
        .receivedAt(OffsetDateTime.ofInstant(l.receivedAt(), ZoneOffset.UTC))
        .quality(GpsQuality.fromValue(l.quality()))
        .staleSinceSeconds((int) Math.min(Integer.MAX_VALUE, staleSeconds));
  }
}
