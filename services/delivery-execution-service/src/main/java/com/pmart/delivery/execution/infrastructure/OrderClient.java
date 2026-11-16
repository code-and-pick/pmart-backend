package com.pmart.delivery.execution.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order-service 내부 호출 — 라이더용 전달지(O-5 {@code GET /internal/orders/{id}/drop-info}, 2026-11-14).
 * 개인정보라 받은 값을 저장·로그하지 않는다({@link DropInfo#toString()} 도 가린다). 주소 LAN 기본 {@code
 * http://order-service:8201}.
 */
@Component
public class OrderClient {

  /** 전달지(주문 당시 사본). */
  public record DropInfo(
      String roadAddress,
      String detailAddress,
      @Nullable String postalCode,
      String receiverName,
      String receiverPhone,
      @Nullable String entranceMethod,
      @Nullable String entranceNote,
      @Nullable String deliveryNote,
      @Nullable Double lat,
      @Nullable Double lng) {

    @Override
    public String toString() {
      return "DropInfo[가림]";
    }
  }

  private final RestClient order;
  private final ObjectMapper mapper;

  public OrderClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.execution.urls.order:http://order-service:8201}") String baseUrl) {
    this.order = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  /** 사본이 없는 주문(2026-11-02 이전)·없는 주문은 비어 있다. 못 부르면 Downstream 규칙(503 등). */
  public Optional<DropInfo> dropInfo(String orderId) {
    String body;
    try {
      body =
          Downstream.call(
              "order-service drop-info",
              () ->
                  order
                      .get()
                      .uri("/api/v1/internal/orders/{id}/drop-info", orderId)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (HttpClientErrorException e) {
      // 403(서비스 신원)·400 등은 설정·계약 문제 — 라이더에게는 일시 장애로(503)
      throw PmartException.dependencyUnavailable(
          "order-service drop-info 가 요청을 거절했습니다(" + e.getStatusCode().value() + ").", e);
    }
    JsonNode n = mapper.readTree(body == null ? "{}" : body);
    JsonNode geo = n.path("geo");
    return Optional.of(
        new DropInfo(
            n.path("roadAddress").asString(""),
            n.path("detailAddress").asString(""),
            text(n, "postalCode"),
            n.path("receiverName").asString(""),
            n.path("receiverPhone").asString(""),
            text(n, "entranceMethod"),
            text(n, "entranceNote"),
            text(n, "deliveryNote"),
            geo.path("lat").isNumber() ? geo.path("lat").asDouble() : null,
            geo.path("lng").isNumber() ? geo.path("lng").asDouble() : null));
  }

  /** 다시 배송 실제 집 주소(order 내부 — ADR-0065). 없으면 empty. [0] 도로명 [1] 상세 [2] 우편번호. */
  public Optional<String[]> redeliveryAddress(String orderId, String destinationId) {
    String body;
    try {
      body =
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
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    } catch (HttpClientErrorException e) {
      throw PmartException.dependencyUnavailable(
          "order-service redelivery-destination 가 요청을 거절했습니다(" + e.getStatusCode().value() + ").",
          e);
    }
    JsonNode n = mapper.readTree(body == null ? "{}" : body);
    return Optional.of(
        new String[] {
          n.path("roadAddress").asString(""), text(n, "detailAddress"), text(n, "postalCode")
        });
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.path(field).isString() ? n.path(field).asString() : null;
  }
}
