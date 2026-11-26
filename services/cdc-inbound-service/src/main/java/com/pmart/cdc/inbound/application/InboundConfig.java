package com.pmart.cdc.inbound.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 시계(시험에서 바꿀 수 있게)와 작업 재시도·자동 보충 감지 주기 작업. */
@Configuration
@EnableScheduling
public class InboundConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
