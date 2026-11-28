package com.pmart.integration.application;

import com.pmart.integration.domain.ExtractedDocument;
import com.pmart.integration.domain.FileChange;
import com.pmart.integration.infrastructure.ChunkingSender;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.integration.channel.ExecutorChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.handler.advice.RequestHandlerRetryAdvice;
import org.springframework.integration.http.HttpHeaders;
import org.springframework.integration.http.dsl.Http;
import org.springframework.messaging.MessageChannel;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 문서 저장소 웹훅 흐름(Spring Integration, ADR-0095) — ES-First 하이브리드 RAG 의 적재 입구.
 *
 * <pre>
 * [입구] HTTP POST /api/v1/internal/docs-webhook (웹훅 중계 → 이 서비스)
 *   → WebhookReceiver: 서명 · 이벤트(push/delete/ping) · 저장소 · 브랜치 → 바뀐 파일 목록
 *   ├─(wireTap) 파일 목록 → split → docChanges(비동기)
 *   └─ GitHub 에 바로 답(202 등)
 * [docChanges] 파일 하나씩
 *   → filter: 추출 대상(확장자·README 제외)
 *   → ContentExtractor: GitHub API 로 그 커밋의 판을 읽어 내용 추출(삭제면 본문 없음)   ← 다시 시도
 *   → ChunkingSender: 청킹 서비스로 보냄(주소 없으면 기록만)                              ← 다시 시도
 *   (끝내 실패 → errorChannel 에 기록)
 * </pre>
 */
@Configuration
public class DocsWebhookFlow {

  public static final String PATH = "/api/v1/internal/docs-webhook";
  public static final String DOC_CHANGES = "docChanges";

  private static final Logger log = LoggerFactory.getLogger(DocsWebhookFlow.class);

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** 파일 처리 채널 — 스레드 하나라 push 가 받은 차례대로 처리된다. */
  @Bean(name = DOC_CHANGES)
  MessageChannel docChanges() {
    return new ExecutorChannel(Executors.newSingleThreadExecutor(r -> new Thread(r, "docs-flow")));
  }

  @Bean
  IntegrationFlow docsWebhookInbound(WebhookReceiver receiver) {
    return IntegrationFlow.from(
            Http.inboundGateway(PATH)
                .requestMapping(m -> m.methods(HttpMethod.POST))
                .requestPayloadType(byte[].class)
                .mappedRequestHeaders("x-github-*", "x-hub-signature-256"))
        .handle(byte[].class, (body, headers) -> receiver.receive(body, headers))
        .wireTap(
            tap ->
                tap.filter(WebhookReceiver.Reply.class, r -> !r.changes().isEmpty())
                    .split(WebhookReceiver.Reply.class, WebhookReceiver.Reply::changes)
                    .channel(DOC_CHANGES))
        .enrichHeaders(
            h ->
                h.headerFunction(
                    HttpHeaders.STATUS_CODE,
                    m -> ((WebhookReceiver.Reply) m.getPayload()).status()))
        .transform(WebhookReceiver.Reply.class, WebhookReceiver.Reply::body)
        .get();
  }

  @Bean
  IntegrationFlow docsProcessing(
      DocsProperties props, ContentExtractor extractor, ChunkingSender sender) {
    return IntegrationFlow.from(DOC_CHANGES)
        .filter(
            FileChange.class,
            c -> props.included(c.path()),
            f ->
                f.discardFlow(
                    d ->
                        d.handle(
                            m ->
                                log.debug(
                                    "추출 대상 아님 path={}", ((FileChange) m.getPayload()).path()))))
        .handle(FileChange.class, (c, h) -> extractor.extract(c), e -> e.advice(retry(props)))
        .handle(
            ExtractedDocument.class,
            (d, h) -> {
              sender.send(d);
              return null;
            },
            e -> e.advice(retry(props)))
        .get();
  }

  static RequestHandlerRetryAdvice retry(DocsProperties props) {
    RequestHandlerRetryAdvice a = new RequestHandlerRetryAdvice();
    a.setRetryPolicy(RetryPolicy.builder().maxRetries(props.retries()).build());
    ExponentialBackOff b = new ExponentialBackOff(1000, 2.0);
    b.setMaxInterval(Duration.ofSeconds(30).toMillis());
    a.setBackOff(b);
    return a;
  }
}
