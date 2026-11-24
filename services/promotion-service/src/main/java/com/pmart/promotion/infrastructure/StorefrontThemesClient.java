package com.pmart.promotion.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.promotion.application.PromotionProperties;
import com.pmart.promotion.infrastructure.ThemeCopyStore.Theme;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * storefront {@code GET /internal/storefront/themes}(0.14.0) — 지금 게시된 진열의 테마. {@code /internal} 은
 * 토큰 없이 내부 호출(공용 RestClient, ADR-0026). 실패는 {@link Downstream} 규칙대로 던진다(호출자가 다음 주기에 다시).
 */
@Component
public class StorefrontThemesClient {

  public record Published(int layoutVersion, List<Theme> themes) {}

  private final RestClient client;
  private final ObjectMapper mapper;

  public StorefrontThemesClient(
      RestClient.Builder builder, PromotionProperties props, ObjectMapper mapper) {
    this.client = builder.clone().baseUrl(props.storefrontBaseUrlOrDefault()).build();
    this.mapper = mapper;
  }

  public Published published() {
    String raw =
        Downstream.call(
            "storefront themes",
            () ->
                client
                    .get()
                    .uri("/api/v1/internal/storefront/themes")
                    .retrieve()
                    .body(String.class));
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    List<Theme> themes = new ArrayList<>();
    for (JsonNode t : n.path("themes")) {
      List<String> products = new ArrayList<>();
      t.path("productIds").forEach(p -> products.add(p.asString()));
      themes.add(
          new Theme(
              t.path("sectionId").asString(),
              t.path("kind").asString(),
              t.path("title").asString(),
              products));
    }
    return new Published(n.path("layoutVersion").asInt(0), themes);
  }
}
