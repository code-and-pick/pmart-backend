package com.pmart.delivery.tracking.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.delivery.tracking.application.TrackingProperties;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** order-service 내부 — 주문의 고객(accountId)만 읽는다(TR-3 고객 소유 확인). 저장·로그하지 않는다. */
@Component
public class OrderClient {

  private final RestClient order;
  private final ObjectMapper mapper;

  public OrderClient(RestClient.Builder builder, ObjectMapper mapper, TrackingProperties props) {
    this.order = builder.clone().baseUrl(props.orderBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 없는 주문이면 empty. 장애는 Downstream 규칙(예외). */
  public Optional<String> accountId(String orderId) {
    String body;
    try {
      body =
          Downstream.call(
              "order-service order",
              () ->
                  order
                      .get()
                      .uri("/api/v1/internal/orders/{id}", orderId)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    String account = mapper.readTree(body == null ? "{}" : body).path("accountId").asString("");
    return account.isBlank() ? Optional.empty() : Optional.of(account);
  }
}
