package com.pmart.customerassistant.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 생성 답변의 인용 검사.
 *
 * <ol>
 *   <li><b>ID 유효성(확실한 검사)</b>: 모델이 쓴 근거 ID 가 모두 이번 검색 결과에 있어야 하고 하나 이상이어야 한다.
 *   <li><b>문장 뒷받침</b>: 답 문장마다 근거와의 문자 bigram 겹침이 하한 이상이거나, 의미 유사도(임베딩, 있을 때)가 하한 이상(2026-11-21 — 바꿔
 *       말한 문장을 버리지 않게, 지어낸 문장은 둘 다 낮다).
 *   <li><b>숫자</b>(2026-11-21 다시 설계): 답 구절의 수마다, 그 구절을 가장 잘 덮는 숫자 있는 근거 단위(문장·표 행)에 같은 값이 있어야 한다 —
 *       근거 어딘가에 있는 다른 행의 값·숫자의 일부("200" 안의 "20")·목록 번호를 통과시키지 않는다. "4천"·"4,000" 은 같은 값.
 *   <li><b>부정</b>(2026-11-21 다시 설계): 답 구절이 근거 단위 하나와 분명히 짝지어질 때(덮는 비율이 높고 다음 단위보다 확실히 높음)만, 그 둘의
 *       긍정·부정이 같아야 한다. 짝이 분명하지 않으면 검사하지 않는다(바꿔 말한 답을 버리지 않게).
 * </ol>
 *
 * 통과해도 "근거로 검증됨"이 아니다. 주체 바꿔치기·잘못된 결합 같은 충실도 문제는 다 잡지 못한다(2026-11-21 평가 — 모델 재확인은 뒤로).
 *
 * <p>운영자용(ai-assistant)·고객용(customer-assistant)이 같은 코드를 쓴다 — 한쪽을 고치면 다른 쪽도.
 */
public final class CitationVerifier {

  public enum Violation {
    NO_CITATION,
    UNKNOWN_ID,
    UNSUPPORTED_SENTENCE,
    NEGATION_MISMATCH,
    NUMBER_MISMATCH,
    /** 근거 한 칸의 번호 항목 중 일부만 씀(2026-11-28 — {@link AnswerCompleteness}). */
    MISSING_ITEMS,
    /** 다른 정책·조항을 가리키기만 하고 내용이 없음(2026-11-28). */
    REFERRAL_ONLY
  }

  public record Result(boolean valid, List<Violation> violations) {}

  /**
   * 답 문장마다 근거 단위와의 최대 의미 유사도(같은 순서). 유사도를 잴 수 없으면(임베딩 장애 등) 예외를 던지지 않고 그 문장에 0 을 돌려준다 — 의미 검사 없이
   * bigram 결과대로 판단한다(답 전체를 의존성 장애로 만들지 않는다).
   */
  @FunctionalInterface
  public interface SentenceSimilarity {
    double[] best(List<String> sentences, List<String> evidenceUnits);
  }

