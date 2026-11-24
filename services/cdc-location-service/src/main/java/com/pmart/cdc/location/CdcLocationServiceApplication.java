package com.pmart.cdc.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * cdc-location-service. 책임·비소유는 services/cdc-location-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class CdcLocationServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CdcLocationServiceApplication.class, args);
  }
}
