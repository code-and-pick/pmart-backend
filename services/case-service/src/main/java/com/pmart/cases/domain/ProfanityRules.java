package com.pmart.cases.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 욕설 자동 인식(2026-11-23 R-14, 사용자 "욕설이니까 자동으로 인식해서", 금칙어 목록 = 설정 — "추천대로").
 *
 * <p>단어 목록 대조만(새 프레임워크 없음). 대소문자·띄어쓰기·문장부호를 지우고 비교해 "시 발", "시.발" 같은 띄어 쓰기도 잡는다. 목록이 비면 아무것도 잡지 않는다.
 * 글·단어는 로그에 남기지 않는다.
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
