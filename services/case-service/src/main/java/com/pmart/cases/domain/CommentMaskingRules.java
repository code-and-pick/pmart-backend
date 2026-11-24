package com.pmart.cases.domain;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 만족도 의견 가리기(0.38.0 — 직원별 평가 목록, 사용자 2026-11-24 "고객 개인정보는 가리고"). 고객이 한 줄 의견에 쓴 전화번호·메일 모양만 가린다 —
 * 이름·주소는 모양으로 알 수 없어 가리지 않는다(목록에는 고객 ID·이름·연락처 칸 자체가 없다).
 */
public final class CommentMaskingRules {

  /** 메일: 앞 한 글자만 남김 — a***@***. */
  private static final Pattern EMAIL =
      Pattern.compile("([A-Za-z0-9])[A-Za-z0-9._%+-]*@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /** 전화: 0 으로 시작하는 2~3 · 3~4 · 4 자리(하이픈·점·띄어쓰기 섞여도) — 앞자리만 남김. */
  private static final Pattern PHONE =
      Pattern.compile("(?<!\\d)(0\\d{1,2})[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}(?!\\d)");

  private CommentMaskingRules() {}

  public static @Nullable String mask(@Nullable String comment) {
    if (comment == null) {
      return null;
    }
    String s = EMAIL.matcher(comment).replaceAll("$1***@***");
    return PHONE.matcher(s).replaceAll("$1-****-****");
  }
}
