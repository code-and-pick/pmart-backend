package com.pmart.embedding.application;

import com.pmart.embedding.domain.Scope;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 임베딩 일 줄: 스레드 하나(Ollama 는 PC 2 CPU 를 다른 업무와 나눠 쓴다 — 동시에 여러 건 보내지 않는다). 같은 (범위, 문서)가 이미 기다리면 다시 넣지
 * 않는다. 실패는 기록만 — 다음 맞춰 보기({@link Reconciler})가 다시 넣는다.
 */
@Component
public class EmbeddingQueue implements DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(EmbeddingQueue.class);

  private final DocumentEmbedding embedding;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "embedding-worker"));
  private final Set<String> pending = ConcurrentHashMap.newKeySet();

  public EmbeddingQueue(DocumentEmbedding embedding) {
    this.embedding = embedding;
  }

  /** 넣었으면 true(이미 기다리면 false). */
  public boolean enqueue(Scope scope, String documentId) {
    String key = scope + "|" + documentId;
    if (!pending.add(key)) {
      return false;
    }
    worker.execute(
        () -> {
          pending.remove(key);
          try {
            embedding.sync(scope, documentId);
          } catch (RuntimeException e) {
            log.warn(
                "문서 벡터 맞춤 실패(다음 맞춰 보기에서 다시) scope={} path={} cause={}",
                scope,
                documentId,
                e.toString());
          }
        });
    return true;
  }

  public int waiting() {
    return pending.size();
  }

  @Override
  public void destroy() {
    worker.shutdownNow();
  }
}
