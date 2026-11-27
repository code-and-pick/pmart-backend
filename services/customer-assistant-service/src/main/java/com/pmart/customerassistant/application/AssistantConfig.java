package com.pmart.customerassistant.application;

import com.pmart.customerassistant.domain.CitationVerifier;
import com.pmart.customerassistant.infrastructure.AnswerStore;
import com.pmart.customerassistant.infrastructure.OllamaModels;
import com.pmart.customerassistant.infrastructure.SnapshotStore;
import com.pmart.customerassistant.infrastructure.embedding.DescribedEmbeddingModel;
import com.pmart.customerassistant.infrastructure.embedding.LocalHashEmbeddingModel;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeIngestor;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 모델 제공자 연결. {@code pmart.assistant.model.provider}:
 *
 * <ul>
 *   <li>{@code local}(기본): 해시 임베딩 + 발췌 답변 — 외부 호출 없음, 기준선.
 *   <li>{@code ollama}: LAN Ollama 임베딩(Qwen3-Embedding-0.6B) + 생성 답변(인용 검사 실패 시 발췌) — ADR-0051. 외부
 *       유료 API 는 쓰지 않는다.
 *   <li>{@code fake}(시험): 시험이 주입한 {@link ChatModel} 로 생성 경로(인용 검사·재생성·발췌 전환)를 돈다.
 * </ul>
 *
 * 기동 검사: 하드 타임아웃 &lt; lease, 스냅숏 정리 유예 ≥ lease×2, ollama 는 모델 이름·버전·주소가 있고 호출 시간 제한 &lt; 하드 타임아웃.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfig {

  @Bean
  @ConditionalOnMissingBean
  Clock assistantClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  DescribedEmbeddingModel embeddingModel(
      AssistantProperties props, ObjectProvider<RestClient.Builder> rest) {
    if ("ollama".equals(props.getModel().getProvider())) {
      validateOllama(props);
      var o = props.getModel().getOllama();
      return OllamaModels.embedding(
          OllamaModels.api(o, rest.getIfAvailable(RestClient::builder)),
          o,
          props.getModel().getEmbeddingDim());
    }
    return new LocalHashEmbeddingModel(props.getModel().getEmbeddingDim());
  }

  @Bean
  @ConditionalOnMissingBean
  AnswerGenerator answerGenerator(
      AssistantProperties props,
      ObjectProvider<ChatModel> chatModel,
      ObjectProvider<RestClient.Builder> rest,
      ObjectMapper json,
      com.pmart.customerassistant.infrastructure.embedding.DescribedEmbeddingModel embeddings) {
    var r = props.getRetrieval();
    ExtractiveAnswerGenerator extractive =
        new ExtractiveAnswerGenerator(r.getMaxAnswerChars(), r.getTopK());
    String provider = props.getModel().getProvider();
    if ("local".equals(provider)) {
      return extractive;
    }
    if ("ollama".equals(provider)) {
      validateOllama(props);
      var o = props.getModel().getOllama();
      return new GenerativeAnswerGenerator(
          OllamaModels.chat(OllamaModels.api(o, rest.getIfAvailable(RestClient::builder)), o),
          "ollama:" + o.getChatModel(),
          // 인용 검사: bigram 이 모자란 문장은 의미 유사도로 다시 본다(2026-11-21)
          new CitationVerifier(
              r.getMinSentenceOverlap(),
              r.getMinSentenceSimilarity(),
              new EmbeddingSentenceSimilarity(embeddings)),
          extractive,
          json,
          r::getFallbackMinSimilarity);
    }
    if ("fake".equals(provider)) {
      ChatModel chat = chatModel.getIfAvailable();
      if (chat == null) {
        throw new IllegalStateException("provider=fake 인데 ChatModel 빈이 없습니다(시험 전용).");
      }
      return new GenerativeAnswerGenerator(
          chat,
          "fake-chat",
          new CitationVerifier(r.getMinSentenceOverlap()),
          extractive,
          json,
          r::getFallbackMinSimilarity);
    }
    throw new IllegalStateException(
        "지원하지 않는 pmart.assistant.model.provider: " + provider + " (local|ollama|fake)");
  }

  /** 처리 실행기 — 작업마다 가상 스레드, 호출 스레드의 관측(trace)·MDC 를 넘긴다. */
  @Bean(destroyMethod = "shutdownNow")
  ExecutorService assistantExecutor() {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(),
        () -> ContextSnapshotFactory.builder().build().captureAll());
  }

  @Bean
  CustomerQuestionService customerQuestionService(
      AnswerStore answers,
      SnapshotStore snapshots,
      Retriever retriever,
      AnswerGenerator generator,
      DescribedEmbeddingModel embeddings,
      AccessPolicy access,
      AssistantProperties props,
      ExecutorService assistantExecutor,
      ObjectMapper json,
      Clock clock,
      com.pmart.customerassistant.infrastructure.OrderClient orders,
      com.pmart.customerassistant.infrastructure.ProductSearchClient products,
      org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry>
          meters) {
    validate(props);
    return new CustomerQuestionService(
        answers,
        snapshots,
        retriever,
        generator,
        embeddings,
        access,
        props,
        assistantExecutor,
        json,
        clock,
        orders,
        products,
        new AnswerMetrics(meters.getIfAvailable()));
  }

  @Bean
  KnowledgeIngestor knowledgeIngestor(
      DataSource dataSource,
      SnapshotStore snapshots,
      DescribedEmbeddingModel embeddings,
      AssistantProperties props,
      Clock clock) {
    return new KnowledgeIngestor(
        dataSource,
        snapshots,
        embeddings,
        new GitSourceRevision(),
        clock,
        props.getRetrieval().getMaxChunkChars());
  }

  /** provider=ollama 필수값 — 없으면 기동하지 않는다(다른 모델로 대체하지 않음). */
  static void validateOllama(AssistantProperties props) {
    var o = props.getModel().getOllama();
    // 순서 고정(설정 파일 순) — 빠진 첫 항목을 알린다
    String[][] required = {
      {"base-url", o.getBaseUrl()},
      {"embedding-model", o.getEmbeddingModel()},
      {"embedding-version", o.getEmbeddingVersion()},
      {"chat-model", o.getChatModel()}
    };
    for (String[] e : required) {
      if (e[1] == null || e[1].isBlank()) {
        throw new IllegalStateException(
            "pmart.assistant.model.ollama." + e[0] + " 가 없습니다(provider=ollama).");
      }
    }
    if (o.getRequestTimeout().compareTo(props.getProcessing().getTimeout()) >= 0) {
      throw new IllegalStateException(
          "pmart.assistant.model.ollama.request-timeout 은 processing.timeout 보다 짧아야 합니다.");
    }
  }

  static void validate(AssistantProperties props) {
    var p = props.getProcessing();
    if (p.getTimeout().compareTo(p.getLease()) >= 0) {
      throw new IllegalStateException(
          "pmart.assistant.processing.timeout("
              + p.getTimeout()
              + ") 는 lease("
              + p.getLease()
              + ") 보다 짧아야 합니다.");
    }
    if (props.getIngest().getPruneGrace().compareTo(p.getLease().multipliedBy(2)) < 0) {
      throw new IllegalStateException("pmart.assistant.ingest.prune-grace 는 lease×2 이상이어야 합니다.");
    }
  }
}
