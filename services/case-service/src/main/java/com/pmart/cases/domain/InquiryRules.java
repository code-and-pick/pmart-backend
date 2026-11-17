package com.pmart.cases.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

  /** 남긴 사람 종류(0.13.0). */
  public static final String CUSTOMER = "CUSTOMER";

  public static final String RIDER = "RIDER";

  /** 라이더 지원센터(사건함 DELIVERY_OPS 팀 역할) — 라이더 문의를 보고 답한다(2026-11-16 사용자 "추천대로"). */
  public static final String RIDER_OPS = "RIDER_OPS";

  /** 고객 분류(0.2.0 그대로). */
  public static final Set<String> CUSTOMER_CATEGORIES =
      Set.of("ORDER", "DELIVERY", "PAYMENT", "REFUND", "PRODUCT", "ACCOUNT", "OTHER");

  /** 라이더 분류(라이더 앱 요청 §11-4 "정산·계정·앱 오류 등"). 배송 건은 배송 화면 도움요청. */
  public static final Set<String> RIDER_CATEGORIES =
      Set.of("SETTLEMENT", "ACCOUNT", "APP_ERROR", "OTHER");

  public static boolean categoryAllowed(String requesterType, String category) {
    return (RIDER.equals(requesterType) ? RIDER_CATEGORIES : CUSTOMER_CATEGORIES)
        .contains(category);
  }

  /** 그 종류 문의에 답하는 역할 — 고객 = 상담원, 라이더 = 지원센터. */
  public static String answerRole(String requesterType) {
    return RIDER.equals(requesterType) ? RIDER_OPS : CS_AGENT;
  }

  /** 운영자가 볼 수 있는 문의 종류 — 답하는 역할 + 본사 관리자(보기만). */
  public static Set<String> readableTypes(Set<String> roles) {
    if (roles.contains(OPERATOR_ADMIN)) {
      return Set.of(CUSTOMER, RIDER);
    }
    boolean c = roles.contains(CS_AGENT);
    boolean r = roles.contains(RIDER_OPS);
    return c && r ? Set.of(CUSTOMER, RIDER) : c ? Set.of(CUSTOMER) : r ? Set.of(RIDER) : Set.of();
  }

  /** 사건 행 종류. */
  public static String caseType(String requesterType) {
    return RIDER.equals(requesterType) ? "RIDER_INQUIRY" : "CS_INQUIRY";
  }

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

  // ------------------------------------------------------------ 사진(0.14.0 — 2026-11-17 사용자 "리뷰 방식
  // 그대로")

  /** 문의 하나에 붙일 수 있는 사진 수(리뷰와 같게). */
  public static final int MAX_PHOTOS = 5;

  /** 장당 상한(리뷰와 같게). */
  public static final int MAX_PHOTO_BYTES = 10 * 1024 * 1024;

  /** 문의에 붙지 않은 사진을 지우기까지. */
  public static final Duration ORPHAN_PHOTO_TTL = Duration.ofHours(24);

  /** 붙일 사진 ID 목록 문제(없으면 빈 값). already = 이미 붙은 장수. */
  public static Optional<String> photoIdsProblem(@Nullable List<String> photoIds, int already) {
    if (photoIds == null || photoIds.isEmpty()) {
      return Optional.empty();
    }
    if (photoIds.stream().anyMatch(id -> id == null || id.isBlank())) {
      return Optional.of("빈 사진 ID 가 있습니다.");
    }
    if (photoIds.stream().distinct().count() != photoIds.size()) {
      return Optional.of("같은 사진이 두 번 있습니다.");
    }
    if (already + photoIds.size() > MAX_PHOTOS) {
      return Optional.of("사진은 문의 하나에 " + MAX_PHOTOS + "장까지입니다.");
    }
    return Optional.empty();
  }

  /** 파일 앞부분으로 형식 판정(이름·보낸 Content-Type 은 믿지 않는다). JPEG·PNG·WEBP 만. */
  public static Optional<String> photoType(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return Optional.of("image/jpeg");
    }
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return Optional.of("image/png");
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of("image/webp");
    }
    return Optional.empty();
  }

  /** 사진 크기·형식 문제(없으면 빈 값). */
  public static Optional<String> photoProblem(byte[] b) {
    if (b.length == 0) {
      return Optional.of("빈 파일입니다.");
    }
    if (b.length > MAX_PHOTO_BYTES) {
      return Optional.of("사진은 장당 10MB 까지입니다.");
    }
    if (photoType(b).isEmpty()) {
      return Optional.of("JPEG·PNG·WEBP 사진만 올릴 수 있습니다.");
    }
    return Optional.empty();
  }
}
