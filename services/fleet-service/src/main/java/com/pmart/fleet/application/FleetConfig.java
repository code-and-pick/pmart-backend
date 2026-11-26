package com.pmart.fleet.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 시계(시험에서 바꿀 수 있게). */
@Configuration
public class FleetConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
