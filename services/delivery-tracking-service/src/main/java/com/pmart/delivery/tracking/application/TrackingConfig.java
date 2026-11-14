package com.pmart.delivery.tracking.application;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TrackingProperties.class)
@EnableScheduling
class TrackingConfig {

  /** UTC(ADR-0010). */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
