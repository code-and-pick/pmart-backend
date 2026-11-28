package com.pmart.ragquery.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ES 에서 끝내도 되는지 판정(ADR-0099) — CPU 규칙, 모델 없음. ES-First 2차
 * 검증(docs/rag/verification/2026-11-28/es-first-v2)의 {@code es_first_eval2.py}·{@code analyze2.py} 와
 * 같은 지표·같은 규칙이다(바꾸면 둘을 같이 바꾸고 회귀셋으로 다시 잰다).
 *
 * <p>지표(질문 낱말 = ES rag_nori 분석기 토큰 중 두 글자 이상 또는 숫자):
 *
 * <ul>
 *   <li>top1·gap·relGap: BM25 <b>원래 점수</b>(가중치 반영 전) 1위·1·2위 차·상대 차 — 가중치로 커진 차이를 답변 가능성으로 읽지 않는다
 *   <li>coverage: 질문 낱말 중 1위 청크(제목 경로 + 본문)에 있는 비율 — <b>규칙에 씀</b>
 *   <li>titleMatch: 질문 낱말 중 1위 청크 제목 경로에 있는 비율(기록만)
 *   <li>phraseMatch: 질문의 이웃한 낱말 쌍 중 1위 청크에서도 이웃한 비율(기록만 — 다음 검증에서 효과를 잰다)
 *   <li>faqQuestionMatch: 1위가 FAQ 면 질문 낱말과 FAQ 질문 문장 낱말의 Jaccard(기록만)
 * </ul>
 *
 * 규칙(설정): coverage ≥ minCoverage 그리고 relGap ≥ minRelGap → ES 에서 끝(상위 N 카드). 2차 검증에서 '1위 하나만 돌려주기'로는
 * 잘못 끝냄 0 규칙이 없었다 — 카드 수는 설정.
 */
public final class SearchConfidenceEvaluator {

  /** 지표. */
  public record Features(
      double top1,
      double gap,
      double relGap,
      double coverage,
      double titleMatch,
      double phraseMatch,
      double faqQuestionMatch) {}

  /** 판정 결과와 이유. */
  public record Decision(boolean acceptAtEs, String reason, Features features) {}

  private final double minCoverage;
  private final double minRelGap;

  public SearchConfidenceEvaluator(double minCoverage, double minRelGap) {
    this.minCoverage = minCoverage;
    this.minRelGap = minRelGap;
  }

  /** 질문 낱말로 쓰는 토큰인가(두 글자 이상 또는 숫자 한 글자 — 평가 스크립트와 같게). */
  public static boolean keep(String token) {
    return token.length() > 1 || (token.length() == 1 && Character.isDigit(token.charAt(0)));
  }

  /**
   * @param rawHits BM25 결과(원래 점수 높은 순)
   * @param question 질문 토큰(분석기 순서)
   * @param top1Text 1위 청크(제목 경로 + 줄바꿈 + 본문) 토큰(순서)
   * @param top1Section 1위 청크 제목 경로 토큰
   * @param top1Heading 1위 청크 절 제목(FAQ 질문) 토큰
   */
  public Features features(
      List<SearchHit> rawHits,
      List<String> question,
      List<String> top1Text,
      List<String> top1Section,
      List<String> top1Heading) {
    if (rawHits.isEmpty()) {
      return new Features(0, 0, 0, 0, 0, 0, 0);
    }
    double s1 = rawHits.get(0).raw();
    double s2 = rawHits.size() > 1 ? rawHits.get(1).raw() : 0.0;
    Set<String> q = filtered(question);
    Set<String> text = filtered(top1Text);
    Set<String> sec = filtered(top1Section);
    double coverage = ratio(q, text);
    double title = ratio(q, sec);
    double phrase = phrase(question, top1Text);
    double faq = 0;
    if ("FAQ".equals(rawHits.get(0).documentType())) {
      Set<String> h = filtered(top1Heading);
      Set<String> union = new HashSet<>(q);
      union.addAll(h);
      Set<String> inter = new HashSet<>(q);
      inter.retainAll(h);
      faq = union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }
    // 평가 스크립트처럼 소수 넷째 자리로 반올림한 값으로 비교한다(경계에서 판정이 갈리지 않게)
    return new Features(
        round(s1),
        round(s1 - s2),
        s1 == 0 ? 0 : round((s1 - s2) / s1),
        coverage,
        title,
        phrase,
        round(faq));
  }

  public Decision decide(Features f) {
    boolean accept = f.coverage() >= minCoverage && f.relGap() >= minRelGap;
    String reason =
        accept
            ? "coverage "
                + round(f.coverage())
                + " ≥ "
                + minCoverage
                + ", relGap "
                + round(f.relGap())
                + " ≥ "
                + minRelGap
            : (f.coverage() < minCoverage
                ? "coverage " + round(f.coverage()) + " < " + minCoverage
                : "relGap " + round(f.relGap()) + " < " + minRelGap);
    return new Decision(accept, reason, f);
  }

  private static Set<String> filtered(List<String> tokens) {
    Set<String> out = new LinkedHashSet<>();
    for (String t : tokens) {
      if (keep(t)) {
        out.add(t);
      }
    }
    return out;
  }

  private static double ratio(Set<String> q, Set<String> in) {
    if (q.isEmpty()) {
      return 0;
    }
    int n = 0;
    for (String t : q) {
      if (in.contains(t)) {
        n++;
      }
    }
    return round((double) n / q.size());
  }

  /** 질문의 이웃한 (남긴) 낱말 쌍 중 본문에서도 이웃한 비율. */
  static double phrase(List<String> question, List<String> text) {
    List<String> q = new ArrayList<>();
    for (String t : question) {
      if (keep(t)) {
        q.add(t);
      }
    }
    if (q.size() < 2) {
      return 0;
    }
    Set<String> pairs = new HashSet<>();
    String prev = null;
    for (String t : text) {
      if (!keep(t)) {
        continue;
      }
      if (prev != null) {
        pairs.add(prev + "\u0001" + t);
      }
      prev = t;
    }
    int n = 0;
    for (int i = 0; i + 1 < q.size(); i++) {
      if (pairs.contains(q.get(i) + "\u0001" + q.get(i + 1))) {
        n++;
      }
    }
    return round((double) n / (q.size() - 1));
  }

  private static double round(double v) {
    return Math.round(v * 10000) / 10000.0;
  }
}
