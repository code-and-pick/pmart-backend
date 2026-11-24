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
    return customerOrder(orderId).isPresent();
  }

  /** 본인 주문의 지금 상태(order OrderStatus)·이행 센터(0.40.0). 응답에 없거나 읽지 못하면 null. */
  public record CustomerOrder(@Nullable String status, @Nullable String mfcId) {
    public CustomerOrder(@Nullable String status) {
      this(status, null);
    }
  }

  /**
   * 호출한 고객의 주문이면 그 상태(0.23.0 — 배송 지연 신고). 남의·없는 주문(200 이 아님)은 빈 값. 같은 {@code GET /orders/{id}} 한 번
   * — 응답에서 status 만 읽는다(주소·결제 등은 읽지 않는다).
   */
  @SuppressWarnings("unchecked")
  public java.util.Optional<CustomerOrder> customerOrder(String orderId) {
    String auth = currentAuthorization();
    return Downstream.call(
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
                      if (s != 200) {
                        return java.util.Optional.<CustomerOrder>empty();
                      }
                      Object st = null;
                      Object mfc = null;
                      try {
                        Map<String, Object> body = res.bodyTo(Map.class);
                        st = body == null ? null : body.get("status");
                        mfc = body == null ? null : body.get("mfcId");
                      } catch (RuntimeException e) {
                        // 본문을 못 읽어도 소유는 확인됨 — 상태만 모름(지연 신고는 받지 않는다)
                        st = null;
                      }
                      return java.util.Optional.of(
                          new CustomerOrder(
                              st == null ? null : st.toString(),
                              mfc == null || mfc.toString().isBlank() ? null : mfc.toString()));
                    }));
  }

  /**
   * 0.40.0 문의 센터 채우기 — order 내부 원장 {@code GET /internal/orders/{id}}(토큰 없음, 사진 없이)의 mfcId. 주문이
   * 없으면(404) 빈 값, 센터가 없으면 빈 문자열이 아닌 빈 값. 장애는 던진다(호출자가 멈추고 다음 기동 때 이어서).
   */
  @SuppressWarnings("unchecked")
  public java.util.Optional<String> internalCenter(String orderId) {
    return Downstream.call(
        ORDER,
        () ->
            client
                .get()
                .uri("/api/v1/internal/orders/{id}?includeImages=false", orderId)
                .exchange(
                    (req, res) -> {
                      int s = res.getStatusCode().value();
                      if (s == 404) {
                        return java.util.Optional.<String>empty();
                      }
                      if (s != 200) {
                        throw new IllegalStateException("order-service " + s);
                      }
                      Map<String, Object> body = res.bodyTo(Map.class);
                      Object mfc = body == null ? null : body.get("mfcId");
                      return mfc == null || mfc.toString().isBlank()
                          ? java.util.Optional.<String>empty()
                          : java.util.Optional.of(mfc.toString());
                    }));
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
