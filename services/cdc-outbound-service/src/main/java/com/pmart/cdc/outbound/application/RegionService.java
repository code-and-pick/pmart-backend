package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.RegionRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지역 ↔ MFC 대응표(0.9.0, chilgok-dc.md CDC-6 — 대응표는 cdc-outbound). 지역 = PDA 지역 바코드 단위, MFC 하나 = 지역 하나.
 * Wave 릴리스가 지역 묶음(REGION_BATCH)을 만들 때 읽는다. 칠곡 처음 5곳·4지역은 Flyway V7 이 넣는다.
 */
@Service
public class RegionService {

  static final String OP_SET = "cdcOutbound:regions";
  public static final int MAX_ITEMS = 200;

  /** stagingBays(0.14.0) = MFC → 출고대기 자리. */
  public record Region(
      String regionCode, String regionName, List<String> mfcIds, Map<String, String> stagingBays) {}

  public record Regions(List<Region> items) {}

  private final OutboundStore store;
  private final RequestIdempotency idempotency;
  private final Support support;

  public RegionService(OutboundStore store, RequestIdempotency idempotency, Support support) {
    this.store = store;
    this.idempotency = idempotency;
    this.support = support;
  }

  @Transactional(readOnly = true)
  public Regions list(String cdcId) {
    Support.requireCenter(cdcId);
    return group(store.regions(cdcId));
  }

  public Outcome<Regions> set(
      String requestId, String sourceSystem, String cdcId, List<RegionRow> items) {
    String by = Support.requireCenter(cdcId);
    if (items.isEmpty() || items.size() > MAX_ITEMS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "items 는 1~" + MAX_ITEMS + "행이어야 합니다.");
    }
    Set<String> seen = new HashSet<>();
    for (RegionRow r : items) {
      if (!seen.add(r.mfcId())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "같은 MFC 가 두 번 있습니다: " + r.mfcId(),
            List.of(new PmartException.Detail("items", "DUPLICATE_MFC")));
      }
    }
    return idempotency.execute(
        Support.key(sourceSystem, requestId, OP_SET, by),
        Map.of("cdcId", cdcId, "items", items),
        Regions.class,
        () -> {
          Instant at = support.now();
          items.forEach(r -> store.upsertRegion(cdcId, r, by, at));
          return group(store.regions(cdcId));
        });
  }

  private static Regions group(List<RegionRow> rows) {
    Map<String, String> names = new LinkedHashMap<>();
    Map<String, List<String>> mfcs = new LinkedHashMap<>();
    Map<String, Map<String, String>> bays = new LinkedHashMap<>();
    for (RegionRow r : rows) {
      names.putIfAbsent(r.regionCode(), r.regionName());
      mfcs.computeIfAbsent(r.regionCode(), k -> new ArrayList<>()).add(r.mfcId());
      Map<String, String> b = bays.computeIfAbsent(r.regionCode(), k -> new LinkedHashMap<>());
      if (r.stagingBay() != null) {
        b.put(r.mfcId(), r.stagingBay());
      }
    }
    List<Region> out = new ArrayList<>();
    names.forEach(
        (code, name) ->
            out.add(
                new Region(code, name, List.copyOf(mfcs.get(code)), Map.copyOf(bays.get(code)))));
    return new Regions(out);
  }
}