  private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?。])\\s+|\\n+");
  private static final Pattern DOC_TAG = Pattern.compile("\\[?\\bD\\d+\\b\\]?");

  /** 부정 검사: 짝짓기 하한·다음 단위와의 차이·구절 최소 줄기 수. */
  static final double NEGATION_MIN_COVERAGE = 0.6;

  static final double NEGATION_MIN_MARGIN = 0.1;
  static final int NEGATION_MIN_STEMS = 3;

  private final double minOverlap;
  private final double minSimilarity;
  private final @Nullable SentenceSimilarity similarity;

  /**
   * @param minOverlap 문장 bigram 중 근거에 있는 비율의 하한(기본 0.5)
   */
  public CitationVerifier(double minOverlap) {
    this(minOverlap, 1.0, null);
  }

  /**
   * @param minSimilarity 의미 유사도 하한 — bigram 이 모자란 문장도 이 이상이면 뒷받침된 것으로 본다
   * @param similarity 없으면(해시 기준선) bigram 만
   */
  public CitationVerifier(
      double minOverlap, double minSimilarity, @Nullable SentenceSimilarity similarity) {
    this.minOverlap = minOverlap;
    this.minSimilarity = minSimilarity;
    this.similarity = similarity;
  }

  public Result verify(
      String answer, Collection<String> usedIds, Map<String, String> evidenceById) {
    List<Violation> violations = new ArrayList<>();
    if (usedIds.isEmpty()) {
      violations.add(Violation.NO_CITATION);
      return new Result(false, violations);
    }
    if (!evidenceById.keySet().containsAll(usedIds)) {
      violations.add(Violation.UNKNOWN_ID);
      return new Result(false, violations);
    }
    List<String> evidence = usedIds.stream().map(evidenceById::get).toList();
    String joined = String.join("\n", evidence);
    Set<String> evidenceBigrams = bigrams(joined);
    List<EvidenceAlignment.Unit> structured = new ArrayList<>();
    evidence.forEach(e -> structured.addAll(EvidenceAlignment.structuredUnits(e)));
    List<String> units = structured.stream().map(EvidenceAlignment.Unit::text).toList();

    List<String> sentences = new ArrayList<>();
    for (String raw : SENTENCE_SPLIT.split(answer)) {
      String s = DOC_TAG.matcher(raw).replaceAll("").strip();
      if (s.replaceAll("\\s", "").length() >= 8) {
        sentences.add(s);
      }
    }
    // 문장 뒷받침: bigram 이 모자란 문장만 의미 유사도로 다시 본다
    List<String> weak = new ArrayList<>();
    for (String s : sentences) {
      Set<String> sb = bigrams(s);
      long hit = sb.stream().filter(evidenceBigrams::contains).count();
      if (sb.isEmpty() || (double) hit / sb.size() < minOverlap) {
        weak.add(s);
      }
    }
    if (!weak.isEmpty()) {
      if (similarity == null || units.isEmpty()) {
        add(violations, Violation.UNSUPPORTED_SENTENCE);
      } else {
        double[] best = similarity.best(weak, units);
        for (double b : best) {
          if (b < minSimilarity) {
            add(violations, Violation.UNSUPPORTED_SENTENCE);
          }
        }
      }
    }
    for (String s : sentences) {
      for (String clause : EvidenceAlignment.clauses(s)) {
        if (!numbersSupported(clause, units)) {
          add(violations, Violation.NUMBER_MISMATCH);
        }
        if (negationMismatch(clause, structured)) {
          add(violations, Violation.NEGATION_MISMATCH);
        }
      }
    }
    // 빠진 사실(2026-11-28): 인용 검사는 틀린 말만 막는다 — 빠진 말·참조만 한 답을 따로 본다
    if (AnswerCompleteness.referralOnly(answer)) {
      add(violations, Violation.REFERRAL_ONLY);
    }
    if (AnswerCompleteness.missingInlineItems(DOC_TAG.matcher(answer).replaceAll(""), evidence)) {
      add(violations, Violation.MISSING_ITEMS);
    }
    return new Result(violations.isEmpty(), violations);
  }

  /**
   * 구절의 수마다: 숫자가 있는 근거 단위 중 구절 줄기를 가장 많이 덮는 단위(동점이면 그 모두)에 같은 값이 있어야 한다. 구절에 줄기가 없으면 근거 단위 어디에든 같은
   * 값이 있으면 된다.
   */
  static boolean numbersSupported(String clause, List<String> units) {
    List<Double> nums = EvidenceAlignment.numbers(clause);
    if (nums.isEmpty()) {
      return true;
    }
    Set<String> stems = EvidenceAlignment.stems(clause);
    List<String> numbered =
        units.stream().filter(u -> !EvidenceAlignment.numbers(u).isEmpty()).toList();
    for (double n : nums) {
      boolean ok;
      if (stems.isEmpty()) {
        ok = numbered.stream().anyMatch(u -> EvidenceAlignment.containsNumber(u, n));
      } else {
        double best =
            numbered.stream()
                .mapToDouble(u -> EvidenceAlignment.coverage(stems, u))
                .max()
                .orElse(0);
        ok =
            numbered.stream()
                .filter(u -> Math.abs(EvidenceAlignment.coverage(stems, u) - best) < 1e-9)
                .anyMatch(u -> EvidenceAlignment.containsNumber(u, n));
      }
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  /** 구절이 근거 단위 하나와 분명히 짝지어지고(덮는 비율·차이) 긍정·부정이 다르면 true. 짝이 분명하지 않으면 false(검사 안 함). */
  static boolean negationMismatch(String clause, List<EvidenceAlignment.Unit> units) {
    Set<String> stems = EvidenceAlignment.stems(clause);
    if (stems.size() < NEGATION_MIN_STEMS || units.isEmpty()) {
      return false;
    }
    EvidenceAlignment.Unit best = null;
    double first = -1;
    double second = -1;
    for (EvidenceAlignment.Unit u : units) {
      double c = EvidenceAlignment.coverage(stems, u.text());
      if (c > first) {
        second = first;
        first = c;
        best = u;
      } else if (c > second) {
        second = c;
      }
    }
    if (best == null
        || first < NEGATION_MIN_COVERAGE
        || first - Math.max(second, 0) < NEGATION_MIN_MARGIN) {
      return false;
    }
    return EvidenceAlignment.negative(clause) != EvidenceAlignment.negativeFor(stems, best);
  }

  private static void add(List<Violation> list, Violation v) {
    if (!list.contains(v)) {
      list.add(v);
    }
  }

  static Set<String> bigrams(String text) {
    String t = text.replaceAll("\\s+", "");
    Set<String> out = new HashSet<>();
    for (int i = 0; i + 2 <= t.length(); i++) {
      out.add(t.substring(i, i + 2));
    }
    return out;
  }
}
