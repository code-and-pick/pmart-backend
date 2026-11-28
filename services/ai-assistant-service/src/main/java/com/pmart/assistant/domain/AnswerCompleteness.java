package com.pmart.assistant.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 빠진 사실 검사(2026-11-28, ES-First 계획 P1 — 평가에서 생성 답이 첫 사실만 말하고 나머지를 빠뜨림: 운영자 E02·고객 C13·C15·C17). 규칙
 * 기반, 모델 없음. 인용 검사({@link CitationVerifier})는 틀린 말만 막고 빠진 말은 못 막아서 더한다.
 *
 * <ul>
 *   <li><b>한 칸 안의 번호 항목</b>: 근거 표 칸(또는 한 줄)에 "1. … 2. … 3. …" 처럼 항목이 함께 있으면 그 묶음은 한 답이다. 답이 그 묶음의 한
 *       항목을 분명히 쓰고(줄기 60% 이상) 다른 항목을 거의 안 쓰면(줄기 34% 미만) 빠진 것으로 본다. <b>조문의 목록 전체(1항·2항 …)에는 쓰지
 *       않는다</b> — 질문과 관계없는 항을 안 쓴 정상 답을 버리게 된다.
 *   <li><b>참조만 한 답</b>: 모든 문장이 "…을 따릅니다", "…에 따라 처리 기준이 적용됩니다"처럼 다른 정책·조항을 가리키기만 하고 내용이 없으면 빠진 것으로
 *       본다.
 * </ul>
 *
 * <p>운영자용(ai-assistant)·고객용(customer-assistant)이 같은 코드를 쓴다 — 한쪽을 고치면 다른 쪽도.
 */
public final class AnswerCompleteness {

  /** 한 칸·한 줄 안의 번호 항목 표시("1. ", "2. " — 앞이 줄 처음이거나 띄어쓰기). */
  private static final Pattern INLINE_NUMBER = Pattern.compile("(?:^|(?<=\\s))(\\d{1,2})\\.\\s+");

  static final double USED = 0.6;
  static final double MISSED = 0.34;

  /** 다른 정책·조항을 가리키기만 하는 문장. */
  private static final Pattern REFERRAL =
      Pattern.compile(
          "(?:정책|안내|기준|절차|규정|조항|\\(목적\\))[^.]*(?:따릅니다|따라야|따르면|따름|따라)|"
              + "(?:에|을|를)\\s*따라[^.]*(?:적용됩니다|처리됩니다|처리합니다)|"
              + "처리 기준이 적용");

  private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?。])\\s+|\\n+");

  private AnswerCompleteness() {}

  /** 근거 글의 칸·줄마다 번호 항목 묶음(항목이 둘 이상일 때만). */
  static List<List<String>> inlineGroups(String evidence) {
    List<List<String>> out = new ArrayList<>();
    for (String raw : evidence.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty()) {
        continue;
      }
      List<String> pieces = new ArrayList<>();
      if (line.startsWith("|")) {
        for (String c : line.split("\\|")) {
          pieces.add(c.strip());
        }
      } else {
        // 줄 처음의 목록 번호("1. …")는 조문 목록 — 한 줄 안에 번호가 더 있을 때만 묶음으로 본다
        pieces.add(line.replaceFirst("^\\d{1,2}\\.\\s+", ""));
      }
      for (String p : pieces) {
        List<String> items = items(p);
        if (items.size() >= 2) {
          out.add(items);
        }
      }
    }
    return out;
  }

  /** "1. a 2. b 3. c" → [a, b, c]. 1 부터 차례로 붙은 번호만(가격 "1. 5" 같은 것은 아님). */
  static List<String> items(String text) {
    Matcher m = INLINE_NUMBER.matcher(text);
    List<int[]> marks = new ArrayList<>();
    int expect = 1;
    while (m.find()) {
      if (Integer.parseInt(m.group(1)) == expect) {
        marks.add(new int[] {m.start(), m.end()});
        expect++;
      }
    }
    List<String> out = new ArrayList<>();
    if (marks.size() < 2) {
      return out;
    }
    for (int i = 0; i < marks.size(); i++) {
      int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length();
      String item = text.substring(marks.get(i)[1], end).strip();
      if (!item.isEmpty()) {
        out.add(item);
      }
    }
    return out;
  }

  /** 답이 쓴 번호 항목 묶음에서 빠진 항목이 있는가. */
  public static boolean missingInlineItems(String answer, List<String> evidence) {
    for (String e : evidence) {
      for (List<String> group : inlineGroups(e)) {
        boolean used = false;
        boolean missed = false;
        for (String item : group) {
          Set<String> st = EvidenceAlignment.stems(item);
          if (st.isEmpty()) {
            continue;
          }
          double c = EvidenceAlignment.coverage(st, answer);
          if (c >= USED) {
            used = true;
          } else if (c < MISSED) {
            missed = true;
          }
        }
        if (used && missed) {
          return true;
        }
      }
    }
    return false;
  }

  /** 모든 문장이 참조만 하는가(내용 없는 답). */
  public static boolean referralOnly(String answer) {
    List<String> sentences = new ArrayList<>();
    for (String s : SENTENCE_SPLIT.split(answer)) {
      if (s.replaceAll("\\s", "").length() >= 6) {
        sentences.add(s.strip());
      }
    }
    return !sentences.isEmpty() && sentences.stream().allMatch(s -> REFERRAL.matcher(s).find());
  }

  /** 섹션이 목적 조항("제N조 (목적)")인가 — 내용이 없는 조라 근거 순위에서 뒤로 민다({@link RankingPolicy}). */
  public static boolean purposeSection(@Nullable String section) {
    if (section == null) {
      return false;
    }
    String last = section.substring(section.lastIndexOf('>') + 1).strip();
    return last.matches("제\\s*\\d+\\s*조\\s*\\(목적\\)");
  }
}
