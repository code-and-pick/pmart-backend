package com.pmart.cdc.inbound.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * cdc-inventory·cdc-location 호출(0.2.0). 내부 경로라 토큰 없이 X-Source-System 만(ADR-0026). 4xx 는 그 코드로
 * PmartException, 미전달·5xx·시간 초과는 {@link Downstream} 이 DEPENDENCY_UNAVAILABLE·UNKNOWN_RESULT 로 —
 * {@link #unknown} 이면 같은 키로 다시.
 */
@Component
public class Clients {

  public static final String SOURCE = "cdc-inbound-service";
  private static final Duration SKU_TTL = Duration.ofMinutes(10);

  /** 칸 하나의 재고 줄. */
  public record Place(
      String sku,
      String lot,
      @Nullable String expiresAt,
      String locationCode,
      String qualityState,
      int onHand,
      int reserved) {}

  public record Loc(
      String locationCode, String locationType, String state, String temperatureZone) {}

  public record PickFace(
      String centerId,
      String locationCode,
      String temperatureZone,
      @Nullable Integer maxQty,
      int minQty,
      int targetQty) {}

  public record SkuInfo(@Nullable String storageType, @Nullable Integer unitsPerBox) {}

  private record Cached(SkuInfo info, Instant at) {}

  private final RestClient inventory;
  private final RestClient location;
  private final ObjectMapper mapper;
  private final Map<String, Cached> skuCache = new ConcurrentHashMap<>();

  public Clients(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.inbound.cdc-inventory-base-url:http://cdc-inventory-service:8601}")
          String inventoryUrl,
      @Value("${pmart.inbound.cdc-location-base-url:http://cdc-location-service:8602}")
          String locationUrl) {
    this.inventory = builder.clone().baseUrl(inventoryUrl).build();
    this.location = builder.clone().baseUrl(locationUrl).build();
    this.mapper = mapper;
  }

  // ---------- cdc-inventory ----------

  /** 센터 재고의 칸별 줄(스냅샷 — 현물 행이 있는 SKU 전부, sku 를 주면 그 SKU 만). */
  public List<Place> places(String cdcId, @Nullable String sku) {
    JsonNode n =
        call(
            "cdc-inventory-service",
            () ->
                inventory
                    .get()
                    .uri(
                        sku == null
                            ? "/api/v1/internal/snapshots?cdcId={c}"
                            : "/api/v1/internal/snapshots?cdcId={c}&sku={s}",
                        sku == null ? new Object[] {cdcId} : new Object[] {cdcId, sku})
                    .header("X-Source-System", SOURCE)
                    .retrieve()
                    .body(String.class));
    List<Place> out = new ArrayList<>();
    for (JsonNode it : n.path("items")) {
      String s = it.path("sku").asString();
      for (JsonNode l : it.path("lots")) {
        for (JsonNode p : l.path("locations")) {
          out.add(
              new Place(
                  s,
                  l.path("lot").asString(),
                  l.path("expiresAt").asString(null),
                  p.path("locationCode").asString(),
                  p.path("qualityState").asString(),
                  p.path("onHand").asInt(),
                  p.path("reserved").asInt()));
        }
      }
    }
    return out;
  }

  /** 상품 사본의 보관 온도·박스 입수(10분 담음). */
  public SkuInfo sku(String sku) {
    Cached c = skuCache.get(sku);
    if (c != null && c.at().plus(SKU_TTL).isAfter(Instant.now())) {
      return c.info();
    }
    JsonNode n =
        call(
            "cdc-inventory-service",
            () ->
                inventory
                    .get()
                    .uri("/api/v1/internal/skus/{s}", sku)
                    .header("X-Source-System", SOURCE)
                    .retrieve()
                    .body(String.class));
    JsonNode upb = n.path("logistics").path("unitsPerBox");
    SkuInfo info =
        new SkuInfo(
            n.path("storageType").asString(null),
            upb.isNumber() && upb.asInt() > 0 ? upb.asInt() : null);
    skuCache.put(sku, new Cached(info, Instant.now()));
    return info;
  }

  /** 재고 이동 전기 — commitId. */
  public @Nullable String move(String key, Map<String, Object> body) {
    JsonNode n =
        call(
            "cdc-inventory-service",
            () ->
                inventory
                    .post()
                    .uri("/api/v1/internal/move-postings")
                    .header("Idempotency-Key", key)
                    .header("X-Source-System", SOURCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
    return n.path("resultId").asString(null);
  }

  // ---------- cdc-location ----------

  public Map<String, Loc> lookup(String cdcId, Collection<String> codes) {
    Map<String, Object> body = Map.of("centerId", cdcId, "locationCodes", List.copyOf(codes));
    JsonNode n =
        call(
            "cdc-location-service",
            () ->
                location
                    .post()
                    .uri("/api/v1/internal/location-lookups")
                    .header("Idempotency-Key", "cdc-inbound-lookup-" + UUID.randomUUID())
                    .header("X-Source-System", SOURCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
    Map<String, Loc> out = new LinkedHashMap<>();
    for (JsonNode it : n.path("items")) {
      String c = it.path("locationCode").asString();
      out.put(
          c,
          new Loc(
              c,
              it.path("locationType").asString(""),
              it.path("state").asString(""),
              it.path("temperatureZone").asString("")));
    }
    return out;
  }

  public List<PickFace> pickFaces() {
    JsonNode n =
        call(
            "cdc-location-service",
            () ->
                location
                    .get()
                    .uri("/api/v1/internal/pick-faces")
                    .header("X-Source-System", SOURCE)
                    .retrieve()
                    .body(String.class));
    List<PickFace> out = new ArrayList<>();
    for (JsonNode it : n.path("items")) {
      out.add(
          new PickFace(
              it.path("centerId").asString(),
              it.path("locationCode").asString(),
              it.path("temperatureZone").asString(""),
              it.path("maxQty").isNumber() ? it.path("maxQty").asInt() : null,
              it.path("pickFaceMinQty").asInt(),
              it.path("pickFaceTargetQty").asInt()));
    }
    return out;
  }

  /** 공간 예약 — reservationId. */
  public String reserve(
      String cdcId,
      String taskId,
      String locationCode,
      String sku,
      String lot,
      int qty,
      int occupied) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("locationCode", locationCode);
    line.put("sku", sku);
    line.put("lot", lot);
    line.put("qty", qty);
    line.put("occupiedQty", occupied);
    Map<String, Object> body =
        Map.of("centerId", cdcId, "sourceRef", taskId, "lines", List.of(line));
    JsonNode n =
        call(
            "cdc-location-service",
            () ->
                location
                    .post()
                    .uri("/api/v1/internal/space-reservations")
                    .header("Idempotency-Key", "cdc-inbound-reserve-" + taskId)
                    .header("X-Source-System", SOURCE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
    return n.path("reservationId").asString();
  }

  /** 예약 소비(qty 있음)·해제(qty 0 = 남은 전량). */
  public void releaseSpace(
      String key,
      String reservationId,
      String action,
      String locationCode,
      int qty,
      @Nullable String commitId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("action", action);
    if (commitId != null) {
      body.put("commitId", commitId);
    }
    if (qty > 0) {
      body.put("lines", List.of(Map.of("locationCode", locationCode, "qty", qty)));
    }
    call(
        "cdc-location-service",
        () ->
            location
                .post()
                .uri("/api/v1/internal/space-reservations/{id}/releases", reservationId)
                .header("Idempotency-Key", key)
                .header("X-Source-System", SOURCE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
  }

  private JsonNode call(String target, Supplier<String> s) {
    try {
      String raw = Downstream.call(target, s::get);
      return mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
    } catch (HttpClientErrorException e) {
      JsonNode p;
      try {
        p = mapper.readTree(e.getResponseBodyAsString());
      } catch (RuntimeException ex) {
        p = mapper.createObjectNode();
      }
      throw new PmartException(
          p.path("code").asString("HTTP_" + e.getStatusCode().value()),
          HttpStatus.valueOf(e.getStatusCode().value()),
          p.path("detail").asString(e.getStatusText()),
          false,
          List.of());
    }
  }

  public static boolean unknown(PmartException e) {
    return e.status().is5xxServerError()
        || "UNKNOWN_RESULT".equals(e.code())
        || ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code());
  }

  public static String reason(PmartException e) {
    String r = e.code() + ": " + e.getMessage();
    return r.length() > 300 ? r.substring(0, 300) : r;
  }
}
