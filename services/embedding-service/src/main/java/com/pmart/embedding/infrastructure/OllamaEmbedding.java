package com.pmart.embedding.infrastructure;

import com.pmart.embedding.application.EmbeddingProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * LAN Ollama 임베딩(Spring AI {@link OllamaApi}). {@code /api/embed} 를 {@link OllamaApi#embed} 로 직접
 * 부른다 — Spring AI {@code OllamaEmbeddingModel}(2.0.2)은 요청에 options·keep_alive 를 싣지 않아 CPU
 * 지정(num_gpu 0)·문맥 길이가 Ollama 에 가지 않는다 (ai-assistant-service 에서 2026-11-09 확인한 것과 같은 판). 모델은 받아 오지
 * 않는다(운영자가 {@code ollama pull}).
 */
public final class OllamaEmbedding implements EmbeddingModel {

  private final OllamaApi api;
  private final String model;
  private final String keepAlive;
  private final Map<String, Object> options;

  public OllamaEmbedding(EmbeddingProperties.Ollama o) {
    Duration connect = o.connectTimeout() == null ? Duration.ofSeconds(5) : o.connectTimeout();
    Duration request = o.requestTimeout() == null ? Duration.ofSeconds(120) : o.requestTimeout();
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connect)
            .build();
    JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(client);
    f.setReadTimeout(request);
    this.api =
        OllamaApi.builder()
            .baseUrl(o.baseUrl())
            .restClientBuilder(RestClient.builder().requestFactory(f))
            .build();
    this.model = o.model();
    this.keepAlive = o.keepAlive() == null ? "24h" : o.keepAlive();
    Map<String, Object> opts = new LinkedHashMap<>();
    if (o.numGpu() != null) {
      opts.put("num_gpu", o.numGpu());
    }
    if (o.numCtx() != null) {
      opts.put("num_ctx", o.numCtx());
    }
    this.options = Map.copyOf(opts);
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<String> inputs = request.getInstructions();
    var res =
        api.embed(new OllamaApi.EmbeddingsRequest(model, inputs, keepAlive, options, true, null));
    if (res == null || res.embeddings() == null || res.embeddings().size() != inputs.size()) {
      throw new IllegalStateException("Ollama 임베딩 응답 개수가 요청과 다름");
    }
    List<Embedding> out = new ArrayList<>();
    for (int i = 0; i < res.embeddings().size(); i++) {
      out.add(new Embedding(res.embeddings().get(i), i));
    }
    return new EmbeddingResponse(out);
  }

  @Override
  public float[] embed(Document document) {
    return embed(document.getText() == null ? "" : document.getText());
  }
}
