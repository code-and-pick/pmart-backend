package com.pmart.delivery.dispatch.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.dispatch.application.DispatchProperties;
import com.pmart.delivery.dispatch.domain.Geo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 배차가 부르는 서비스들(모두 토큰 없는 내부 호출 — 공용 RestClient 가 /internal/ 에 X-Source-System 을 붙인다). 실패 규칙은 {@link
 * Downstream}(미전달·503 → DEPENDENCY_UNAVAILABLE, 시간 초과·5xx → UNKNOWN_RESULT).
 *
 * <ul>
 *   <li>rider-service: 적격 라이더·라이더 상태
 *   <li>delivery-tracking: 라이더 마지막 위치, 도로 거리 행렬(카카오모빌리티 — 직선거리 대체 없음, ADR-0058)
 *   <li>order-service: 배송지(O-4 — 도로명주소는 좌표 변환에만, 저장·로그 금지)
 *   <li>center-master: MFC 좌표(CM-1)
 *   <li>serviceability: 도로명주소 → 좌표(정확 일치)
 *   <li>claim-service: 배차 불가 자동 취소(시스템 취소 DISPATCH_UNAVAILABLE — 2026-11-20)
 * </ul>
 */
@Component
public class Upstreams {

  public record Rider(
      String riderId,
      String vehicleType,
      String dispatchMode,
      boolean oneAtATime,
      boolean acceptsNewDispatch,
      @Nullable String operatingMfcId) {}

  public record Location(String riderId, Geo at, Instant receivedAt, String quality) {}

  /** 행렬 한 칸. ok=false 면 길 없음. */
  public record Leg(boolean ok, int distanceM, int durationS) {}

  public record Destination(
      String status,
      String deliveryMode,
      @Nullable String roadAddress,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      @Nullable Instant confirmedAt) {}

  public record Place(Geo at, @Nullable String areaLabel) {}

  private final RestClient rider;
  private final RestClient tracking;
  private final RestClient order;
  private final RestClient center;
  private final RestClient serviceability;
  private final RestClient claim;
  private final ObjectMapper mapper;

  public Upstreams(RestClient.Builder builder, DispatchProperties props, ObjectMapper mapper) {
    DispatchProperties.Urls u = props.urls();
    this.rider = builder.clone().baseUrl(u.rider()).build();
    this.tracking = builder.clone().baseUrl(u.tracking()).build();
    this.order = builder.clone().baseUrl(u.order()).build();
    this.center = builder.clone().baseUrl(u.centerMaster()).build();
    this.serviceability = builder.clone().baseUrl(u.serviceability()).build();
    this.claim = builder.clone().baseUrl(u.claim()).build();
    this.mapper = mapper;
  }

  // ---------- claim ----------

  /** 자동 취소 결과 — accepted 면 claim 이 취소를 접수(또는 이미 진행 중), 아니면 거절 코드. */
  public record CancelResult(boolean accepted, @Nullable String rejectCode) {}

  /**
   * 배차 불가 자동 취소(2026-11-20 사용자 "쿠폰 안 되면 자동취소만", "약속+30분"): claim 시스템 취소(reason
   * DISPATCH_UNAVAILABLE) — 주문 전체 취소·전액 환불, 그 뒤 OrderCancelled 로 이 배송도 픽업 전 취소된다. 같은 배송은 같은 멱등 키.
   * 4xx 는 거절 코드로, 연결 안 됨·5xx 는 예외(호출자가 나중에 다시).
   */
  public CancelResult cancelOrderForNoRider(String orderId, String deliveryId) {
    try {
      Downstream.call(
          "claim-service system-cancellation",
          () ->
              claim
                  .post()
                  .uri("/api/v1/internal/claims/system-cancellations")
                  .contentType(MediaType.APPLICATION_JSON)
                  .header("Idempotency-Key", "dispatch-no-rider:" + deliveryId)
                  .body(
                      Map.of(
                          "orderId",
                          orderId,
                          "reason",
                          "DISPATCH_UNAVAILABLE",
                          "reasonDetail",
                          "배차 불가 자동 취소 — 배송 " + deliveryId + " 약속 시각이 지나도 라이더 없음"))
                  .retrieve()
                  .body(String.class));
      return new CancelResult(true, null);
    } catch (HttpClientErrorException e) {
      String code = "HTTP_" + e.getStatusCode().value();
      try {
        String c = mapper.readTree(e.getResponseBodyAsString()).path("code").asString("");
        if (!c.isBlank()) {
          code = c;
        }
      } catch (RuntimeException ignored) {
        // 본문이 JSON 이 아니면 HTTP 코드
      }
      return new CancelResult(false, code);
    }
  }

