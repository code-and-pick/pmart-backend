package com.pmart.delivery.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.TestJwtConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Python AI 최적화 서버를 켠 배차(ADR-0058 결정 13). AI 서버도 같은 WireMock 이다 — {@link DispatchFlowIT} 의 시나리오를
 * 그대로 다시 돌리며(AI 응답을 고정하지 않은 시나리오는 404 → OR-Tools 로 대신해도 결과가 같아야 한다), AI 가 낸 묶음·방문 순서를 그대로 쓰는지, 틀린
 * 안·503 이면 OR-Tools 로 대신하는지 본다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.dispatch.tick-interval=PT1H",
      "pmart.dispatch.tick-initial-delay=PT1H",
      // 부모 properties 를 물려받지 않는다(이 목록이 대신한다) — 빠지면 새 배달 알림이 15초마다 배경에서 돌아 시험의 run() 과 겹쳐 알림
      // 2건(2026-11-25)
      "pmart.dispatch.list-notice-interval=PT1H",
      "pmart.dispatch.operating-start=00:00",
      "pmart.dispatch.operating-end=24:00",
      "pmart.dispatch.ai.base-url=${pmart.dispatch.urls.rider}" // 부모의 WireMock(up)을 AI 서버로도 쓴다
    })
@Import(TestJwtConfiguration.class)
class AiDispatchIT extends DispatchFlowIT {

  @Test
  void aiBundleAndVisitOrderAreUsedAsGiven() {
    String mfc = "MFC-A1";
    String rider = "RD-ai-" + UUID.randomUUID();
    riders(mfc, rider(rider, "AI_RECOMMENDED", true));
    riderLookup(rider(rider, "AI_RECOMMENDED", true));
    locations(Map.of(rider, new double[] {37.4985, 127.0280}));
    String orderA = "ORD-AA-" + UUID.randomUUID();
    String orderB = "ORD-AB-" + UUID.randomUUID();
    bundledOrder(orderA, "AI-ROAD-A", 37.5030, 127.0300);
    bundledOrder(orderB, "AI-ROAD-B", 37.5034, 127.0306);
    for (String q : List.of("37.503,127.03;37.5034,127.0306", "37.5034,127.0306;37.503,127.03")) {
      up.stubFor(
          post(urlPathEqualTo("/api/v1/internal/distance-matrix"))
              .atPriority(0)
              .withRequestBody(
                  matchingJsonPath("$.origins[0].lat", equalTo(q.split(";")[0].split(",")[0])))
              .withRequestBody(
                  matchingJsonPath("$.origins[1].lat", equalTo(q.split(";")[1].split(",")[0])))
              .willReturn(json(square(100))));
    }
    String a = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-a1-" + orderA, orderA);
    String b = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-a2-" + orderB, orderB);
    // AI 는 B → A 순서로 묶는다
    up.stubFor(
        post(urlPathEqualTo("/api/v1/internal/dispatch-plans"))
            .willReturn(
                json(
                    Map.of(
                        "assignments",
                        List.of(Map.of("riderId", rider, "deliveryIds", List.of(b, a))),
                        "solver",
                        "ROUTEFINDER rf-transformer-100"))));

    engine.tick();

    up.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/internal/dispatch-plans"))
            .withRequestBody(matchingJsonPath("$.maxBundleSize", equalTo("3")))
            .withRequestBody(matchingJsonPath("$.riders[0].riderId", equalTo(rider))));
    List<Map<String, Object>> offers =
        jdbc.queryForList(
            "SELECT delivery_id, bundle_id, stop_seq FROM dispatch_offer WHERE delivery_id IN (?, ?)"
                + " AND state = 'SENT' ORDER BY stop_seq",
            a,
            b);
    assertThat(offers).hasSize(2);
    assertThat(offers.get(0).get("delivery_id")).isEqualTo(b);
    assertThat(offers.get(1).get("delivery_id")).isEqualTo(a);
    assertThat(offers.get(0).get("bundle_id"))
        .isNotNull()
        .isEqualTo(offers.get(1).get("bundle_id"));
  }

  @Test
  void invalidAiPlanOrOutageFallsBackToOrTools() {
    String mfc = "MFC-A2";
    String rider = "RD-ai2-" + UUID.randomUUID();
    riders(mfc, rider(rider, "AI_RECOMMENDED", true));
    riderLookup(rider(rider, "AI_RECOMMENDED", true));
    locations(Map.of(rider, new double[] {37.4985, 127.0280}));
    String d1 = readyShipment(mfc, "SHP-" + UUID.randomUUID(), "PKG-f1-" + UUID.randomUUID());
    // 모르는 라이더에게 배정 — 검증에서 버리고 OR-Tools 로
    up.stubFor(
        post(urlPathEqualTo("/api/v1/internal/dispatch-plans"))
            .willReturn(
                json(
                    Map.of(
                        "assignments",
                        List.of(Map.of("riderId", "RD-stranger", "deliveryIds", List.of(d1))),
                        "solver",
                        "ROUTEFINDER"))));
    engine.tick();
    assertThat(
            jdbc.queryForObject(
                "SELECT rider_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
                String.class,
                d1))
        .isEqualTo(rider);

    // AI 서버 503 — OR-Tools 로
    String mfc3 = "MFC-A3";
    String rider3 = "RD-ai3-" + UUID.randomUUID();
    riders(mfc3, rider(rider3, "AI_RECOMMENDED", true));
    riderLookup(rider(rider3, "AI_RECOMMENDED", true));
    locations(Map.of(rider3, new double[] {37.4985, 127.0280}));
    String d3 = readyShipment(mfc3, "SHP-" + UUID.randomUUID(), "PKG-f3-" + UUID.randomUUID());
    up.stubFor(
        post(urlPathEqualTo("/api/v1/internal/dispatch-plans"))
            .willReturn(aResponse().withStatus(503)));
    engine.tick();
    assertThat(
            jdbc.queryForObject(
                "SELECT rider_id FROM dispatch_offer WHERE delivery_id = ? AND state = 'SENT'",
                String.class,
                d3))
        .isEqualTo(rider3);
  }
}
