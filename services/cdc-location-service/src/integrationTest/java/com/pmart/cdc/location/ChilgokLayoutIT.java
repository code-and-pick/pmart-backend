package com.pmart.cdc.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 칠곡 위치 사본(docs/simulator/cdc/layout/chilgok-locations.json, 시뮬레이터 배치 내보내기)이 실제 서버 규칙을 전부 통과하는지:
 * deploy/lan/seed/cdc_locations.py 와 같은 방식(2,000행 묶음·ACTIVE)으로 넣고 유형별 칸 수를 대조한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
class ChilgokLayoutIT extends PostgresKafkaContainerSupport {

  static final Path TABLE = Path.of("../../docs/simulator/cdc/layout/chilgok-locations.json");
  static final String SERVICE = TestJwt.service("pmart-seed");

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @Test
  void wholeChilgokLayoutPassesRulesInBatches() throws Exception {
    jdbc.update("DELETE FROM location_state_history");
    jdbc.update("DELETE FROM location");
    JsonNode d = mapper.readTree(Files.readString(TABLE));
    List<String> cols = new ArrayList<>();
    d.path("columns").forEach(c -> cols.add(c.asString()));
    List<Map<String, Object>> items = new ArrayList<>();
    for (JsonNode r : d.path("rows")) {
      Map<String, Object> cap = new LinkedHashMap<>();
      if (!r.get(cols.indexOf("capacityUnit")).isNull()) {
        cap.put("unit", r.get(cols.indexOf("capacityUnit")).asString());
      }
      if (!r.get(cols.indexOf("maxQty")).isNull()) {
        cap.put("maxQty", r.get(cols.indexOf("maxQty")).asInt());
      }
      if (!r.get(cols.indexOf("maxWeightG")).isNull()) {
        cap.put("maxWeightG", r.get(cols.indexOf("maxWeightG")).asInt());
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("locationCode", r.get(cols.indexOf("locationCode")).asString());
      m.put("centerId", d.path("centerId").asString());
      m.put("temperatureZone", r.get(cols.indexOf("temperatureZone")).asString());
      m.put("locationType", r.get(cols.indexOf("locationType")).asString());
      m.put("capacity", cap);
      items.add(m);
    }
    RestClient http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    for (int i = 0, n = 0; i < items.size(); i += 2000, n++) {
      var chunk = items.subList(i, Math.min(items.size(), i + 2000));
      String key = "cdc-loc-it-" + n;
      var res =
          http.post()
              .uri("/internal/location-batches")
              .headers(
                  h -> {
                    h.setBearerAuth(SERVICE);
                    h.set("Idempotency-Key", key);
                    h.set("X-Source-System", "pmart-seed");
                  })
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  Map.of(
                      "centerId",
                      "CDC01",
                      "approvalRef",
                      "BO-06-IT",
                      "activate",
                      true,
                      "items",
                      chunk))
              .retrieve()
              .toEntity(String.class);
      assertThat(res.getStatusCode().value()).as(String.valueOf(res.getBody())).isEqualTo(200);
    }
    JsonNode counts = d.path("counts");
    for (String type : List.of("RESERVE", "PICK_FACE", "STAGING", "DOCK")) {
      Integer n =
          jdbc.queryForObject(
              "SELECT count(*) FROM location WHERE center_id = 'CDC01' AND location_type = ? AND state = 'ACTIVE'",
              Integer.class,
              type);
      assertThat(n).as(type).isEqualTo(counts.path(type).asInt());
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM location", Integer.class))
        .isEqualTo(counts.path("TOTAL").asInt());
  }
}
