package com.pmart.delivery.dispatch.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DispatchProperties.class)
@EnableScheduling
class DispatchConfig {

  /** UTC(ADR-0010). 배차 가능 시간 판정만 Asia/Seoul. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
