package com.pmart.delivery.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

/** 기동 검증: 실제 PostgreSQL(Flyway 공통+서비스 마이그레이션)·Kafka 위에서 컨텍스트가 뜨고 /actuator/health 가 200. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      // 같은 PostgreSQL 을 다른 시험과 함께 쓴다 — 배경 판단 주기·새 배달 알림이 그 시험 데이터를 건드리지 않게(2026-11-25)
      "pmart.dispatch.tick-interval=PT1H",
      "pmart.dispatch.tick-initial-delay=PT1H",
      "pmart.dispatch.list-notice-interval=PT1H"
    })
class ServiceBootIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;

  @Test
  void healthIsUpWithoutAuthentication() {
    var body =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/actuator/health")
            .retrieve()
            .body(String.class);
    assertThat(body).contains("\"status\":\"UP\"");
  }
}
