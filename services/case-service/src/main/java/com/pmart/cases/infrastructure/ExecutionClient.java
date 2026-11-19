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
 * delivery-execution 배송 상태 조회(동기 1 hop, R-10 2026-11-19 — 전달 실패 결정 전이면 접수 건 닫기를 막는다). 직원 {@code GET
 * /deliveries/{id}} 를 <b>호출한 직원의 토큰</b>으로(OrderClient.staffOrder 와 같은 방식). 주소 {@code
 * pmart.cases.delivery-execution-base-url}(없으면 {@code http://delivery-execution-service:8405} — LAN
 * 은 이름으로 닿는다).
 */
@Component
public class ExecutionClient {

  public static final String EXECUTION = "delivery-execution-service";

  /** 배송 상태. {@code found=false} 면 배송이 없음(404). {@code readable=false} 면 이 직원이 배송을 못 읽음(401·403). */
  public record DeliveryState(boolean found, boolean readable, @Nullable String state) {}

  private final RestClient client;

  public ExecutionClient(RestClient.Builder builder, CaseProperties props) {
    this.client =
        builder
            .clone()
            .baseUrl(
                Objects.requireNonNullElse(
                    props.deliveryExecutionBaseUrl(), "http://delivery-execution-service:8405"))
            .build();
  }

  @SuppressWarnings("unchecked")
  public DeliveryState state(String deliveryId) {
    String auth = currentAuthorization();
    return Downstream.call(
        EXECUTION,
        () ->
            client
                .get()
                .uri("/api/v1/deliveries/{id}", deliveryId)
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
                        throw PmartException.dependencyUnavailable(
                            "delivery-execution-service 가 응답할 수 없습니다.",
                            new IllegalStateException("delivery-execution-service " + s));
                      }
                      if (s == 404) {
                        return new DeliveryState(false, true, null);
                      }
                      if (s == 401 || s == 403) {
                        return new DeliveryState(true, false, null);
                      }
                      if (s != 200) {
                        throw new IllegalStateException("delivery-execution-service " + s);
                      }
                      Map<String, Object> body = res.bodyTo(Map.class);
                      Object st = body == null ? null : body.get("state");
                      return new DeliveryState(true, true, st == null ? null : st.toString());
                    }));
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      return a.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
