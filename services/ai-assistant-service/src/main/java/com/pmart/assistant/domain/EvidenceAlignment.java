package com.pmart.assistant.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 인용 검사가 쓰는 짝짓기 도구(2026-11-21 사용자 "인용 검사 고친다면 2·3·4 진행"). 규칙 기반, 모델 없음.
 *
 * <ul>
 *   <li>근거 단위: 근거 글을 문장·목록 항목·표 행으로 나눈다. 표 행은 "머리글: 값, …" 한 줄로(행의 열이 한 단위에 함께 있게), 목록 번호("1.",
 *       "-")는 뗀다 — 목록 번호가 숫자 검사에 섞이지 않게.
 *   <li>구절: 답 문장을 ", " 로 나눈 조각(숫자 안의 쉼표 "4,000" 은 나누지 않는다).
 *   <li>낱말 줄기: 띄어쓰기 낱말의 앞 두 글자(한글 조사·어미를 떼는 대신). 한 글자 낱말·숫자·흔한 서술어는 뺀다.
 *   <li>숫자: "4,000"·"4천"·"3만"·"1만 5천" 을 같은 값으로. 앞뒤 숫자 없이 온전한 수만.
 * </ul>
 *
 * <p>운영자용(ai-assistant)·고객용(customer-assistant)이 같은 코드를 쓴다 — 한쪽을 고치면 다른 쪽도.
 */
