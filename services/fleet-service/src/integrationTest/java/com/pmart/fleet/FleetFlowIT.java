package com.pmart.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 운송사·차량·기사 등록(관리자만)·조회와 점유 예약([시작,끝) 겹침 — 차량·기사 따로, 같은 목적 다시 잡기, 해제). 실제 PostgreSQL. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@Import(TestJwtConfiguration.class)
class FleetFlowIT extends PostgresKafkaContainerSupport {

  static final String MANAGER =
      TestJwt.operator("op-m", List.of("CDC_SUPERVISOR"), List.of("CDC01"));
  static final String WORKER = TestJwt.operator("op-w", List.of("CDC_WORKER"), List.of("CDC01"));
  static final String SERVICE = TestJwt.service("transport-planning-service");

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  RestClient http;

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  record Res(int status, JsonNode body) {}

  Res post(String token, String path, String key, Object body) {
    var e =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "it");
                })
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  Res get(String token, String path) {
    var e =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(e.getStatusCode().value(), read(e.getBody()));
  }

  JsonNode read(String body) {
    return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
  }

  static String key() {
    return UUID.randomUUID().toString();
  }

  static String uid() {
    return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  Map<String, Object> vehicle(String id, String carrier, String temp) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("vehicleId", id);
    m.put("carrierId", carrier);
    m.put("plateNo", "12가" + id.substring(id.length() - 4));
    m.put("contractType", "REGULAR");
    m.put("tempClass", temp);
    m.put("tonnage", 5);
    return m;
  }

  Map<String, Object> reservation(String v, String d, Instant s, Instant e, String purpose) {
    return Map.of(
        "vehicleId",
        v,
        "driverId",
        d,
        "window",
        Map.of("start", s.toString(), "end", e.toString()),
        "purposeRef",
        purpose);
  }

  @Test
  void registerAndReserve() {
    String u = uid();
    String carrier = "CR-" + u;
    String v1 = "VH-" + u + "-01";
    String v2 = "VH-" + u + "-02";
    String d1 = "DR-" + u + "-01";
    String d2 = "DR-" + u + "-02";

    // 1) 등록 — 관리자만, 같은 키 재전송은 같은 결과, 같은 ID 다른 키는 409
    assertThat(
            post(WORKER, "/carriers", key(), Map.of("carrierId", carrier, "name", "칠곡운수")).status())
        .isEqualTo(403);
    String ck = key();
    Res c = post(MANAGER, "/carriers", ck, Map.of("carrierId", carrier, "name", "칠곡운수"));
    assertThat(c.status()).as(c.body().toString()).isEqualTo(201);
    assertThat(
            post(MANAGER, "/carriers", ck, Map.of("carrierId", carrier, "name", "칠곡운수")).status())
        .isEqualTo(201);
    assertThat(
            post(MANAGER, "/carriers", key(), Map.of("carrierId", carrier, "name", "칠곡운수"))
                .body()
                .path("code")
                .asString())
        .isEqualTo("ALREADY_EXISTS");
    Res veh = post(MANAGER, "/vehicles", key(), vehicle(v1, carrier, "CHILLED"));
    assertThat(veh.status()).as(veh.body().toString()).isEqualTo(201);
    assertThat(veh.body().path("maxRt").asInt()).isEqualTo(20);
    assertThat(veh.body().path("maxWeightKg").asInt()).isEqualTo(5000);
    assertThat(post(MANAGER, "/vehicles", key(), vehicle(v2, carrier, "CHILLED")).status())
        .isEqualTo(201);
    assertThat(
            post(MANAGER, "/vehicles", key(), vehicle("VH-" + u + "-09", "CR-NONE", "AMBIENT"))
                .status())
        .isEqualTo(404);
    for (String d : List.of(d1, d2)) {
      assertThat(
              post(
                      MANAGER,
                      "/drivers",
                      key(),
                      Map.of("driverId", d, "carrierId", carrier, "displayName", "기사 " + d))
                  .status())
          .isEqualTo(201);
    }

    // 2) 조회 — 운영자 누구나, 거르기
    JsonNode list = get(WORKER, "/vehicles?carrierId=" + carrier + "&tempClass=CHILLED").body();
    assertThat(list.path("items").findValuesAsString("vehicleId")).containsExactly(v1, v2);
    assertThat(
            get(WORKER, "/vehicles?carrierId=" + carrier + "&tempClass=FROZEN")
                .body()
                .path("items"))
        .isEmpty();
    assertThat(get(WORKER, "/drivers?carrierId=" + carrier).body().path("items").size())
        .isEqualTo(2);
    assertThat(get(SERVICE, "/vehicles/" + v1).status()).isEqualTo(200);

    // 3) 바꾸기 — 버전 검사
    Res ch = post(MANAGER, "/vehicles/" + v2 + "/changes", key(), Map.of("maxRt", 18));
    assertThat(ch.body().path("maxRt").asInt()).isEqualTo(18);
    assertThat(ch.body().path("version").asInt()).isEqualTo(2);

    // 4) 점유 — 22:30~09:00, 같은 차량 겹치면 409(VEHICLE), 같은 기사 겹치면 409(DRIVER), 끝 = 다음 시작은 겹치지 않음
    Instant s = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    Instant e = s.plus(10, ChronoUnit.HOURS);
    Res r1 =
        post(SERVICE, "/internal/reservations", key(), reservation(v1, d1, s, e, "TO-" + u + "-1"));
    assertThat(r1.status()).as(r1.body().toString()).isEqualTo(200);
    assertThat(r1.body().path("state").asString()).isEqualTo("HELD");
    Res clashV =
        post(
            SERVICE,
            "/internal/reservations",
            key(),
            reservation(v1, d2, s.plusSeconds(3600), e, "TO-" + u + "-2"));
    assertThat(clashV.status()).isEqualTo(409);
    assertThat(clashV.body().path("code").asString()).isEqualTo("RESOURCE_OCCUPIED");
    assertThat(clashV.body().toString()).contains("VEHICLE");
    Res clashD =
        post(SERVICE, "/internal/reservations", key(), reservation(v2, d1, s, e, "TO-" + u + "-2"));
    assertThat(clashD.status()).isEqualTo(409);
    assertThat(clashD.body().toString()).contains("DRIVER");
    assertThat(
            post(
                    SERVICE,
                    "/internal/reservations",
                    key(),
                    reservation(v1, d1, e, e.plusSeconds(3600), "TO-" + u + "-3"))
                .status())
        .isEqualTo(200);

    // 5) 같은 목적 다시 — 같은 본문이면 같은 점유, 다른 차량이면 앞 것을 풀고 새로
    Res same =
        post(SERVICE, "/internal/reservations", key(), reservation(v1, d1, s, e, "TO-" + u + "-1"));
    assertThat(same.body().path("reservationId").asString())
        .isEqualTo(r1.body().path("reservationId").asString());
    Res moved =
        post(SERVICE, "/internal/reservations", key(), reservation(v2, d2, s, e, "TO-" + u + "-1"));
    assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);
    assertThat(moved.body().path("vehicleId").asString()).isEqualTo(v2);
    JsonNode old =
        get(SERVICE, "/internal/reservations/" + r1.body().path("reservationId").asString()).body();
    assertThat(old.path("state").asString()).isEqualTo("RELEASED");
    assertThat(old.path("releaseReason").asString()).isEqualTo("REPLANNED");

    // 6) 해제(멱등) 뒤 그 자리를 다시 잡을 수 있다
    String mid = moved.body().path("reservationId").asString();
    String rk = key();
    assertThat(
            post(
                    SERVICE,
                    "/internal/reservations/" + mid + "/releases",
                    rk,
                    Map.of("reason", "CANCELLED"))
                .body()
                .path("state")
                .asString())
        .isEqualTo("RELEASED");
    assertThat(
            post(
                    SERVICE,
                    "/internal/reservations/" + mid + "/releases",
                    key(),
                    Map.of("reason", "CANCELLED"))
                .status())
        .isEqualTo(200);
    assertThat(
            post(
                    SERVICE,
                    "/internal/reservations",
                    key(),
                    reservation(v2, d2, s, e, "TO-" + u + "-4"))
                .status())
        .isEqualTo(200);

    // 7) 사용 중지 차량은 잡지 않는다
    post(MANAGER, "/vehicles/" + v2 + "/changes", key(), Map.of("active", false));
    Res inactive =
        post(
            SERVICE,
            "/internal/reservations",
            key(),
            reservation(v2, d2, e.plusSeconds(7200), e.plusSeconds(9000), "TO-" + u + "-5"));
    assertThat(inactive.body().path("code").asString()).isEqualTo("RESOURCE_INACTIVE");
  }
}
