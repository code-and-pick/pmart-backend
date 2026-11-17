package com.pmart.mfc.fulfillment.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.application.FulfillmentProperties;
import com.pmart.mfc.fulfillment.domain.WorkRules.ReservationPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory 호출({@code /internal}, 서비스 간 인증 없음 — ADR-0026). 수량 정본은 inventory 다 — 예약 조회는 작업 접수
 * 판단, 피킹 지시는 표시, 작업 실행 반영(PICK·PACK)은 재고 확정(IV-07, 슬라이스 6b)에 쓴다.
 */
@Component
public class InventoryClient {

  public static final String INVENTORY = "mfc-inventory-service";
  public static final String SOURCE = "mfc-fulfillment-service";

  /** 예약 사본. 없으면(404) state null·parts 빈 목록. */
  public record Reservation(@Nullable String state, List<ReservationPart> parts) {}

  /** 피킹 지시 조각(inventory PickPlan). */
  public record PlanPart(
      @Nullable String orderLineId,
      String sku,
      String lotId,
      @Nullable String lotNo,
      @Nullable String expiryDate,
      String state,
      int qty,
      int pickedQty,
      int packedQty,
      List<Map.Entry<String, Integer>> locations,
      Map<String, Integer> allocated) {}

  /** 작업 실행 반영 결과. APPLIED / REJECTED(4xx — code·detail) / UNKNOWN(결과 모름) / NOT_DELIVERED(안 감). */
  public record Commit(
      String outcome,
      @Nullable String commitId,
      int appliedQty,
      @Nullable String code,
      @Nullable String detail) {}

  private final RestClient inventory;
  private final ObjectMapper mapper;

