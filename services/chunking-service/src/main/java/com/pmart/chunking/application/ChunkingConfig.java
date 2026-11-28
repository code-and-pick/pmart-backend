package com.pmart.chunking.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ChunkingConfig {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
