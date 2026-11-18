package com.pmart.notification.infrastructure;

import com.pmart.common.web.Downstream;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * rider-service 내부 — 공지 받는 라이더 ID(rider 0.13.0 {@code GET /internal/riders/recipients},
 * 2026-11-18).
 */
@Component
public class RiderClient {

  private final RestClient rider;
  private final ObjectMapper mapper;

  public RiderClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.notification.rider-base-url:http://rider-service:8403}") String baseUrl) {
    this.rider = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  /** 전체 또는 그 센터 배달지역 라이더 ID 전부(1000개씩 이어 받는다). 못 부르면 Downstream 규칙으로 실패. */
  public List<String> recipients(@Nullable String mfcId) {
    List<String> out = new ArrayList<>();
    String after = null;
    while (true) {
      String a = after;
      String body =
          Downstream.call(
              "rider-service recipients",
              () ->
                  rider
                      .get()
                      .uri(
                          b -> {
                            b.path("/api/v1/internal/riders/recipients").queryParam("size", 1000);
                            if (mfcId != null) {
                              b.queryParam("mfcId", mfcId);
                            }
                            if (a != null) {
                              b.queryParam("after", a);
                            }
                            return b.build();
                          })
                      .retrieve()
                      .body(String.class));
      JsonNode ids = mapper.readTree(body == null ? "{}" : body).path("riderIds");
      List<String> page = new ArrayList<>();
      ids.forEach(n -> page.add(n.asString()));
      out.addAll(page);
      if (page.size() < 1000) {
        return out;
      }
      after = page.get(page.size() - 1);
    }
  }
}
