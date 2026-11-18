package com.pmart.delivery.dispatch.application;

import com.pmart.delivery.dispatch.domain.Geo;
import com.pmart.delivery.dispatch.domain.SearchRules;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore;
import com.pmart.delivery.dispatch.infrastructure.Upstreams;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 일반배차모드 새 배달 알림(2026-11-18 — 사용자 "일반배차모드 라이더에게도 … 보냄 — 묶어서"). 일반 모드는 제안이 없어(원문 §2.4 배달리스트에서 고름)
 * 앱을 닫아 두면 새 배달을 모른다. {@code list-notice-interval}(15초)마다: 일반 모드·신규배차 켬·위치가 살아 있는 라이더에게 그 라이더의
 * 배달리스트에 보일 배송(반경 단계·방금 배차취소한 배송 제외·한집은 단독 규칙) 중 **지난 알림 뒤 새로 올라온 것**이 있으면 {@code
 * DeliveryListUpdated}(notification 이 푸시). 라이더마다 {@code list-notice-min-gap}(1분)에 한 번까지.
 */
@Service
public class ListNotifier {

  private static final Logger log = LoggerFactory.getLogger(ListNotifier.class);
  private static final Set<String> USABLE_QUALITY = Set.of("GOOD", "LOW_ACCURACY");

  private final DispatchStore store;
  private final Upstreams upstreams;
  private final DispatchEvents events;
  private final DispatchProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final java.time.Duration minGap;

  public ListNotifier(
      DispatchStore store,
      Upstreams upstreams,
      DispatchEvents events,
      DispatchProperties props,
      TransactionTemplate tx,
      Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${pmart.dispatch.list-notice-min-gap:PT1M}")
          java.time.Duration minGap) {
    this.store = store;
    this.upstreams = upstreams;
    this.events = events;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
    this.minGap = minGap;
  }

  @Scheduled(
      initialDelayString = "${pmart.dispatch.list-notice-interval:PT15S}",
      fixedDelayString = "${pmart.dispatch.list-notice-interval:PT15S}")
  public void run() {
    Instant now = clock.instant();
    Map<String, List<DispatchStore.ListedDelivery>> byMfc = new LinkedHashMap<>();
    for (DispatchStore.ListedDelivery x : store.dispatchingWithStage()) {
      byMfc.computeIfAbsent(x.delivery().mfcId(), k -> new ArrayList<>()).add(x);
    }
    for (var e : byMfc.entrySet()) {
      try {
        notifyMfc(e.getKey(), e.getValue(), now);
      } catch (RuntimeException ex) {
        log.warn("MFC {} 새 배달 알림 판단 실패 — 다음 회차에: {}", e.getKey(), ex.toString());
      }
    }
  }

  private void notifyMfc(String mfcId, List<DispatchStore.ListedDelivery> listed, Instant now) {
    List<Upstreams.Rider> normal =
        upstreams.eligibleRiders(mfcId, props.maxRadiusKm()).stream()
            .filter(r -> r.acceptsNewDispatch() && "NORMAL".equals(r.dispatchMode()))
            .toList();
    if (normal.isEmpty()) {
      return;
    }
    List<String> ids = normal.stream().map(Upstreams.Rider::riderId).toList();
    Set<String> restricted = new java.util.HashSet<>(store.restrictedAmong(ids, now));
    Set<String> holdsSingle = store.singleHolders(ids);
    Map<String, Integer> active = store.activeCounts(ids);
    Map<String, Instant> last = store.lastListNotices(ids);
    Map<String, Geo> at = new HashMap<>();
    for (Upstreams.Location l : upstreams.locations(ids)) {
      if (!l.receivedAt().plus(props.locationMaxAge()).isBefore(now)
          && USABLE_QUALITY.contains(l.quality())) {
        at.put(l.riderId(), l.at());
      }
    }
    for (Upstreams.Rider r : normal) {
      String id = r.riderId();
      Geo here = at.get(id);
      if (here == null || restricted.contains(id) || holdsSingle.contains(id)) {
        continue;
      }
      int limit = r.oneAtATime() ? 1 : props.maxActiveDeliveries();
      int mine = active.getOrDefault(id, 0);
      if (mine >= limit) {
        continue;
      }
      Instant since = last.get(id);
      if (since != null && since.plus(minGap).isAfter(now)) {
        continue;
      }
      int fresh = 0;
      for (DispatchStore.ListedDelivery x : listed) {
        var d = x.delivery();
        if (id.equals(x.excludedRiderId()) || (mine > 0 && d.customerSingle())) {
          continue;
        }
        if (since != null && !x.waitingSince().isAfter(since)) {
          continue; // 지난 알림 때 이미 있던 배송
        }
        double km = here.metersTo(new Geo(d.mfcLat(), d.mfcLng())) / 1000.0;
        if (km <= SearchRules.radiusKm(props.radiiKm(), x.stage())) {
          fresh++;
        }
      }
      if (fresh == 0) {
        continue;
      }
      int count = fresh;
      tx.executeWithoutResult(
          s -> events.deliveryListUpdated(id, mfcId, count, store.markListNotice(id, now), now));
      log.info("라이더 {} 일반 모드 새 배달 {}건 알림", id, count);
    }
  }
}
