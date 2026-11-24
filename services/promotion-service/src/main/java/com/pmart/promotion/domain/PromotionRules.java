package com.pmart.promotion.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 쿠폰·기간 할인 규칙(ADR-0082 — 부록 C §8.5 계산 순서). 순수 계산만 — 저장·시각은 호출자가 준다.
 *
 * <ul>
 *   <li>기간 할인: 같은 SKU 에 여럿이면 가장 큰 할인 1개. 정률은 원 미만 버림, 할인 ≤ 단가.
 *   <li>장바구니 쿠폰: 최소 주문금액은 기간 할인 뒤·쿠폰 전 상품액. 정률은 원 미만 버림 + 최대 할인액, 할인 ≤ 상품액.
 * </ul>
 */
public final class PromotionRules {

  private PromotionRules() {}

  public static final int MAX_RATE = 90;
  public static final int MAX_TARGETS = 500;

  /** 0.3.0 — 만들 때 지정하는 받을 고객 한도(지정 발급 coupon-grants 와 같다). */
  public static final int MAX_RECIPIENTS = 100;

  /** 진열 편성 테마 kind — 자동 랭킹은 상품이 저절로 바뀌어 할인 대상으로 고르지 못한다(0.3.0 사용자 "추천안대로"). */
  public static final String RANKING_AUTO = "RANKING_AUTO";

  public record Discount(String type, int value, @Nullable Integer maxAmount) {}

  public record Target(String type, String id) {}

  /** 0.3.0 — 지정 발급(ADMIN) 쿠폰을 만들 때 정한 받을 고객. 승인 뒤 시작 시각에 서버가 발급한다. */
  public record Recipients(List<String> accountIds, String reason) {
    public Recipients {
      accountIds = accountIds == null ? List.of() : List.copyOf(accountIds);
    }
  }

  public record CouponPolicy(
      String issueMode,
      long minOrderAmount,
      @Nullable Integer totalQuantity,
      int perCustomerLimit,
      @Nullable Integer useDays,
      @Nullable Instant useUntil,
      @Nullable Recipients recipients) {

    public CouponPolicy(
        String issueMode,
        long minOrderAmount,
        @Nullable Integer totalQuantity,
        int perCustomerLimit,
        @Nullable Integer useDays,
        @Nullable Instant useUntil) {
      this(issueMode, minOrderAmount, totalQuantity, perCustomerLimit, useDays, useUntil, null);
    }
  }

  /** 행사 입력(도메인 모양). */
  public record Input(
      String kind,
      String name,
      Instant startsAt,
      Instant endsAt,
      Discount discount,
      List<Target> targets,
      @Nullable CouponPolicy coupon) {}

  /** code = 화면이 가려 쓸 코드(0.3.0 — 없을 수 있음). */
  public record Problem(String field, String reason, @Nullable String code) {
    public Problem(String field, String reason) {
      this(field, reason, null);
    }
  }

  /** 제출을 막는 곳 — 받아 둔 진열 테마가 없다고 보고 검사(테마 대상이 있으면 THEME_NOT_PUBLISHED). */
  public static List<Problem> check(Input in, Instant now) {
    return check(in, now, Map.of());
  }

