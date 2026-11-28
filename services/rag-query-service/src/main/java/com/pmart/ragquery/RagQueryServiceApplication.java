package com.pmart.ragquery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** rag-query-service — 새 RAG 질문 검색(ADR-0099). 책임은 services/rag-query-service/CLAUDE.md. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RagQueryServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(RagQueryServiceApplication.class, args);
  }
}
