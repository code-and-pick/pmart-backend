package com.pmart.delivery.execution.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ExecutionConfig {

  /** UTC(ADR-0010). 내 배송 날짜 필터만 Asia/Seoul. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