  /**
   * 제출을 막는 곳(초안 저장은 막지 않는다). themeKinds = 받아 둔 지금 게시 진열의 테마 sectionId → kind(0.3.0 THEME 대상 검사).
   */
  public static List<Problem> check(Input in, Instant now, Map<String, String> themeKinds) {
    List<Problem> out = new ArrayList<>();
    if (in.name() == null || in.name().isBlank()) {
      out.add(new Problem("name", "이름이 필요합니다."));
    }
    if (!in.endsAt().isAfter(in.startsAt())) {
      out.add(new Problem("endsAt", "끝 시각이 시작 시각보다 뒤여야 합니다."));
    }
    if (!in.endsAt().isAfter(now)) {
      out.add(new Problem("endsAt", "이미 지난 끝 시각입니다."));
    }
    Discount d = in.discount();
    if ("RATE".equals(d.type())) {
      if (d.value() < 1 || d.value() > MAX_RATE) {
        out.add(new Problem("discount.value", "할인율은 1~" + MAX_RATE + "% 입니다."));
      }
    } else if ("AMOUNT".equals(d.type())) {
      if (d.value() < 1) {
        out.add(new Problem("discount.value", "할인액은 1원 이상입니다."));
      }
    } else {
      out.add(new Problem("discount.type", "RATE 또는 AMOUNT 입니다."));
    }
    if ("PRICE_DISCOUNT".equals(in.kind())) {
      if (in.targets().isEmpty()) {
        out.add(new Problem("targets", "할인할 상품(SKU)·카테고리·진열 테마를 하나 이상 고르세요."));
      }
      if (in.targets().size() > MAX_TARGETS) {
        out.add(new Problem("targets", "대상은 " + MAX_TARGETS + "개까지입니다."));
      }
      Set<String> seen = new HashSet<>();
      for (int i = 0; i < in.targets().size(); i++) {
        Target t = in.targets().get(i);
        if (!"SKU".equals(t.type()) && !"CATEGORY".equals(t.type()) && !"THEME".equals(t.type())) {
          out.add(new Problem("targets[" + i + "].type", "SKU·CATEGORY·THEME 중 하나입니다."));
        } else if ("SKU".equals(t.type()) && !t.id().matches("^[EB]-[0-9]{8}$")) {
          out.add(new Problem("targets[" + i + "].id", "SKU 코드 모양이 아닙니다(E-/B- 8자리)."));
        } else if ("THEME".equals(t.type()) && !t.id().matches("^T-[A-Za-z0-9_-]{1,38}$")) {
          out.add(new Problem("targets[" + i + "].id", "진열 테마 ID 모양이 아닙니다(T-…)."));
        } else if ("THEME".equals(t.type()) && !themeKinds.containsKey(t.id())) {
          out.add(
              new Problem(
                  "targets[" + i + "].id",
                  "지금 게시된 진열에 없는 테마입니다(초안에만 있거나 빠짐).",
                  "THEME_NOT_PUBLISHED"));
        } else if ("THEME".equals(t.type()) && RANKING_AUTO.equals(themeKinds.get(t.id()))) {
          out.add(
              new Problem(
                  "targets[" + i + "].id",
                  "자동 랭킹 테마는 상품이 저절로 바뀌어 할인 대상으로 고를 수 없습니다.",
                  "THEME_AUTO_RANKING"));
        } else if (!seen.add(t.type() + ":" + t.id())) {
          out.add(new Problem("targets[" + i + "]", "같은 대상이 두 번 있습니다."));
        }
      }
      if (d.maxAmount() != null) {
        out.add(new Problem("discount.maxAmount", "최대 할인액은 쿠폰에만 씁니다."));
      }
      if (in.coupon() != null) {
        out.add(new Problem("coupon", "기간 할인에는 쿠폰 설정을 두지 않습니다."));
      }
    } else if ("COUPON".equals(in.kind())) {
      if (!in.targets().isEmpty()) {
        out.add(new Problem("targets", "장바구니 쿠폰은 대상 상품을 두지 않습니다(주문 전체)."));
      }
      CouponPolicy c = in.coupon();
      if (c == null) {
        out.add(new Problem("coupon", "쿠폰 설정이 필요합니다."));
      } else {
        if (!List.of("DOWNLOAD", "ADMIN", "SIGNUP").contains(c.issueMode())) {
          out.add(new Problem("coupon.issueMode", "DOWNLOAD·ADMIN·SIGNUP 중 하나입니다."));
        }
        if (c.minOrderAmount() < 0) {
          out.add(new Problem("coupon.minOrderAmount", "0원 이상입니다."));
        }
        if (c.perCustomerLimit() < 1 || c.perCustomerLimit() > 10) {
          out.add(new Problem("coupon.perCustomerLimit", "1~10 입니다."));
        }
        if (c.totalQuantity() != null && c.totalQuantity() < 1) {
          out.add(new Problem("coupon.totalQuantity", "1 이상이거나 비웁니다."));
        }
        if ((c.useDays() == null) == (c.useUntil() == null)) {
          out.add(
              new Problem("coupon.useDays", "사용 기간은 받은 날부터 N일(useDays) 또는 고정 기한(useUntil) 중 하나만."));
        } else if (c.useUntil() != null && c.useUntil().isBefore(in.endsAt())) {
          out.add(new Problem("coupon.useUntil", "고정 사용 기한이 받기 끝 시각보다 앞입니다."));
        } else if (c.useDays() != null && (c.useDays() < 1 || c.useDays() > 365)) {
          out.add(new Problem("coupon.useDays", "1~365일입니다."));
        }
        if ("AMOUNT".equals(d.type()) && d.maxAmount() != null) {
          out.add(new Problem("discount.maxAmount", "정액 쿠폰에는 최대 할인액을 두지 않습니다."));
        }
        checkRecipients(c, out);
        if ("AMOUNT".equals(d.type()) && c.minOrderAmount() > 0 && d.value() > c.minOrderAmount()) {
          out.add(new Problem("discount.value", "정액 할인이 최소 주문금액보다 큽니다."));
        }
      }
    } else {
      out.add(new Problem("kind", "PRICE_DISCOUNT 또는 COUPON 입니다."));
    }
    return out;
  }

