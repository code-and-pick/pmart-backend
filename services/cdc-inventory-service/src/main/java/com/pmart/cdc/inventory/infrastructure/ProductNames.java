package com.pmart.cdc.inventory.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SKU → 상품 관리용 명칭(cdc-inventory 0.17.0 CI-1 — cdc-outbound ProductNames 를 옮김, MFC 와 같은
 * internalName). 주소 {@code pmart.inventory.product-base-url} 가 비면 이름 없이(null). product-service
 * {@code GET /internal/skus?skus=}(최대 100건)를 10분 담아 둔다. 상품 서비스가 안 되면 이름 없이(null) — 화면 조회를 막지 않는다.
 */
@Component
public class ProductNames {

  private static final Logger log = LoggerFactory.getLogger(ProductNames.class);
  private static final Duration TTL = Duration.ofMinutes(10);
  private static final Duration BUDGET = Duration.ofSeconds(2); // 응답 하나에서 이름 찾기에 쓰는 시간 상한

  private record Entry(String name, Instant at) {}

  private final Map<String, Entry> cache = new ConcurrentHashMap<>();
  private final @org.jspecify.annotations.Nullable RestClient http;
  private final ObjectMapper mapper;

  public ProductNames(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.inventory.product-base-url:}") String baseUrl) {
    this.http = baseUrl.isBlank() ? null : builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  public Map<String, String> of(Collection<String> skus) {
    Map<String, String> out = new HashMap<>();
    RestClient http = this.http;
    if (http == null || skus.isEmpty()) {
      return out;
    }
    List<String> miss = new ArrayList<>();
    Instant now = Instant.now();
    for (String s : skus.stream().distinct().toList()) {
      Entry e = cache.get(s);
      if (e != null && e.at().plus(TTL).isAfter(now)) {
        if (!e.name().isEmpty()) {
          out.put(s, e.name());
        }
      } else {
        miss.add(s);
      }
    }
    Instant deadline = now.plus(BUDGET);
    for (int i = 0; i < miss.size(); i += 100) {
      List<String> part = miss.subList(i, Math.min(miss.size(), i + 100));
      try {
        // SKU → 상품 ID·승인 버전(SkuDetail 에는 이름이 없다)
        String body =
            http.get()
                .uri("/api/v1/internal/skus?skus={s}", String.join(",", part))
                .header("X-Source-System", "cdc-inventory-service")
                .retrieve()
                .body(String.class);
        Map<String, List<String>> skusOf = new HashMap<>();
        Map<String, Integer> versionOf = new HashMap<>();
        for (String s : part) {
          cache.put(s, new Entry("", now)); // 없는 SKU·이름 없는 상품도 잠시 담아 매번 부르지 않는다
        }
        for (JsonNode it : mapper.readTree(body == null ? "{}" : body).path("items")) {
          String sku = it.path("sku").asString(null);
          String pid = it.path("productId").asString(null);
          if (sku != null && pid != null && it.path("version").isNumber()) {
            skusOf.computeIfAbsent(pid, k -> new ArrayList<>()).add(sku);
            versionOf.put(pid, it.path("version").asInt());
          }
        }
        // 상품 승인 스냅샷의 관리용 명칭(internalName — MFC 와 같은 기준)
        for (var e : skusOf.entrySet()) {
          if (Instant.now().isAfter(deadline)) {
            log.warn("상품 이름 조회 시간 넘김 — 나머지는 다음 조회에");
            return out;
          }
          String prod =
              http.get()
                  .uri(
                      "/api/v1/internal/products/{id}/versions/{v}",
                      e.getKey(),
                      versionOf.get(e.getKey()))
                  .header("X-Source-System", "cdc-inventory-service")
                  .retrieve()
                  .body(String.class);
          String name =
              mapper.readTree(prod == null ? "{}" : prod).path("internalName").asString(null);
          if (name != null && !name.isBlank()) {
            for (String sku : e.getValue()) {
              cache.put(sku, new Entry(name, now));
              out.put(sku, name);
            }
          }
        }
      } catch (RuntimeException e) {
        log.warn("상품 이름 조회 실패(이름 없이 응답): {}", e.toString());
        return out;
      }
    }
    return out;
  }
}
