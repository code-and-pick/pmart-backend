package com.pmart.cases.domain;

import org.jspecify.annotations.Nullable;

/** 고객센터 문의 규칙(0.2.0) — 프레임워크 없음. */
public final class InquiryRules {

  private InquiryRules() {}

  public static final String RECEIVED = "RECEIVED";
  public static final String ANSWERED = "ANSWERED";
  public static final String CLOSED = "CLOSED";

  /** 고객센터 상담원 역할(2026-11-09 — 운영자 역할 이름은 자유 문자열, 이 서비스가 이 이름으로 인가). */
  public static final String CS_AGENT = "CS_AGENT";

  /** 본사 관리자 — 문의 보기만(답변 불가, 2026-11-16 사용자 (가)). */
  public static final String OPERATOR_ADMIN = "OPERATOR_ADMIN";

  /** 답변을 붙일 수 있는가 — 닫힌 문의는 안 된다. 거절 이유, 되면 null. */
  public static @Nullable String replyRejection(String status) {
    return CLOSED.equals(status) ? "INQUIRY_CLOSED" : null;
  }

  /** 답변 뒤 상태. 닫히지 않았으면 ANSWERED(이미 답변됨이어도 그대로). */
  public static String afterReply(String status) {
    return CLOSED.equals(status) ? CLOSED : ANSWERED;
  }

  /** 문의 출처 기본값. */
  public static String origin(@Nullable String origin) {
    return origin == null || origin.isBlank() ? "INQUIRY_PAGE" : origin;
  }
}
