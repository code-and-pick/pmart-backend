package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.infrastructure.InventoryClient;
import com.pmart.cdc.outbound.infrastructure.LpnStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferLineRow;
import com.pmart.cdc.outbound.infrastructure.ShortageStore;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 운영 확인 기준(0.28.0 — WMS CDC-F-034 표, DC 백오피스 KP-1, 사용자 "있는 자료만 + 나머지 N/A"). 기한이 그 기간인 TRF 를 평가
 * 대상으로, 원천이 있는 항목만 계산하고 없는 것은 N/A. SKU 를 섞은 낱개 합 비율은 잠정(확정 지표가 아님). 원장·품질·환불을 바꾸지 않는다.
 */
@Service
public class OperatingMetrics {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  public static final String DEFINITION = "CDC-F-034/v1-provisional";

  public record Component(String key, @Nullable Integer count, String status) {}

  public record Item(
      String key,
      String label,
      String status,
      @Nullable Double numerator,
      @Nullable Double denominator,
      @Nullable Double value,
      String unit,
      boolean provisional,
      List<String> reasons,
      List<Component> components,
      List<String> sources) {}

  public record Report(
      LocalDate from,
      LocalDate to,
      @Nullable String mfcId,
      Instant asOf,
      int evaluatedTrfCount,
      List<Item> items) {}

  /** TRF 하나의 평가 재료. */
  record Trf(
      String trfId,
      String state,
      Instant dueAt,
      int approved,
      @Nullable JsonNode transit,
      boolean transitFailed,
      @Nullable String departureResult,
      int shortageRecords) {}

  private final OutboundStore store;
  private final LpnStore lpns;
  private final ShortageStore shortages;
  private final InventoryClient inventory;
  private final Clock clock;

  public OperatingMetrics(
      OutboundStore store,
      LpnStore lpns,
      ShortageStore shortages,
      InventoryClient inventory,
      Clock clock) {
    this.store = store;
    this.lpns = lpns;
    this.shortages = shortages;
    this.inventory = inventory;
    this.clock = clock;
  }

