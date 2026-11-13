package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.inbound.application.InboundProperties;
import com.pmart.mfc.inbound.application.SkuLabels.Label;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory SKU 사본 일괄 조회({@code GET /internal/skus?skus=}, 100건씩) — 현장 화면의 상품명·바코드(BR-53 R8).
 * 내부 호출(토큰 없음, ADR-0026). 없는 SKU 는 결과에 없다.
 */
@Component
public class InventorySkuClient {

  static final int BATCH = 100;

  private final RestClient http;
  private final ObjectMapper mapper;

  public InventorySkuClient(
      RestClient.Builder builder, InboundProperties props, ObjectMapper mapper) {
    this.http = builder.clone().baseUrl(props.mfcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  public Map<String, Label> labels(List<String> skus) {
    Map<String, Label> out = new HashMap<>();
    for (int i = 0; i < skus.size(); i += BATCH) {
      String joined = String.join(",", skus.subList(i, Math.min(skus.size(), i + BATCH)));
      String raw =
          Downstream.call(
              "mfc-inventory-service skus",
              () ->
                  http.get()
                      .uri("/api/v1/internal/skus?skus={s}", joined)
                      .retrieve()
                      .body(String.class));
      for (JsonNode n : mapper.readTree(raw == null ? "{}" : raw).path("items")) {
        List<String> barcodes = new ArrayList<>();
        n.path("barcodes").forEach(b -> barcodes.add(b.asString()));
        String name = n.hasNonNull("productName") ? n.get("productName").asString() : null;
        out.put(n.path("sku").asString(), new Label(name, List.copyOf(barcodes)));
      }
    }
    return out;
  }
}
