package com.pmart.cases.infrastructure;

import com.pmart.cases.application.CaseProperties;
import com.pmart.common.web.Downstream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * delivery-dispatch 배정 조회(동기 1 hop, 라이더 컴플레인 R-5). {@code /internal/} 경로라 공용 RestClient 가 토큰 없이
 * X-Source-System 만 붙인다(ADR-0026). dispatch 는 배정 전이면 404 ASSIGNMENT_NOT_FOUND, 배송이 없으면 404
 * DELIVERY_NOT_FOUND. 연결 실패·5xx 는 {@link Downstream} 이 503/UNKNOWN 으로 바꾼다.
 */
@Component
public class DispatchClient {

  public static final String DISPATCH = "delivery-dispatch-service";

  /**
   * 조회 결과. riderId 가 null 이면 배정 라이더 없음(notFoundCode 에 dispatch 코드). mfcId = 배송 센터(0.33.0 R-12, 없으면
   * null).
   */
  public record Assignment(
      @Nullable String riderId, @Nullable String notFoundCode, @Nullable String mfcId) {}

  private final RestClient client;
  private final ObjectMapper mapper;

  public DispatchClient(RestClient.Builder builder, CaseProperties props, ObjectMapper mapper) {
    this.client =
        builder
            .clone()
            .baseUrl(
                Objects.requireNonNullElse(props.deliveryDispatchBaseUrl(), "http://unconfigured"))
            .build();
    this.mapper = mapper;
  }

  public Assignment assignment(String deliveryId) {
    return Downstream.call(
        DISPATCH,
        () ->
            client
                .get()
                .uri("/api/v1/internal/deliveries/{id}/assignment", deliveryId)
                .exchange(
                    (req, res) -> {
                      int s = res.getStatusCode().value();
                      byte[] body = res.getBody().readAllBytes();
                      if (s >= 500) {
                        throw HttpServerErrorException.create(
                            res.getStatusCode(),
                            "delivery-dispatch-service",
                            res.getHeaders(),
                            body,
                            StandardCharsets.UTF_8);
                      }
                      JsonNode n =
                          body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
                      if (s == 404) {
                        return new Assignment(null, n.path("code").asString("NOT_FOUND"), null);
                      }
                      if (s >= 400) {
                        throw HttpClientErrorException.create(
                            res.getStatusCode(),
                            "delivery-dispatch-service",
                            res.getHeaders(),
                            body,
                            StandardCharsets.UTF_8);
                      }
                      String riderId = n.path("riderId").asString("");
                      String mfcId = n.path("mfcId").asString("");
                      return riderId.isBlank()
                          ? new Assignment(null, "ASSIGNMENT_NOT_FOUND", null)
                          : new Assignment(riderId, null, mfcId.isBlank() ? null : mfcId);
                    }));
  }
}
