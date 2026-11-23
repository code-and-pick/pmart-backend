package com.pmart.delivery.execution.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 금칙어 대조(2026-11-23 — 사용자 "응": 배송 도움요청 대화에도 case 문의와 같은 규칙). case-service {@code ProfanityRules} 와
 * 같은 판정 — 대소문자·띄어쓰기·문장부호를 지우고 목록 단어가 들어 있는지만 본다(목록이 비면 아무것도 잡지 않음). 서비스 사이 코드 공유를 하지 않아 같은 규칙을 여기
 * 둔다 — 바꾸면 case 와 같이. 글·단어는 로그에 남기지 않는다.
 */
public final class ProfanityRules {

  private static final Pattern NOISE = Pattern.compile("[\\s\\p{Punct}·…~※★☆♡♥]+");

  private ProfanityRules() {}

  public static String normalize(@Nullable String s) {
    return s == null ? "" : NOISE.matcher(s.toLowerCase(Locale.ROOT)).replaceAll("");
  }

  /** 글 가운데 하나라도 목록의 단어를 담고 있으면 true. */
  public static boolean contains(List<String> words, @Nullable String... texts) {
    if (words.isEmpty()) {
      return false;
    }
    StringBuilder all = new StringBuilder();
    for (String t : texts) {
      all.append(normalize(t)).append('\u0000');
    }
    String hay = all.toString();
    for (String w : words) {
      String n = normalize(w);
      if (!n.isEmpty() && hay.contains(n)) {
        return true;
      }
    }
    return false;
  }
}
