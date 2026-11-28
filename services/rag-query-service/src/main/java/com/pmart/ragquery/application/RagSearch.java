package com.pmart.ragquery.application;

import com.pmart.ragquery.domain.SearchConfidenceEvaluator;
import com.pmart.ragquery.domain.SearchConfidenceEvaluator.Decision;
import com.pmart.ragquery.domain.SearchHit;
import com.pmart.ragquery.infrastructure.EsRag;
import com.pmart.ragquery.infrastructure.VectorClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 질문 검색 흐름(ADR-0099, ES-First 계획 §2-1):
 *
 * <ol>
 *   <li>ES BM25(범위 색인) — 운영자면 POLICY 점수 ×policyBoost 로 다시 정렬(정렬에만).
 *   <li>{@link SearchConfidenceEvaluator} 가 <b>원래 점수</b>로 판정 — 안전하면 <b>ES 에서 끝</b>(상위 cards 카드, 모델
 *       호출 없음).
 *   <li>아니면 embedding-service 벡터 검색 → BM25 와 RRF → 벡터 1위 코사인 &lt; noAnswerCosine 이면 <b>답변 불가</b>,
 *       아니면 상위 cards 근거(③ 생성은 아직 없음 — 근거만 돌려준다).
 * </ol>
 *
 * 질문 원문은 로그에 남기지 않는다(경로·판정·건수·시간만).
 */
@Service
public class RagSearch {

  private static final Logger log = LoggerFactory.getLogger(RagSearch.class);

  /** 끝난 곳. */
  public enum Exit {
    ES,
    HYBRID,
    NO_ANSWER
  }

  /** 결과. */
  public record Result(
      Exit exit,
      List<SearchHit> evidence,
      Decision decision,
      @Nullable Double vectorTop1Cosine,
      int embeddingCalls,
      Map<String, Long> millis) {}

  private final EsRag es;
  private final VectorClient vectors;
  private final RagQueryProperties props;
  private final SearchConfidenceEvaluator evaluator;

  public RagSearch(EsRag es, VectorClient vectors, RagQueryProperties props) {
    this.es = es;
    this.vectors = vectors;
    this.props = props;
    this.evaluator = new SearchConfidenceEvaluator(props.minCoverage(), props.minRelGap());
  }

  /**
   * @param scope OPERATOR 또는 CUSTOMER
   */
  public Result search(String scope, String question) {
    String alias = "OPERATOR".equals(scope) ? "rag-operator" : "rag-customer";
    Map<String, Long> ms = new LinkedHashMap<>();
    long t0 = System.nanoTime();
    List<SearchHit> raw = es.bm25(alias, question, props.candidates());
    List<SearchHit> ranked = boost(raw, scope);
    Decision d;
    if (raw.isEmpty()) {
      d = evaluator.decide(evaluator.features(raw, List.of(), List.of(), List.of(), List.of()));
    } else {
      SearchHit top = raw.get(0);
      d =
          evaluator.decide(
              evaluator.features(
                  raw,
                  es.analyze(alias, question),
                  es.analyze(alias, es.chunkText(alias, top.chunkId())),
                  es.analyze(alias, top.sectionPath()),
                  es.analyze(alias, top.heading())));
    }
    ms.put("es", (System.nanoTime() - t0) / 1_000_000);
    if (d.acceptAtEs()) {
      log.info("RAG 검색 끝=ES scope={} 판정={} ms={}", scope, d.reason(), ms);
      return new Result(Exit.ES, top(ranked, props.cards()), d, null, 0, ms);
    }
    long t1 = System.nanoTime();
    VectorClient.Result v = vectors.search(alias, question, 10);
    ms.put("vector", (System.nanoTime() - t1) / 1_000_000);
    ms.put("embed", v.embedMillis());
    Double cos1 = v.hits().isEmpty() ? null : v.hits().get(0).raw();
    if (cos1 == null || cos1 < props.noAnswerCosine()) {
      log.info("RAG 검색 끝=답변불가 scope={} 판정={} cos1={} ms={}", scope, d.reason(), cos1, ms);
      return new Result(Exit.NO_ANSWER, List.of(), d, cos1, 1, ms);
    }
    List<SearchHit> fused = rrf(top(ranked, 10), v.hits(), props.rrfK());
    log.info("RAG 검색 끝=HYBRID scope={} 판정={} cos1={} ms={}", scope, d.reason(), cos1, ms);
    return new Result(Exit.HYBRID, top(fused, props.cards()), d, cos1, 1, ms);
  }

  List<SearchHit> boost(List<SearchHit> raw, String scope) {
    List<SearchHit> out = new ArrayList<>();
    for (SearchHit h : raw) {
      double f =
          "OPERATOR".equals(scope) && "POLICY".equals(h.documentType()) ? props.policyBoost() : 1.0;
      out.add(h.withScore(h.raw() * f));
    }
    out.sort(Comparator.comparingDouble(SearchHit::score).reversed());
    return out;
  }

  /** RRF — 같은 chunkId 는 한 줄(먼저 본 목록의 정보). */
  static List<SearchHit> rrf(List<SearchHit> a, List<SearchHit> b, int k) {
    Map<String, Double> score = new LinkedHashMap<>();
    Map<String, SearchHit> info = new LinkedHashMap<>();
    for (List<SearchHit> list : List.of(a, b)) {
      for (int i = 0; i < Math.min(10, list.size()); i++) {
        SearchHit h = list.get(i);
        score.merge(h.chunkId(), 1.0 / (k + i + 1), Double::sum);
        info.putIfAbsent(h.chunkId(), h);
      }
    }
    List<SearchHit> out = new ArrayList<>();
    score.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .forEach(e -> out.add(info.get(e.getKey()).withScore(e.getValue())));
    return out;
  }

  private static List<SearchHit> top(List<SearchHit> l, int n) {
    return List.copyOf(l.subList(0, Math.min(n, l.size())));
  }
}
