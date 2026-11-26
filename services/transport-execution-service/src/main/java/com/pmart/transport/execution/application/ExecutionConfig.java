package com.pmart.transport.execution.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 시계(시험에서 바꿀 수 있게)와 출발 검증·planning 알림·점유 해제 재시도 주기 작업. */
@Configuration
@EnableScheduling
public class ExecutionConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
