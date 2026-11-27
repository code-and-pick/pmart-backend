package com.pmart.assistant.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * 답변 종료 지점 지표(2026-11-27 ES-First 계획 P1 ① — 사용자 "검색 종료 판정 기준과 답변 검증 문제를 우선 측정"). 질문 원문·답·문서 본문은 싣지
 * 않는다 — 꼬리표는 정해진 이름뿐(종료·시도 결과·검사 규칙).
 *
 * <ul>
 *   <li>{@code pmart_assistant_answers_total{exit}} —
 *       앞단(REALTIME_DATA_REQUIRED·OUT_OF_SCOPE·PRODUCT_INFO), 문서 경로({@link AnswerGenerator.Exit}),
 *       실패(FAILED_&lt;코드&gt;)
 *   <li>{@code pmart_assistant_generation_attempts_total{result}} — 생성 시도마다
 *       PASS·JSON_INVALID·UNGROUNDED·CHECK_FAILED
 *   <li>{@code pmart_assistant_citation_violations_total{rule}} — 검사에 걸린 규칙(한 시도에 여럿이면 각각)
 *   <li>{@code pmart_assistant_top_similarity{exit}} — 1위 근거 혼합 유사도 분포(임계값·발췌 하한을 맞추는 바탕)
 * </ul>
 */
public class AnswerMetrics {

  private final @Nullable MeterRegistry registry;

  public AnswerMetrics(@Nullable MeterRegistry registry) {
    this.registry = registry;
  }

  /** 지표 없이(시험). */
  public static AnswerMetrics none() {
    return new AnswerMetrics(null);
  }

  public void exit(String exit) {
    if (registry != null) {
      Counter.builder("pmart.assistant.answers")
          .description("답변 종료 지점")
          .tag("exit", exit)
          .register(registry)
          .increment();
    }
  }

  /** 문서 경로 결과 — 종료·시도마다 결과·검사 규칙·1위 유사도. */
  public void generated(AnswerGenerator.Generated g) {
    exit(g.exit().name());
    if (registry == null) {
      return;
    }
    for (String a : g.attempts()) {
      String result =
          switch (a) {
            case "PASS", "JSON_INVALID", "UNGROUNDED" -> a;
            default -> a.startsWith("CUSTOMER_") ? "CUSTOMER_CHECK_FAILED" : "CHECK_FAILED";
          };
      Counter.builder("pmart.assistant.generation.attempts")
          .description("생성 시도 결과")
          .tag("result", result)
          .register(registry)
          .increment();
      if (result.equals("CHECK_FAILED") || result.equals("CUSTOMER_CHECK_FAILED")) {
        Arrays.stream(a.split(","))
            .filter(r -> !r.isBlank())
            .forEach(
                r ->
                    Counter.builder("pmart.assistant.citation.violations")
                        .description("답변 검사에 걸린 규칙")
                        .tag("rule", r)
                        .register(registry)
                        .increment());
      }
    }
    if (g.topSimilarity() != null) {
      DistributionSummary.builder("pmart.assistant.top.similarity")
          .description("1위 근거 혼합 유사도")
          .tag("exit", g.exit().name())
          .serviceLevelObjectives(0.40, 0.425, 0.45, 0.475, 0.50, 0.55, 0.60, 0.70)
          .register(registry)
          .record(g.topSimilarity());
    }
  }
}
