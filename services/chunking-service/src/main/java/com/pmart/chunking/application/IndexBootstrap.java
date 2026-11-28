package com.pmart.chunking.application;

import com.pmart.chunking.domain.AccessScope;
import com.pmart.chunking.infrastructure.RagIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 뜰 때 두 RAG 색인(별칭)을 만들어 둔다 — 검색하는 쪽이 문서가 아직 없어도 빈 색인을 볼 수 있게. ES 가 아직 없으면 경고만(문서가 올 때 다시 만든다). */
@Component
class IndexBootstrap {

  private static final Logger log = LoggerFactory.getLogger(IndexBootstrap.class);

  private final RagIndex index;

  IndexBootstrap(RagIndex index) {
    this.index = index;
  }

  @EventListener(ApplicationReadyEvent.class)
  void ensureIndexes() {
    for (AccessScope s : AccessScope.values()) {
      try {
        index.ensure(s);
      } catch (RuntimeException e) {
        log.warn("RAG 색인 준비 실패(문서가 올 때 다시 시도) alias={} cause={}", s.alias(), e.toString());
      }
    }
  }
}