  public InventoryClient(
      RestClient.Builder builder, FulfillmentProperties props, ObjectMapper mapper) {
    this.inventory = builder.clone().baseUrl(props.mfcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 미전달·불명은 PmartException(503 DEPENDENCY_UNAVAILABLE 등) 그대로 — 호출자가 결정을 기록하지 않는다. */
  public Reservation reservation(String reservationId) {
    String raw;
    try {
      raw =
          Downstream.call(
              INVENTORY,
              () ->
                  inventory
                      .get()
                      .uri("/api/v1/internal/reservations/{id}", reservationId)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return new Reservation(null, List.of());
    }
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<ReservationPart> parts = new ArrayList<>();
    for (JsonNode p : n.path("parts")) {
      parts.add(
          new ReservationPart(
              p.path("sku").asString(""), p.path("qty").asInt(0), p.path("state").asString("")));
    }
    return new Reservation(n.path("state").asString(null), parts);
  }

  /** SKU 투영 일괄 조회 한 번의 상한(mfc-inventory {@code GET /internal/skus} maxItems). */
  static final int SKU_BATCH = 100;

  /**
   * SKU 별 활성 바코드(0.7.0 — PDA 스캔 대조). mfc-inventory SKU 투영 {@code GET /internal/skus?skus=}(100건씩).
   * 투영이 없는 SKU 는 결과에 없다. 조회가 안 되면 PmartException·RestClientException 그대로(호출자가 빈 바코드로 둔다).
   */
  public Map<String, List<String>> skuBarcodes(java.util.Collection<String> skus) {
    List<String> all = skus.stream().distinct().sorted().toList();
    Map<String, List<String>> out = new java.util.HashMap<>();
    for (int i = 0; i < all.size(); i += SKU_BATCH) {
      String joined = String.join(",", all.subList(i, Math.min(all.size(), i + SKU_BATCH)));
      String raw =
          Downstream.call(
              INVENTORY + " skus",
              () ->
                  inventory
                      .get()
                      .uri("/api/v1/internal/skus?skus={s}", joined)
                      .retrieve()
                      .body(String.class));
      for (JsonNode n : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
        List<String> barcodes = new ArrayList<>();
        n.path("barcodes").forEach(b -> barcodes.add(b.asString()));
        out.put(n.path("sku").asString(), List.copyOf(barcodes));
      }
    }
    return out;
  }

  /** 적치·복귀 추천 칸(#59, mfc-inventory placement-suggestions). */
  public List<String> suggestPlacement(String mfcId, String sku, String lotId, int qty) {
    String raw =
        Downstream.call(
            INVENTORY,
            () ->
                inventory
                    .get()
                    .uri(
                        u ->
                            u.path("/api/v1/internal/placement-suggestions")
                                .queryParam("mfcId", mfcId)
                                .queryParam("sku", sku)
                                .queryParam("lotId", lotId)
                                .queryParam("qty", qty)
                                .build())
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<String> out = new ArrayList<>();
    for (JsonNode c : n.path("locationCodes")) {
      out.add(c.asString(""));
    }
    return out;
  }

  public List<PlanPart> pickPlan(String reservationId) {
    String raw =
        Downstream.call(
            INVENTORY,
            () ->
                inventory
                    .get()
                    .uri("/api/v1/internal/reservations/{id}/pick-plan", reservationId)
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<PlanPart> out = new ArrayList<>();
    for (JsonNode p : n.path("parts")) {
      List<Map.Entry<String, Integer>> locs = new ArrayList<>();
      Map<String, Integer> allocated = new java.util.LinkedHashMap<>();
      for (JsonNode l : p.path("locations")) {
        locs.add(Map.entry(l.path("locationCode").asString(""), l.path("qty").asInt(0)));
        int a = l.path("allocatedQty").asInt(0);
        if (a > 0) {
          allocated.put(l.path("locationCode").asString(""), a);
        }
      }
      out.add(
          new PlanPart(
              p.path("orderLineId").asString(null),
              p.path("sku").asString(""),
              p.path("lotId").asString(""),
              p.path("lotNo").asString(null),
              p.path("expiryDate").asString(null),
              p.path("state").asString(""),
              p.path("qty").asInt(0),
              p.path("pickedQty").asInt(0),
              p.path("packedQty").asInt(0),
              locs,
              allocated));
    }
    return out;
  }

  /** 예약의 위치 배정(피킹 시작 때). inventory 쪽이 멱등(이미 배정된 양은 다시 배정하지 않음). 실패는 PmartException 그대로. */
  public void allocateLocations(String reservationId, String key) {
    Downstream.call(
        INVENTORY,
        () ->
            inventory
                .post()
                .uri("/api/v1/internal/reservations/{id}/location-allocations", reservationId)
                .header("Idempotency-Key", key)
                .header("X-Source-System", SOURCE)
                .retrieve()
                .toBodilessEntity());
  }

  /**
   * CommitTaskExecution(PICK·PACK). 같은 executionId 는 inventory 가 한 번만 반영하므로(다른 요청 ID 여도) 결과를 모르면 같은
   * 실행 ID 로 다시 보내면 된다. Idempotency-Key 도 executionId 로 둔다.
   */
  public Commit commit(String executionId, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              INVENTORY,
              () ->
                  inventory
                      .post()
                      .uri("/api/v1/internal/task-executions")
                      .header("Idempotency-Key", executionId)
                      .header("X-Source-System", SOURCE)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
      return new Commit(
          "APPLIED", n.path("resultId").asString(null), n.path("appliedQty").asInt(0), null, null);
    } catch (HttpClientErrorException e) {
      JsonNode p = problem(e.getResponseBodyAsString());
      return new Commit(
          "REJECTED",
          null,
          0,
          p.path("code").asString("HTTP_" + e.getStatusCode().value()),
          p.path("detail").asString(null));
    } catch (PmartException e) {
      if (ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code())) {
        return new Commit("NOT_DELIVERED", null, 0, e.code(), e.getMessage());
      }
      return new Commit("UNKNOWN", null, 0, e.code(), e.getMessage());
    }
  }

  /**
   * 집품 뒤 취소(IV-08) — 집품·포장분 취소 복귀 대기, 나머지 예약·이행부족 해제. 키 = 중지 요청 ID(같은 본문 재생). 결과는 {@link #commit} 과
   * 같은 모양(appliedQty = 해제한 R).
   */
  public Commit cancelReturn(String reservationId, String key, String bodyJson) {
    try {
      String raw =
          Downstream.call(
              INVENTORY,
              () ->
                  inventory
                      .post()
                      .uri("/api/v1/internal/reservations/{id}/cancel-returns", reservationId)
                      .header("Idempotency-Key", key)
                      .header("X-Source-System", SOURCE)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(bodyJson)
                      .retrieve()
                      .body(String.class));
      JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
      return new Commit(
          "APPLIED", n.path("resultId").asString(null), n.path("appliedQty").asInt(0), null, null);
    } catch (HttpClientErrorException e) {
      JsonNode p = problem(e.getResponseBodyAsString());
      return new Commit(
          "REJECTED",
          null,
          0,
          p.path("code").asString("HTTP_" + e.getStatusCode().value()),
          p.path("detail").asString(null));
    } catch (PmartException e) {
      String outcome =
          ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code()) ? "NOT_DELIVERED" : "UNKNOWN";
      return new Commit(outcome, null, 0, e.code(), e.getMessage());
    }
  }

  /**
   * CommitPackageHandover(인계 전기). 키 = 인계 ID(같은 포장은 inventory 가 한 번만 반영). 결과는 {@link #commit} 과 같은
   * 모양.
   */
  public Commit packageHandover(String handoverId, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              INVENTORY,
              () ->
                  inventory
                      .post()
                      .uri("/api/v1/internal/package-handovers")
                      .header("Idempotency-Key", handoverId)
                      .header("X-Source-System", SOURCE)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
      return new Commit(
          "APPLIED", n.path("resultId").asString(null), n.path("appliedQty").asInt(0), null, null);
    } catch (HttpClientErrorException e) {
      JsonNode p = problem(e.getResponseBodyAsString());
      return new Commit(
          "REJECTED",
          null,
          0,
          p.path("code").asString("HTTP_" + e.getStatusCode().value()),
          p.path("detail").asString(null));
    } catch (PmartException e) {
      String outcome =
          ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code()) ? "NOT_DELIVERED" : "UNKNOWN";
      return new Commit(outcome, null, 0, e.code(), e.getMessage());
    }
  }

