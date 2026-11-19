package com.pmart.rider.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(
    PhoneVerificationProperties.class)
class RiderConfig {

  /** UTC(ADR-0010). */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
