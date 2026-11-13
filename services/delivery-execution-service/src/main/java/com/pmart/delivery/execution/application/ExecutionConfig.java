package com.pmart.delivery.execution.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecutionProperties.class)
@EnableScheduling
class ExecutionConfig {

  /** UTC(ADR-0010). 내 배송·수행일·지급 예정일만 Asia/Seoul. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
