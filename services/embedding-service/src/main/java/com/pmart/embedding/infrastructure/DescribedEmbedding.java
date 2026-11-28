package com.pmart.embedding.infrastructure;

import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Spring AI {@link EmbeddingModel} + 모델 이름·판(캐시 키·저장 기록). 문서 쪽은 글 그대로 넣는다(질문 쪽 지시문은 다음 단계 검색 서비스가
 * 붙인다).
 *
 * @param model 임베딩 모델
 * @param name 모델 이름(예: ollama:qwen3-embedding:0.6b)
 * @param version 모델 판
 */
public record DescribedEmbedding(EmbeddingModel model, String name, String version) {

  /** 여러 글을 한 번에. 응답 개수가 다르면 예외. */
  public List<float[]> embedAll(List<String> texts) {
    List<float[]> out = model.embed(texts);
    if (out.size() != texts.size()) {
      throw new IllegalStateException("임베딩 응답 개수가 요청과 다름");
    }
    return out;
  }
}
