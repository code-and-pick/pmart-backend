package com.pmart.embedding.application;

import com.pmart.embedding.domain.Scope;
import com.pmart.embedding.infrastructure.DescribedEmbedding;
import com.pmart.embedding.infrastructure.VectorStore;
import com.pmart.embedding.infrastructure.VectorStore.VectorHit;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 질문 벡터 검색(ADR-0099 — rag-query-service 가 ES 로 끝내지 못한 질문에 부른다). 질문에 범위별 지시문을 붙여 같은 모델·판으로 임베딩하고 자기
 * pgvector 에서 가까운 청크를 찾는다 — 모델과 벡터 저장소의 주인이 이 서비스라 질문 임베딩도 여기서 한다(문서·질문 벡터가 어긋나지 않게).
 */
@Service
public class VectorSearch {

  /** 결과: 쓴 모델·판과 청크 목록(코사인 높은 순). */
  public record Result(String model, String version, List<VectorHit> hits, long embedMillis) {}

  private final DescribedEmbedding embedding;
  private final VectorStore store;
  private final EmbeddingProperties props;

  public VectorSearch(DescribedEmbedding embedding, VectorStore store, EmbeddingProperties props) {
    this.embedding = embedding;
    this.store = store;
    this.props = props;
  }

  public Result search(Scope scope, String question, int size) {
    long t = System.nanoTime();
    float[] v = embedding.embedAll(List.of(props.queryInstruction(scope.name()) + question)).get(0);
    long ms = (System.nanoTime() - t) / 1_000_000;
    return new Result(
        embedding.name(),
        embedding.version(),
        store.nearest(scope, v, Math.max(1, Math.min(size, 50))),
        ms);
  }
}
