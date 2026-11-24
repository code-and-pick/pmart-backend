package com.pmart.cdc.outbound;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * cdc-outbound-service. 책임·비소유는 services/cdc-outbound-service/CLAUDE.md 와
 * docs/architecture/service-map.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CdcOutboundServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CdcOutboundServiceApplication.class, args);
  }
}