  /** 결품 보고 결과. commit 은 반영 여부(APPLIED/REJECTED/UNKNOWN/NOT_DELIVERED), blockedQty 는 재확보 못 한 양. */
  public record ShortageOutcome(Commit commit, int blockedQty, int resecuredQty) {}

  /** ReportShortage(IV-09). reportId 가 같으면 inventory 가 한 번만 반영한다. */
  public ShortageOutcome reportShortage(String reportId, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              INVENTORY,
              () ->
                  inventory
                      .post()
                      .uri("/api/v1/internal/shortage-reports")
                      .header("Idempotency-Key", reportId)
                      .header("X-Source-System", SOURCE)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
      return new ShortageOutcome(
          new Commit("APPLIED", n.path("commitId").asString(null), 0, null, null),
          n.path("blockedQty").asInt(0),
          n.path("resecuredQty").asInt(0));
    } catch (HttpClientErrorException e) {
      JsonNode p = problem(e.getResponseBodyAsString());
      return new ShortageOutcome(
          new Commit(
              "REJECTED",
              null,
              0,
              p.path("code").asString("HTTP_" + e.getStatusCode().value()),
              p.path("detail").asString(null)),
          0,
          0);
    } catch (PmartException e) {
      String outcome =
          ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code()) ? "NOT_DELIVERED" : "UNKNOWN";
      return new ShortageOutcome(new Commit(outcome, null, 0, e.code(), e.getMessage()), 0, 0);
    }
  }

  private JsonNode problem(String raw) {
    try {
      return mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
    } catch (RuntimeException ex) {
      return mapper.createObjectNode();
    }
  }
}