  private static void checkRecipients(CouponPolicy c, List<Problem> out) {
    Recipients r = c.recipients();
    if (r == null) {
      return;
    }
    if (!"ADMIN".equals(c.issueMode())) {
      out.add(
          new Problem(
              "coupon.recipients", "받을 고객은 지정 발급(ADMIN) 쿠폰에만 둡니다.", "RECIPIENTS_NOT_ADMIN"));
    }
    List<String> ids = r.accountIds();
    if (ids.isEmpty() || ids.size() > MAX_RECIPIENTS) {
      out.add(new Problem("coupon.recipients.accountIds", "받을 고객은 1~" + MAX_RECIPIENTS + "명입니다."));
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < ids.size(); i++) {
      String id = ids.get(i);
      if (id == null || id.isBlank() || id.length() > 64) {
        out.add(new Problem("coupon.recipients.accountIds[" + i + "]", "고객 ID 가 비었거나 너무 깁니다."));
      } else if (!seen.add(id)) {
        out.add(new Problem("coupon.recipients.accountIds[" + i + "]", "같은 고객이 두 번 있습니다."));
      }
    }
    if (r.reason() == null || r.reason().isBlank() || r.reason().length() > 200) {
      out.add(new Problem("coupon.recipients.reason", "발급 사유는 1~200자입니다."));
    }
  }

  /** 개당 기간 할인액 — 정률은 원 미만 버림, 단가를 넘지 않는다. */
  public static long unitDiscount(Discount d, long unitPrice) {
    long raw = "RATE".equals(d.type()) ? unitPrice * d.value() / 100 : d.value();
    return Math.max(0, Math.min(raw, unitPrice));
  }

  /** 표시용 할인율(%) — 내림. */
  public static int ratePercent(long unitPrice, long unitDiscount) {
    return unitPrice <= 0 ? 0 : (int) (unitDiscount * 100 / unitPrice);
  }

  /** 장바구니 쿠폰 할인액(최소 주문금액은 호출자가 먼저 확인) — 정률은 원 미만 버림·최대 할인액, 상품액을 넘지 않는다. */
  public static long couponDiscount(Discount d, long itemAmount) {
    long raw = "RATE".equals(d.type()) ? itemAmount * d.value() / 100 : d.value();
    if ("RATE".equals(d.type()) && d.maxAmount() != null) {
      raw = Math.min(raw, d.maxAmount());
    }
    return Math.max(0, Math.min(raw, itemAmount));
  }

  /** 쿠폰 사용 기한: 받은 날(zone)부터 N일째 끝(다음 날 0시 직전) 또는 고정 기한. */
  public static Instant validUntil(CouponPolicy c, Instant issuedAt, ZoneId zone) {
    if (c.useDays() != null) {
      LocalDate day = issuedAt.atZone(zone).toLocalDate().plusDays(c.useDays());
      return day.atStartOfDay(zone).toInstant().minusMillis(1);
    }
    return java.util.Objects.requireNonNull(c.useUntil());
  }

  /** 행사 단계: 승인 전·시작 전·진행 중·끝남. */
  public static String phase(
      String state, Instant startsAt, Instant endsAt, @Nullable Instant endedAt, Instant now) {
    if ("ENDED".equals(state)) {
      return "FINISHED";
    }
    if (!"APPROVED".equals(state)) {
      return "NOT_APPROVED";
    }
    if (now.isBefore(startsAt)) {
      return "SCHEDULED";
    }
    if (!now.isBefore(endsAt) || (endedAt != null && !now.isBefore(endedAt))) {
      return "FINISHED";
    }
    return "RUNNING";
  }

  /**
   * 카테고리 ID 와 그 상위(P마트 카테고리 = B마트 트리 id 규칙 {@code p09 → p09-c01 → p09-c01-l01}, product-service
   * CLAUDE.md seed-v1). 카테고리 행사는 그 아래 상품 전부라 상위 ID 로도 찾게 둔다.
   */
  public static List<String> withAncestors(String categoryId) {
    List<String> out = new ArrayList<>();
    String[] parts = categoryId.split("-");
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        b.append('-');
      }
      b.append(parts[i]);
      out.add(b.toString());
    }
    return out;
  }

  public static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