  // ---------- rider ----------

  public List<Rider> eligibleRiders(String mfcId, double radiusKm) {
    JsonNode n =
        json(
            Downstream.call(
                "rider-service eligible",
                () ->
                    rider
                        .get()
                        .uri(
                            "/api/v1/internal/riders/eligible?mfcId={m}&radiusKm={r}&size=200",
                            mfcId,
                            radiusKm)
                        .retrieve()
                        .body(String.class)));
    List<Rider> out = new ArrayList<>();
    for (JsonNode r : n.path("items")) {
      out.add(rider(r));
    }
    return out;
  }

  public Optional<Rider> rider(String riderId) {
    try {
      return Optional.of(
          rider(
              json(
                  Downstream.call(
                      "rider-service rider",
                      () ->
                          rider
                              .get()
                              .uri("/api/v1/internal/riders/{id}", riderId)
                              .retrieve()
                              .body(String.class)))));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }

  private static Rider rider(JsonNode r) {
    JsonNode op = r.path("operating");
    boolean accepts =
        "ON_DUTY".equals(op.path("status").asString(""))
            && op.path("newDispatchEnabled").asBoolean(false)
            && "NORMAL".equals(r.path("restriction").asString(""));
    return new Rider(
        r.path("riderId").asString(),
        r.path("vehicleType").asString("MOTORCYCLE"),
        r.path("dispatchMode").asString("AI_RECOMMENDED"),
        r.path("oneAtATime").asBoolean(false),
        accepts,
        op.path("mfcId").asString(null));
  }

  // ---------- tracking ----------

  public List<Location> locations(List<String> riderIds) {
    if (riderIds.isEmpty()) {
      return List.of();
    }
    String ids = String.join(",", riderIds);
    JsonNode n =
        json(
            Downstream.call(
                "delivery-tracking locations",
                () ->
                    tracking
                        .get()
                        .uri("/api/v1/internal/rider-locations?riderIds={ids}", ids)
                        .retrieve()
                        .body(String.class)));
    List<Location> out = new ArrayList<>();
    for (JsonNode l : n.path("items")) {
      out.add(
          new Location(
              l.path("riderId").asString(),
              new Geo(l.path("lat").asDouble(), l.path("lng").asDouble()),
              Instant.parse(l.path("receivedAt").asString()),
              l.path("quality").asString("UNKNOWN")));
    }
    return out;
  }

  /** 위치 점 하나(정한 시각 근처). {@code at} 은 단말 기록 시각. */
  public record PointAt(Geo at, Instant recordedAt) {}

  /**
   * 정한 시각에 가장 가까운 라이더 위치 점(DD-2 오배송 회수지 — 원래 라이더가 완료를 누른 곳). 그 앞뒤에 점이 없으면 empty. URL 에는 시각만(좌표는 응답
   * 본문).
   */
  public Optional<PointAt> locationAt(String riderId, Instant at) {
    JsonNode n;
    try {
      n =
          json(
              Downstream.call(
                  "delivery-tracking location-at",
                  () ->
                      tracking
                          .get()
                          .uri(
                              "/api/v1/internal/riders/{id}/location-at?at={at}",
                              riderId,
                              at.toString())
                          .retrieve()
                          .body(String.class)));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    if (!n.path("lat").isNumber() || !n.path("lng").isNumber()) {
      return Optional.empty();
    }
    return Optional.of(
        new PointAt(
            new Geo(n.path("lat").asDouble(), n.path("lng").asDouble()),
            Instant.parse(n.path("recordedAt").asString())));
  }

  public Optional<Location> location(String riderId) {
    return locations(List.of(riderId)).stream().findFirst();
  }

  /** [출발지][도착지]. 카카오 오류는 tracking 이 422/429/503 으로 돌려주고 여기서는 그대로 올린다(직선거리 대체 없음). */
  public Leg[][] matrix(List<Geo> origins, List<Geo> destinations) {
    // 좌표는 본문으로(URL 쿼리는 추적 기록 http.url 에 남는다 — PC 1 검토 H3)
    Map<String, Object> body =
        Map.of(
            "origins", origins.stream().map(Upstreams::point).toList(),
            "destinations", destinations.stream().map(Upstreams::point).toList());
    JsonNode n =
        json(
            Downstream.call(
                "delivery-tracking distance-matrix",
                () ->
                    tracking
                        .post()
                        .uri("/api/v1/internal/distance-matrix")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .body(body)
                        .retrieve()
                        .body(String.class)));
    Leg[][] out = new Leg[origins.size()][destinations.size()];
    for (int i = 0; i < origins.size(); i++) {
      JsonNode row = n.path("rows").path(i).path("elements");
      for (int j = 0; j < destinations.size(); j++) {
        JsonNode e = row.path(j);
        out[i][j] =
            "OK".equals(e.path("status").asString(""))
                ? new Leg(true, e.path("distanceM").asInt(), e.path("durationSeconds").asInt())
                : new Leg(false, 0, 0);
      }
    }
    return out;
  }

  // ---------- order ----------

  public Destination destination(String orderId) {
    JsonNode n;
    try {
      n =
          json(
              Downstream.call(
                  "order-service delivery-destination",
                  () ->
                      order
                          .get()
                          .uri("/api/v1/internal/orders/{id}/delivery-destination", orderId)
                          .retrieve()
                          .body(String.class)));
    } catch (HttpClientErrorException.NotFound e) {
      throw PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다.");
    }
    JsonNode w = n.path("arrivalWindow");
    return new Destination(
        n.path("status").asString(""),
        n.path("deliveryMode").asString("SINGLE"),
        n.path("roadAddress").isString() ? n.path("roadAddress").asString() : null,
        w.path("date").isString() ? LocalDate.parse(w.path("date").asString()) : null,
        w.path("start").asString(null),
        w.path("end").asString(null),
        n.path("confirmedAt").isString() ? Instant.parse(n.path("confirmedAt").asString()) : null);
  }

  /** 오배송 다시 배송 실제 집 도로명주소(order 내부 — ADR-0065). 없으면 empty. 주소는 로그에 남기지 않는다. */
  public Optional<String> redeliveryAddress(String orderId, String destinationId) {
    JsonNode n;
    try {
      n =
          json(
              Downstream.call(
                  "order-service redelivery-destination",
                  () ->
                      order
                          .get()
                          .uri(
                              "/api/v1/internal/orders/{id}/redelivery-destinations/{d}",
                              orderId,
                              destinationId)
                          .retrieve()
                          .body(String.class)));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    return n.path("roadAddress").isString()
        ? Optional.of(n.path("roadAddress").asString())
        : Optional.empty();
  }

  /** 라이더용 주문 요약(order 내부 — 라이더 앱 요청 §1). 없거나 못 받으면 empty(배차를 막지 않는다). */
  public Optional<JsonNode> riderOrderSummary(String orderId) {
    try {
      return Optional.of(
          json(
              Downstream.call(
                  "order-service rider-summary",
                  () ->
                      order
                          .get()
                          .uri("/api/v1/internal/orders/{id}/rider-summary", orderId)
                          .retrieve()
                          .body(String.class))));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * 라이더용 주문 요약 사본 JSON(라이더 앱 요청 §1): order rider-summary + 지점 이름·주소. 주문 요약을 못 받으면 empty — 배차를 막지
   * 않는다.
   */
  public Optional<String> riderSummaryJson(String orderId, String mfcId) {
    JsonNode s = riderOrderSummary(orderId).orElse(null);
    if (s == null || !s.path("items").isArray()) {
      return Optional.empty();
    }
    var out = mapper.createObjectNode();
    out.put("orderId", orderId);
    if (s.path("orderNumber").isString()) {
      out.put("orderNumber", s.path("orderNumber").asString());
    }
    JsonNode c = center(mfcId).orElse(null);
    if (c != null && c.path("name").isString()) {
      out.put("mfcName", c.path("name").asString());
    }
    if (c != null && c.path("address").isString()) {
      out.put("mfcAddress", c.path("address").asString());
    }
    if (c != null && c.path("phone").isString()) {
      // 지점 전화(center-master profile — 2026-11-17 라이더 앱 "가게 전화", 없으면 뺀다)
      out.put("mfcPhone", c.path("phone").asString());
    }
    out.set("items", s.path("items"));
    if (s.path("itemAmount").isNumber()) {
      out.put("itemAmount", s.path("itemAmount").asLong());
    }
    if (s.path("deliveryNote").isString()) {
      out.put("deliveryNote", s.path("deliveryNote").asString());
    }
    return Optional.of(mapper.writeValueAsString(out));
  }

  /** 지점 이름·주소(center-master). 못 받으면 empty. */
  public Optional<JsonNode> center(String mfcId) {
    try {
      return Optional.of(
          json(
              Downstream.call(
                  "center-master center",
                  () ->
                      center
                          .get()
                          .uri("/api/v1/internal/centers/{id}", mfcId)
                          .retrieve()
                          .body(String.class))));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  // ---------- center-master ----------

  public Optional<Geo> centerCoordinates(String mfcId) {
    JsonNode n;
    try {
      n =
          json(
              Downstream.call(
                  "center-master center",
                  () ->
                      center
                          .get()
                          .uri("/api/v1/internal/centers/{id}", mfcId)
                          .retrieve()
                          .body(String.class)));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    JsonNode c = n.path("coordinates");
    if (!c.path("lat").isNumber() || !c.path("lng").isNumber()) {
      return Optional.empty();
    }
    return Optional.of(new Geo(c.path("lat").asDouble(), c.path("lng").asDouble()));
  }

  // ---------- serviceability ----------

  /** 정확 일치 첫 결과. 0건이면 empty. 주소는 로그에 남기지 않는다. */
  public Optional<Place> geocode(String roadAddress) {
    // 주소는 본문으로(URL 쿼리는 추적 기록에 남는다 — H3)
    JsonNode n =
        json(
            Downstream.call(
                "serviceability geocode",
                () ->
                    serviceability
                        .post()
                        .uri("/api/v1/internal/address-geocodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .body(Map.of("query", roadAddress))
                        .retrieve()
                        .body(String.class)));
    JsonNode first = n.path("results").path(0);
    if (!first.path("lat").isNumber()) {
      return Optional.empty();
    }
    return Optional.of(
        new Place(
            new Geo(first.path("lat").asDouble(), first.path("lng").asDouble()),
            areaLabel(first.path("jibunAddress").asString(null))));
  }

  /** "서울 서초구 서초동 1-2" → "서초구 서초동"(라이더 표시 — 동까지만). */
  static @Nullable String areaLabel(@Nullable String jibun) {
    if (jibun == null || jibun.isBlank()) {
      return null;
    }
    String[] t = jibun.trim().split("\\s+");
    if (t.length >= 3) {
      return t[1] + " " + t[2];
    }
    return t.length == 2 ? t[1] : null;
  }

  private static Map<String, Object> point(Geo g) {
    return Map.of("lat", g.lat(), "lng", g.lng());
  }

  private JsonNode json(@Nullable String body) {
    return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
  }
}
