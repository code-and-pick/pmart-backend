package com.pmart.cdc.inbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * cdc-inbound-service. 책임·비소유는 services/cdc-inbound-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
public class CdcInboundServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CdcInboundServiceApplication.class, args);
  }
}
