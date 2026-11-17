package com.pmart.cases.infrastructure;

import com.pmart.cases.application.CaseProperties;
import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * order-service 본인 주문 확인(동기 1 hop, 호출자 고객 토큰 전달). order `GET /orders/{id}` 는 고객 토큰만 받고 남의·없는 주문은
 * 404 — 그래서 200 이면 본인 주문이다. 연결 실패·5xx 는 {@link Downstream} 이 503/UNKNOWN 으로 바꾼다.
 */
@Component
public class OrderClient {

  public static final String ORDER = "order-service";

  private final RestClient client;

  public OrderClient(RestClient.Builder builder, CaseProperties props) {
    this.client =
        builder
            .clone()
            .baseUrl(Objects.requireNonNullElse(props.orderServiceBaseUrl(), "http://unconfigured"))
            .build();
  }

  /** 호출한 고객의 주문인가. */
  public boolean ownsOrder(String orderId) {
    String auth = currentAuthorization();
    Integer status =
        Downstream.call(
            ORDER,
            () ->
                client
                    .get()
                    .uri("/api/v1/orders/{id}", orderId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .exchange(
                        (req, res) -> {
                          int s = res.getStatusCode().value();
                          if (s >= 500) {
                            // 0.19.2: 원천 장애로(계약 503, 전에는 500)
                            throw PmartException.dependencyUnavailable(
                                "order-service 가 응답할 수 없습니다.",
                                new IllegalStateException("order-service " + s));
                          }
                          return s;
                        }));
    return status != null && status == 200;
  }

  /** 직원 주문 조회의 결과 — 주문이 있으면 고객 ID. */
  public sealed interface StaffOrder {
    record Found(String customerId) implements StaffOrder {}

    record NotFound() implements StaffOrder {}

    record Forbidden() implements StaffOrder {}
  }

  /**
   * CS 접수(0.12.0) — order {@code GET /orders/lookups/{id}} 를 호출한 직원 토큰으로. 응답에서 customerId 만 쓴다(배송지
   * 등은 가린 값이고 읽지 않는다).
   */
  @SuppressWarnings("unchecked")
  public StaffOrder staffOrder(String orderId) {
    String auth = currentAuthorization();
    return Downstream.call(
        ORDER,
        () ->
            client
                .get()
                .uri("/api/v1/orders/lookups/{id}", orderId)
                .headers(
                    h -> {
                      if (auth != null) {
                        h.set(HttpHeaders.AUTHORIZATION, auth);
                      }
                    })
                .exchange(
                    (req, res) -> {
                      int s = res.getStatusCode().value();
                      if (s >= 500) {
                        // 0.19.2: exchange 는 상태 처리기를 거치지 않아 Downstream 이 503 으로 바꾸지 못한다 — 직접 원천
                        // 장애로(계약 503, 전에는 500)
                        throw PmartException.dependencyUnavailable(
                            "order-service 가 응답할 수 없습니다.",
                            new IllegalStateException("order-service " + s));
                      }
                      if (s == 404) {
                        return new StaffOrder.NotFound();
                      }
                      if (s == 401 || s == 403) {
                        return new StaffOrder.Forbidden();
                      }
                      if (s != 200) {
                        throw new IllegalStateException("order-service " + s);
                      }
                      Map<String, Object> body = res.bodyTo(Map.class);
                      Object c = body == null ? null : body.get("customerId");
                      if (c == null) {
                        throw new IllegalStateException("order-service 응답에 customerId 없음");
                      }
                      return new StaffOrder.Found(c.toString());
                    }));
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      return a.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
