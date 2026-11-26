package com.pmart.transport.planning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * transport-planning-service. 책임·비소유는 services/transport-planning-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class TransportPlanningServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(TransportPlanningServiceApplication.class, args);
  }
}
