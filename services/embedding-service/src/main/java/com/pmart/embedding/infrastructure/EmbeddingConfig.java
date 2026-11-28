package com.pmart.embedding.infrastructure;

import com.pmart.embedding.application.EmbeddingProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 임베딩 모델 고르기: {@code ollama}(LAN) / {@code fake}(시험·로컬). */
@Configuration
class EmbeddingConfig {

  @Bean
  DescribedEmbedding describedEmbedding(EmbeddingProperties props) {
    if ("ollama".equals(props.provider())) {
      EmbeddingProperties.Ollama o = props.ollama();
      if (o == null) {
        throw new IllegalStateException(
            "pmart.embedding.provider=ollama 인데 pmart.embedding.ollama 설정이 없음");
      }
      return new DescribedEmbedding(new OllamaEmbedding(o), "ollama:" + o.model(), o.version());
    }
    if ("fake".equals(props.provider())) {
      return new DescribedEmbedding(new FakeEmbedding(props.dim()), "fake", "1");
    }
    throw new IllegalStateException("알 수 없는 pmart.embedding.provider: " + props.provider());
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
