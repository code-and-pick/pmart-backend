package com.pmart.fleet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** fleet-service. 책임·비소유는 services/fleet-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
public class FleetServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(FleetServiceApplication.class, args);
  }
}
