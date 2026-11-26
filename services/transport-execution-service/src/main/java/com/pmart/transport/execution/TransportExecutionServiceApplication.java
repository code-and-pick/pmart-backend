package com.pmart.transport.execution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * transport-execution-service. 책임·비소유는 services/transport-execution-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class TransportExecutionServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(TransportExecutionServiceApplication.class, args);
  }
}
