package com.pmart.embedding.application;

import com.pmart.embedding.domain.Scope;
import com.pmart.embedding.infrastructure.EsChunks;
import com.pmart.embedding.infrastructure.VectorStore;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 맞춰 보기: ES 의 ACTIVE 문서(판 해시)와 pgvector 의 ACTIVE 문서를 비교해 다른 문서를 일 줄에 넣는다 — 알림을 놓쳤거나(이 서비스가 꺼져 있던
 * 동안) 임베딩이 실패한 문서를 채운다. 뜰 때 한 번, 그 뒤 {@code reconcile-interval} 마다.
 */
@Component
public class Reconciler {

  private static final Logger log = LoggerFactory.getLogger(Reconciler.class);

  private final EsChunks es;
  private final VectorStore store;
  private final EmbeddingQueue queue;

  public Reconciler(EsChunks es, VectorStore store, EmbeddingQueue queue) {
    this.es = es;
    this.store = store;
    this.queue = queue;
  }

  @EventListener(ApplicationReadyEvent.class)
  void onStart() {
    reconcileQuietly();
  }

  @Scheduled(
      initialDelayString = "${pmart.embedding.reconcile-interval:PT10M}",
      fixedDelayString = "${pmart.embedding.reconcile-interval:PT10M}")
  void scheduled() {
    reconcileQuietly();
  }

  void reconcileQuietly() {
    try {
      int n = reconcile();
      if (n > 0) {
        log.info("맞춰 보기 — 다른 문서 {}건을 일 줄에 넣음", n);
      }
    } catch (RuntimeException e) {
      log.warn("맞춰 보기 실패(다음 주기에 다시) cause={}", e.toString());
    }
  }

  /** 일 줄에 넣은 문서 수. */
  public int reconcile() {
    int n = 0;
    for (Scope s : Scope.values()) {
      Map<String, String> inEs = es.activeDocuments(s);
      Map<String, String> inDb = store.activeDocuments(s);
      Set<String> docs = new HashSet<>(inEs.keySet());
      docs.addAll(inDb.keySet());
      for (String d : docs) {
        if (!Objects.equals(inEs.get(d), inDb.get(d)) && queue.enqueue(s, d)) {
          n++;
        }
      }
    }
    return n;
  }
}
