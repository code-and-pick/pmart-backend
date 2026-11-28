package com.pmart.chunking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * chunking-service — 문서 청킹 → Elasticsearch RAG 색인(ADR-0096). ES-First 하이브리드 RAG 의 "청킹 → ES 선 적재".
 * 책임은 services/chunking-service/CLAUDE.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ChunkingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(ChunkingServiceApplication.class, args);
  }
}