public final class EvidenceAlignment {

  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?。])\\s+");
  private static final Pattern LIST_MARK = Pattern.compile("^(?:\\d+\\.|[-*•])\\s+");
  private static final Pattern TABLE_RULE = Pattern.compile(":?-{2,}:?");

  /** 답 구절 나누기 — 근거 조각({@link #SEGMENT_SPLIT})과 같은 연결어미에서도 나눠 양쪽 단위를 맞춘다(2026-11-27). */
  private static final Pattern CLAUSE_SPLIT =
      Pattern.compile("(?<!\\d),\\s+|,\\s+(?=\\D)|;\\s*|(?<=[가-힣](?:며|지만|으나|는데))\\s+");

  private static final Pattern NUMBER =
      Pattern.compile("(?<![\\d.,])(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*(만|천)?(?![\\d])");
  private static final Pattern WORD = Pattern.compile("[가-힣A-Za-z0-9]+");
  private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

  /** 뜻을 가르지 않는 흔한 줄기(서술어·조사 꼴). */
  private static final Set<String> STOP =
      Set.of(
          "있고", "있습", "있으", "있는", "있다", "없습", "없으", "없는", "없다", "입니", "합니", "됩니", "이고", "이며", "하며",
          "하고", "에는", "에서", "으로", "까지", "부터", "보다", "처럼", "같이", "따라", "다음", "경우", "그리", "또는", "것입",
          "것은", "것이", "것을", "하는", "하면", "해야", "되는", "주세", "드립", "니다", "이다", "아래", "위의");

  /** 부정·불가 표지. */
  private static final Pattern NEGATION =
      Pattern.compile("않|없|아니|아닙|불가|금지|못하|못 |말 것|안 ?됩|안 ?돼|미적용|제외");

  private EvidenceAlignment() {}

  /** 근거 글 → 단위(문장·목록 항목·표 행). 머리줄("#")은 빼지 않는다(조 번호를 말한 답이 있다). */
  public static List<String> units(String evidence) {
    return structuredUnits(evidence).stream().map(Unit::text).toList();
  }

  /** 가능·불가를 나타내는 표 칸 값. */
  private static final Pattern VERDICT =
      Pattern.compile("^(?:가능|불가|불가능|허용|금지|예|아니요|아니오|O|X|있음|없음|필요|불필요)$");

  /**
   * 근거 조각 나누기: 문장 끝, ", "(숫자 안 쉼표 제외), 그리고 연결어미(-며·-지만·-으나·-는데) 뒤. 연결어미로 이은 한 문장 안의 부정("…판단하며 배달팁과
   * 봉투값은 넣지 않습니다")이 앞 구절과 짝지은 조각에 같이 들어가 옳은 답을 부정 뒤집기로 거절하던 것을 막는다(2026-11-27 C2 측정 — 잘못 거절 4건이 모두
   * 부정 검사). "-고" 는 "재고" 같은 낱말 끝과 겹쳐 나누지 않는다.
   */
  private static final Pattern SEGMENT_SPLIT =
      Pattern.compile("(?<=[.!?。])\\s+|,\\s+(?=\\D)|(?<=[가-힣](?:며|지만|으나|는데))\\s+");

  /**
   * 근거 단위. 표 행이면 머리글과 칸 값을 함께 둔다(칸별 긍정·부정을 보려고), 아니면 둘 다 비어 있다.
   *
   * @param text 짝짓기·숫자 검사에 쓰는 한 줄(표 행은 "머리글: 값, …")
   */
  public record Unit(String text, List<String> headers, List<String> cells) {
    public boolean isRow() {
      return !cells.isEmpty();
    }
  }

  /**
   * 구절과 짝지어진 단위의 긍정·부정. 표 행이면, 구절과 줄기가 겹치는 머리글의 가능·불가 칸이 있으면 그 칸으로, 없으면 구절을 가장 잘 덮는 칸 조각으로. 표 행이
   * 아니면 구절을 가장 잘 덮는 조각(문장·", " 조각)으로.
   */
  public static boolean negativeFor(Set<String> clauseStems, Unit unit) {
    if (unit.isRow()) {
      for (int i = 0; i < unit.cells().size(); i++) {
        String cell = unit.cells().get(i);
        String header = i < unit.headers().size() ? unit.headers().get(i) : "";
        if (VERDICT.matcher(cell).matches()
            && stems(header).stream().anyMatch(clauseStems::contains)) {
          return negative(cell);
        }
      }
      return negative(bestSegment(clauseStems, unit.cells()));
    }
    return negative(bestSegment(clauseStems, List.of(unit.text())));
  }

  private static String bestSegment(Set<String> clauseStems, List<String> segments) {
    String best = "";
    double score = -1;
    for (String seg : segments) {
      for (String part : SEGMENT_SPLIT.split(seg)) {
        double c = coverage(clauseStems, part);
        if (c > score) {
          score = c;
          best = part;
        }
      }
    }
    return best;
  }

  /** 근거 글 → 단위(문장·목록 항목·표 행). 머리줄("#")은 빼지 않는다(조 번호를 말한 답이 있다). */
  public static List<Unit> structuredUnits(String evidence) {
    List<Unit> out = new ArrayList<>();
    List<String> header = null;
    for (String raw : evidence.split("\\R")) {
      String s = raw.strip();
      if (s.isEmpty()) {
        header = null;
        continue;
      }
      if (s.startsWith("|")) {
        List<String> cells = cells(s);
        if (cells.stream().allMatch(c -> TABLE_RULE.matcher(c).matches())) {
          continue;
        }
        if (header == null) {
          header = cells;
          continue;
        }
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
          if (row.length() > 0) {
            row.append(", ");
          }
          String h = i < header.size() ? header.get(i) : "";
          row.append(h.isEmpty() ? "" : h + ": ").append(cells.get(i));
        }
        out.add(new Unit(row.toString(), List.copyOf(header), List.copyOf(cells)));
        continue;
      }
      header = null;
      s = s.replaceFirst("^#+\\s*", "");
      s = LIST_MARK.matcher(s).replaceFirst("");
      for (String p : SENTENCE_END.split(s)) {
        if (p.replaceAll("\\s", "").length() >= 2) {
          out.add(new Unit(p.strip(), List.of(), List.of()));
        }
      }
    }
    return out;
  }

  private static List<String> cells(String row) {
    String t = row.strip();
    if (t.startsWith("|")) {
      t = t.substring(1);
    }
    if (t.endsWith("|")) {
      t = t.substring(0, t.length() - 1);
    }
    List<String> cells = new ArrayList<>();
    for (String c : t.split("\\|", -1)) {
      cells.add(c.strip());
    }
    return cells;
  }

  /** 답 문장 → 구절(", " 기준, 숫자 안 쉼표는 나누지 않는다). 목록 번호는 뗀다. */
  public static List<String> clauses(String sentence) {
    String s = LIST_MARK.matcher(sentence.strip()).replaceFirst("");
    List<String> out = new ArrayList<>();
    for (String c : CLAUSE_SPLIT.split(s)) {
      if (!c.isBlank()) {
        out.add(c.strip());
      }
    }
    return out;
  }

  /** 낱말 줄기(앞 두 글자). 숫자가 든 낱말·한 글자 낱말·흔한 줄기는 뺀다. */
  public static Set<String> stems(String text) {
    Set<String> out = new LinkedHashSet<>();
    Matcher m = WORD.matcher(text);
    while (m.find()) {
      String w = m.group();
      if (w.length() < 2 || HAS_DIGIT.matcher(w).find()) {
        continue;
      }
      String stem = w.substring(0, 2);
      if (!STOP.contains(stem)) {
        out.add(stem);
      }
    }
    return out;
  }

  /** 단위가 구절의 줄기를 얼마나 덮는가(0~1). 줄기가 없으면 0. */
  public static double coverage(Set<String> clauseStems, String unit) {
    if (clauseStems.isEmpty()) {
      return 0;
    }
    long hit = clauseStems.stream().filter(unit::contains).count();
    return (double) hit / clauseStems.size();
  }

  /** 글 안의 수(값). "4,000"=4000, "4천"=4000, "3만"=30000, "1만 5천"=15000. */
  public static List<Double> numbers(String text) {
    List<Double> out = new ArrayList<>();
    Matcher m = NUMBER.matcher(text);
    Double pendingMan = null;
    int pendingEnd = -1;
    while (m.find()) {
      double v;
      try {
        v = Double.parseDouble(m.group(1).replace(",", ""));
      } catch (NumberFormatException e) {
        continue;
      }
      String unit = m.group(2);
      if ("만".equals(unit)) {
        if (pendingMan != null) {
          out.add(pendingMan);
        }
        pendingMan = v * 10_000;
        pendingEnd = m.end();
        continue;
      }
      if ("천".equals(unit)) {
        v = v * 1_000;
      }
      if (pendingMan != null) {
        boolean adjacent = text.substring(pendingEnd, m.start()).isBlank();
        if (adjacent && v < 10_000) {
          out.add(pendingMan + v);
          pendingMan = null;
          continue;
        }
        out.add(pendingMan);
        pendingMan = null;
      }
      out.add(v);
    }
    if (pendingMan != null) {
      out.add(pendingMan);
    }
    return out;
  }

  public static boolean containsNumber(String unit, double value) {
    return numbers(unit).stream().anyMatch(n -> Math.abs(n - value) < 1e-9);
  }

  public static boolean negative(String text) {
    return NEGATION.matcher(text).find();
  }
}
