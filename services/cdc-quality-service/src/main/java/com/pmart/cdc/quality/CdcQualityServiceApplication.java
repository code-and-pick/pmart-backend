package com.pmart.cdc.quality;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * cdc-quality-service. 책임·비소유는 services/cdc-quality-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class CdcQualityServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CdcQualityServiceApplication.class, args);
  }
}
