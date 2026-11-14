package com.pmart.mfc.capacity.application;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** 다음 작업 받기 응답의 상세(0.9.1) — 작업 상세(fulfillment)·이동 지시(inbound) 응답을 화면용으로 옮겨 적는다. 순수 변환. */
public final class NextTaskDetails {

  private NextTaskDetails() {}

  public record Line(
      String sku,
      @Nullable String productName,
      @Nullable String lotId,
      @Nullable String expiresOn,
      int qty,
      @Nullable String fromLocation,
      @Nullable String toLocation,
      @Nullable String notice) {}

  public record Detail(
      boolean available,
      @Nullable String promisedReadyAt,
      boolean urgent,
      List<String> packingLocations,
      List<Line> lines) {

    public static Detail unavailable() {
      return new Detail(false, null, false, List.of(), List.of());
    }
  }

  /** fulfillment GET /tasks/{id} 응답 → 상세. PICK·RETURN 은 LOT 조각마다 한 줄, PACK 은 주문 줄마다. */
  public static Detail fromTask(JsonNode t) {
    List<Line> out = new ArrayList<>();
    String type = t.path("taskType").asString("");
    for (JsonNode l : t.path("lines")) {
      String sku = l.path("sku").asString("");
      String name = text(l, "productName");
      JsonNode picks = l.path("picks");
      if (!"PACK".equals(type) && picks.isArray() && !picks.isEmpty()) {
        for (JsonNode p : picks) {
          int qty = p.path("remainingQty").asInt(0);
          if (qty <= 0) {
            continue;
          }
          out.add(
              new Line(
                  sku,
                  name,
                  text(p, "lotId"),
                  text(p, "expiresOn"),
                  qty,
                  fromLocation(p.path("locations")),
                  p.path("suggestedToLocations").isArray()
                          && !p.path("suggestedToLocations").isEmpty()
                      ? p.path("suggestedToLocations").path(0).asString(null)
                      : null,
                  text(p, "notice")));
        }
        continue;
      }
      int qty = l.path("remainingQty").asInt(0);
      if (qty > 0) {
        out.add(
            new Line(
                sku,
                name,
                text(l, "lotId"),
                text(l, "expiresOn"),
                qty,
                text(l, "locationId"),
                null,
                null));
      }
    }
    List<String> packing = new ArrayList<>();
    t.path("packingLocations").forEach(x -> packing.add(x.asString("")));
    return new Detail(true, text(t, "promisedReadyAt"), false, packing, out);
  }

  /** inbound GET /move-orders/{id} 응답 → 상세(한 줄, 남은 양 = 지시 − 집은 양). */
  public static Detail fromMove(JsonNode m) {
    int qty = Math.max(0, m.path("qty").asInt(0) - m.path("pickedQty").asInt(0));
    return new Detail(
        true,
        null,
        m.path("urgent").asBoolean(false),
        List.of(),
        List.of(
            new Line(
                m.path("sku").asString(""),
                null,
                text(m, "lotId"),
                null,
                qty,
                text(m, "fromLocation"),
                text(m, "toLocation"),
                null)));
  }

  /** inbound GET /inbound-tasks/{id} 응답 → 상세(0.14.0, 한 줄 — 출발 칸 = 수령 위치, 남은 양 = 계획 − 완료). */
  public static Detail fromInboundTask(JsonNode t) {
    int qty = Math.max(0, t.path("plannedQty").asInt(0) - t.path("doneQty").asInt(0));
    return new Detail(
        true,
        null,
        false,
        List.of(),
        List.of(
            new Line(
                t.path("sku").asString(""),
                null,
                text(t, "lotId"),
                null,
                qty,
                text(t, "locationCode"),
                null,
                null)));
  }

  /** 이 주문 몫이 배정된 칸 우선, 없으면 첫 칸. */
  private static @Nullable String fromLocation(JsonNode locations) {
    String first = null;
    for (JsonNode x : locations) {
      String code = x.path("locationCode").asString(null);
      if (first == null) {
        first = code;
      }
      if (x.path("allocatedQty").asInt(0) > 0) {
        return code;
      }
    }
    return first;
  }

  private static @Nullable String text(JsonNode n, String field) {
    String v = n.path(field).asString(null);
    return v == null || v.isBlank() ? null : v;
  }
}
