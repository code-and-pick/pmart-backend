package com.pmart.cdc.outbound.infrastructure;

import com.pmart.cdc.outbound.application.OutboundProperties;
import com.pmart.common.web.Downstream;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 운영 제한 확인(0.19.0 — cdc-quality 0.4.0 {@code POST /internal/operation-restriction-checks}, WMS
 * CDC-F-031). 맞는 제한이 있으면 409 OPERATION_RESTRICTED, 확인이 안 되면(주소 없음 CONFIG_REQUIRED·미전달
 * DEPENDENCY_UNAVAILABLE·불명 UNKNOWN_RESULT) 새 할당을 보류한다 — "조회 실패면 해당 신규 처리를 보류". 내부 경로라 토큰 없이
 * X-Source-System 만.
 */
@Component
public class RestrictionClient {

  static final String QUALITY = "cdc-quality-service";

  public record Item(String sku, @Nullable String temperatureZone) {}

  private final @Nullable RestClient http;
  private final ObjectMapper mapper;

  public RestrictionClient(
      RestClient.Builder builder, OutboundProperties props, ObjectMapper mapper) {
    this.http = builder.clone().baseUrl(props.cdcQualityBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 막히면 409 OPERATION_RESTRICTED. */
  public void requireAllowed(String cdcId, String activity, List<Item> items) {
    RestClient c = http;
    if (c == null) {
      throw PmartException.configRequired("pmart.outbound.cdc-quality-base-url (운영 제한 확인)");
    }
    List<Map<String, Object>> its = new ArrayList<>();
    for (Item i : items) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", i.sku());
      m.put("temperatureZone", i.temperatureZone());
      its.add(m);
    }
    Map<String, Object> body = Map.of("cdcId", cdcId, "activity", activity, "items", its);
    String raw;
    try {
      raw =
          Downstream.call(
              QUALITY,
              () ->
                  c.post()
                      .uri("/api/v1/internal/operation-restriction-checks")
                      .header("Idempotency-Key", "cdc-outbound-restriction-" + UUID.randomUUID())
                      .header("X-Source-System", "cdc-outbound-service")
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException e) {
      throw new PmartException(
          ErrorCode.DEPENDENCY_UNAVAILABLE,
          "운영 제한 확인이 거절됐습니다(HTTP " + e.getStatusCode().value() + ") — 새 처리를 보류합니다.");
    }
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    if (n.path("restricted").asBoolean(false)) {
      List<PmartException.Detail> d = new ArrayList<>();
      for (JsonNode m : n.path("matches")) {
        d.add(
            new PmartException.Detail(
                m.path("sku").asString(), m.path("restrictionId").asString()));
      }
      throw new PmartException(
          "OPERATION_RESTRICTED",
          HttpStatus.CONFLICT,
          "운영 제한으로 이 센터의 새 " + ("ALLOCATION".equals(activity) ? "할당" : "처리") + "이 막혀 있습니다.",
          false,
          d);
    }
  }
}
