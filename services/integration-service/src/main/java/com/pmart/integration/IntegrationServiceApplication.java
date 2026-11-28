package com.pmart.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * integration-service — 문서 저장소(GitHub {@code code-and-pick/pmart-all-docs}) 웹훅 흐름(ADR-0095).
 * ES-First 하이브리드 RAG 의 적재 입구: 바뀐 파일을 가져와 내용을 추출하고 청킹 서비스로 넘긴다. 책임은
 * services/integration-service/CLAUDE.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IntegrationServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(IntegrationServiceApplication.class, args);
  }
}
