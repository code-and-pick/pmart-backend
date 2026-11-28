package com.pmart.embedding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * embedding-service — ES 청크 → Ollama 임베딩 → pgvector(ADR-0098). ES-First 하이브리드 RAG 의 벡터 쪽 적재. 책임은
 * services/embedding-service/CLAUDE.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class EmbeddingServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(EmbeddingServiceApplication.class, args);
  }
}
