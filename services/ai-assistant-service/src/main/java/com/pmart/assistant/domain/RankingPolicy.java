package com.pmart.assistant.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 순위 규칙(2차 검토 5). 임계값을 넘은 후보끼리만 {@code 유사도 + 지위 가중 + 우선순위 등급 가중} 으로 정렬한다. 임계값 미만은 지위로 끌어올리지 않는다(먼저
 * 걸러낸다).
 *
 * @param threshold 혼합 유사도 하한
 * @param authorityWeights 지위별 가중(확정 > 채택 설계 > 설계안 > 가정 > 미결정·외부 참고)
 * @param precedenceWeight 우선순위 등급(domain-map.md:35) 1단계당 가중
 */
public record RankingPolicy(
    double threshold, Map<Authority, Double> authorityWeights, double precedenceWeight) {

  /**
   * 목적 조항("제N조 (목적)")은 내용이 없어 1위 근거가 되면 "처리 기준이 적용됩니다" 같은 빈 답이 나온다(2026-11-28 고객 평가 C13·C15·C17) —
   * 정렬 점수에서 뺀다. 임계값 판정(유사도)은 그대로라 다른 근거가 없으면 그대로 쓰인다.
   */
  public static final double PURPOSE_PENALTY = 0.2;

  public static Map<Authority, Double> defaultWeights() {
    return Map.of(
        Authority.CONFIRMED, 0.06,
        Authority.ADOPTED_DESIGN, 0.04,
        Authority.PROPOSAL, 0.02,
        Authority.ASSUMPTION, 0.01,
        Authority.UNDECIDED, 0.0,
        Authority.EXTERNAL_REFERENCE, 0.0);
  }

  public List<RetrievedChunk> rank(List<RetrievedChunk> candidates, int topK) {
    return candidates.stream()
        .filter(c -> c.similarity() >= threshold)
        .map(
            c ->
                c.withFinalScore(
                    c.similarity()
                        + authorityWeights.getOrDefault(c.authority(), 0.0)
                        + precedenceWeight * c.precedenceRank()
                        - (AnswerCompleteness.purposeSection(c.section()) ? PURPOSE_PENALTY : 0)))
        .sorted(Comparator.comparingDouble(RetrievedChunk::finalScore).reversed())
        .limit(topK)
        .toList();
  }
}