  public Report report(String cdcId, LocalDate from, LocalDate to, @Nullable String mfcId) {
    Support.requireCenter(cdcId);
    if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > 31) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "기간은 from ≤ to, 31일까지입니다.");
    }
    List<Trf> trfs = new ArrayList<>();
    for (OutboundStore.FeedRow f :
        store.transfersDue(
            cdcId,
            from.atStartOfDay(SEOUL).toInstant(),
            to.plusDays(1).atStartOfDay(SEOUL).toInstant(),
            2000)) {
      if (mfcId != null && !mfcId.equals(f.mfcId())) {
        continue;
      }
      int approved = 0;
      for (TransferLineRow l : store.transferLines(f.trfId())) {
        approved += l.approvedQty();
      }
      String dep =
          lpns.departureOfTrf(f.trfId()).map(LpnStore.DepartureTransferRow::result).orElse(null);
      JsonNode transit = null;
      boolean failed = false;
      if (dep != null || "DEPARTED".equals(f.state())) {
        try {
          transit = inventory.transit(f.trfId());
        } catch (PmartException e) {
          failed = true; // 재고 쪽이 안 되면 그 원천 항목만 N/A
        }
      }
      trfs.add(
          new Trf(
              f.trfId(),
              f.state(),
              f.dueAt(),
              approved,
              transit,
              failed,
              dep,
              shortages.ofTrf(f.trfId()).size()));
    }
    boolean invDown = trfs.stream().anyMatch(Trf::transitFailed);
    List<Item> items = new ArrayList<>();
    items.add(approvedFulfillment(trfs, invDown));
    items.add(na("ORIGINAL_DEMAND_FULFILLMENT", "원수요 충족률", "EA_MIXED_SKU"));
    items.add(normalReceipt(trfs, invDown));
    items.add(na("READY_TIME", "사용 준비 시간", "MINUTES"));
    items.add(na("PROMISE_COMPLIANCE", "약속 준수·전량/품질", "COUNT"));
    items.add(exceptions(trfs, invDown));
    return new Report(from, to, mfcId, clock.instant(), trfs.size(), items);
  }

  private Item approvedFulfillment(List<Trf> trfs, boolean invDown) {
    double den = 0;
    double num = 0;
    boolean open = false;
    List<String> src = new ArrayList<>();
    for (Trf t : trfs) {
      den += t.approved();
      src.add(t.trfId());
      if (t.transit() == null) {
        if (!"CANCELLED".equals(t.state())) {
          open = true; // 아직 출발·수령 전
        }
        continue;
      }
      if (!"TRANSIT_RECONCILED".equals(t.transit().path("state").asString())) {
        open = true;
      }
      String last = t.transit().path("lastReceiptAt").asString(null);
      if (last != null && !Instant.parse(last).isAfter(t.dueAt())) {
        for (JsonNode l : t.transit().path("lines")) {
          num += l.path("normalQty").asInt();
        }
      }
    }
    List<String> reasons = new ArrayList<>();
    reasons.add("MIXED_SKU");
    if (open) {
      reasons.add("OPEN_RECEIPTS");
    }
    if (invDown) {
      return naWith(
          "APPROVED_DEMAND_FULFILLMENT",
          "승인 수요 충족률",
          "EA_MIXED_SKU",
          "DEPENDENCY_UNAVAILABLE",
          src);
    }
    if (den == 0) {
      return naWith(
          "APPROVED_DEMAND_FULFILLMENT", "승인 수요 충족률", "EA_MIXED_SKU", "ZERO_DENOMINATOR", src);
    }
    return new Item(
        "APPROVED_DEMAND_FULFILLMENT",
        "승인 수요 충족률",
        "VALUE",
        num,
        den,
        round(num / den),
        "EA_MIXED_SKU",
        true,
        reasons,
        List.of(),
        src);
  }

  private Item normalReceipt(List<Trf> trfs, boolean invDown) {
    double normal = 0;
    double received = 0;
    boolean pending = false;
    List<String> src = new ArrayList<>();
    for (Trf t : trfs) {
      if (t.transit() == null) {
        continue;
      }
      boolean any = false;
      for (JsonNode l : t.transit().path("lines")) {
        normal += l.path("normalQty").asInt();
        received += l.path("r").asInt();
        any |= l.path("r").asInt() > 0;
        pending |= l.path("pendingQty").asInt() > 0;
      }
      if (any) {
        src.add(t.trfId());
      }
    }
    if (invDown) {
      return naWith("NORMAL_RECEIPT_RATE", "정상 수령률", "EA_MIXED_SKU", "DEPENDENCY_UNAVAILABLE", src);
    }
    if (received == 0) {
      return naWith("NORMAL_RECEIPT_RATE", "정상 수령률", "EA_MIXED_SKU", "ZERO_DENOMINATOR", src);
    }
    List<String> reasons = new ArrayList<>(List.of("MIXED_SKU"));
    if (pending) {
      reasons.add("PENDING_QUALITY");
    }
    return new Item(
        "NORMAL_RECEIPT_RATE",
        "정상 수령률",
        "VALUE",
        normal,
        received,
        round(normal / received),
        "EA_MIXED_SKU",
        true,
        reasons,
        List.of(),
        src);
  }

  private Item exceptions(List<Trf> trfs, boolean invDown) {
    int mismatch = 0;
    int shortage = 0;
    int unreconciled = 0;
    Map<String, Boolean> src = new LinkedHashMap<>();
    for (Trf t : trfs) {
      if (t.departureResult() != null && !"MATCHED".equals(t.departureResult())) {
        mismatch++;
        src.put(t.trfId(), true);
      }
      if (t.shortageRecords() > 0) {
        shortage += t.shortageRecords();
        src.put(t.trfId(), true);
      }
      if (t.transit() != null) {
        int open = 0;
        for (JsonNode l : t.transit().path("lines")) {
          open += l.path("t").asInt();
        }
        if (open > 0) {
          unreconciled++;
          src.put(t.trfId(), true);
        }
      }
    }
    List<Component> cs =
        List.of(
            new Component("DEPARTURE_MISMATCH", mismatch, "VALUE"),
            new Component("PICK_SHORTAGE", shortage, "VALUE"),
            invDown
                ? new Component("TRANSIT_UNRECONCILED", null, "NA")
                : new Component("TRANSIT_UNRECONCILED", unreconciled, "VALUE"),
            new Component("DEADLINE_LOSS", null, "NA"),
            new Component("URGENT_SHARE", null, "NA"));
    int total = mismatch + shortage + (invDown ? 0 : unreconciled);
    List<String> reasons = new ArrayList<>();
    reasons.add("SOURCE_UNAVAILABLE"); // 기한 손실·긴급 비중
    if (invDown) {
      reasons.add("DEPENDENCY_UNAVAILABLE");
    }
    return new Item(
        "EXCEPTION_VOLUME",
        "예외 운영량",
        "VALUE",
        null,
        null,
        (double) total,
        "COUNT",
        true,
        reasons,
        cs,
        new ArrayList<>(src.keySet()));
  }

  private static Item na(String key, String label, String unit) {
    return naWith(key, label, unit, "SOURCE_UNAVAILABLE", List.of());
  }

  private static Item naWith(
      String key, String label, String unit, String reason, List<String> src) {
    return new Item(
        key, label, "NA", null, null, null, unit, false, List.of(reason), List.of(), src);
  }

  private static double round(double v) {
    return Math.round(v * 10000.0) / 10000.0;
  }
}
