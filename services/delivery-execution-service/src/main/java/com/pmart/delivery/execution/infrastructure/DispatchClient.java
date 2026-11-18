package com.pmart.delivery.execution.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * delivery-dispatch 내부 호출 — 사고 지점까지 간 거리(dispatch 0.9.0 {@code GET
 * /internal/deliveries/{id}/incident-distance}, 2026-11-18). 주소 LAN 기본 {@code
 * http://delivery-dispatch-service:8404}.
 */
@Component
public class DispatchClient {

  private static final Logger log = LoggerFactory.getLogger(DispatchClient.class);

  private final RestClient dispatch;
  private final ObjectMapper mapper;

  public DispatchClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.execution.urls.dispatch:http://delivery-dispatch-service:8404}")
          String baseUrl) {
    this.dispatch = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  /**
   * MFC → 사고 지점 도로 거리(m). 잴 수 없거나(404 — 위치·길 없음) 부르지 못하면 비어 있다 — 호출자는 산정 불가(지원센터 조정)로 둔다. 사고 확정을 막지
   * 않는다.
   */
  public OptionalInt incidentDistance(String deliveryId, Instant at) {
    try {
      String body =
          Downstream.call(
              "delivery-dispatch incident-distance",
              () ->
                  dispatch
                      .get()
                      .uri(
                          "/api/v1/internal/deliveries/{id}/incident-distance?at={at}",
                          deliveryId,
                          at.toString())
                      .retrieve()
                      .body(String.class));
      JsonNode n = mapper.readTree(body == null ? "{}" : body);
      return n.path("distanceM").isNumber()
          ? OptionalInt.of(n.path("distanceM").asInt())
          : OptionalInt.empty();
    } catch (HttpClientErrorException e) {
      log.info("배송 {} 사고 지점 거리를 잴 수 없음(HTTP {}) — 산정 불가", deliveryId, e.getStatusCode().value());
      return OptionalInt.empty();
    } catch (PmartException e) {
      log.warn("배송 {} 사고 지점 거리 조회 실패({}) — 산정 불가", deliveryId, e.code());
      return OptionalInt.empty();
    }
  }
}
