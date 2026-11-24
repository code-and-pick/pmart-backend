package com.pmart.storefront.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 진열 편성 규칙(2026-11-20 ADR-0075 ② — BO-05 일반 배너·상품 테마). 제출 때 검사하고, 게시된 본문으로 노출(기간)을 판단한다.
 *
 * <ul>
 *   <li>상품은 진열할 수 있어야 한다(게시 콘텐츠·상품 버전·판매중지 아님) — PRODUCT_NOT_PUBLISHED
 *   <li>배너 이미지는 올린 것이어야 한다 — IMAGE_NOT_FOUND. 링크: SECTION = 같은 초안의 테마, PRODUCT = 진열 가능 상품, CATEGORY
 *       = 값 있음, NONE = 비움 — INVALID_LINK
 *   <li>기간: startsAt &lt; endsAt — INVALID_PERIOD
 *   <li>문구: 근거 없는 최저가·신선 보장 — PROMOTION_NOT_ALLOWED. 할인·특가·쿠폰·세일·1+1 문구는 2026-11-21 부터 허용(ADR-0082
 *       쿠폰·기간 할인 활성, 사용자 "풀어줘")
 *   <li>0.9.0: 자동 랭킹 테마(RANKING_AUTO — 상품 비움·autoSize 1~30, INVALID_AUTO_THEME), 그 밖 테마는 상품 1개
 *       이상(EMPTY_THEME), 타이머는 끝 시각 필수(TIMER_NEEDS_END), MD 탭(테마 sectionId 가 있어야·한 테마는 한 탭 —
 *       UNKNOWN_SECTION·DUPLICATE_ID)
 *   <li>배너·테마가 하나도 없는 본문도 게시할 수 있다(철회 = 뺀 새 버전 — ADR-0075 ⑧)
 * </ul>
 */
public final class LayoutRules {

  /** 근거 없는 약속(최저가·신선 보장). 할인·쿠폰·특가 문구는 2026-11-21 부터 허용. */
  private static final Pattern PROMOTION =
      Pattern.compile("최저가|신선\\s*보장", Pattern.CASE_INSENSITIVE);

  public static final String RANKING_AUTO = "RANKING_AUTO";

  /**
   * 배너 자리 — 홈(HOME_TOP·HOME_MIDDLE), 0.9.0 팝업(POPUP)·처음 로딩 화면(SPLASH), 0.11.0 카테고리 화면 맨 위(CATEGORY
   * — 홈에는 섞지 않는다, 2026-11-22 클라이언트 BR-82).
   */
  public static final List<String> PLACEMENTS =
      List.of("HOME_TOP", "HOME_MIDDLE", "POPUP", "SPLASH", "HOME_INLINE", "CATEGORY");

  /** 0.10.0 홈 순서 안 배너 묶음 자리(groupId 필수). */
  public static final String HOME_INLINE = "HOME_INLINE";

  private static final Pattern GROUP_ID = Pattern.compile("^G-[A-Za-z0-9_-]{1,38}$");

  /** 홈 응답 banners 에 들어가는 자리. */
  public static final List<String> HOME_PLACEMENTS = List.of("HOME_TOP", "HOME_MIDDLE");

  public record Banner(
      String bannerId,
      String placement,
      String imageId,
      String altText,
      String linkType,
      @Nullable String linkRef,
      int sortOrder,
      @Nullable Instant startsAt,
      @Nullable Instant endsAt,
      @Nullable String groupId) {

    public Banner(
        String bannerId,
        String placement,
        String imageId,
        String altText,
        String linkType,
        @Nullable String linkRef,
        int sortOrder,
        @Nullable Instant startsAt,
        @Nullable Instant endsAt) {
      this(
          bannerId, placement, imageId, altText, linkType, linkRef, sortOrder, startsAt, endsAt,
          null);
    }
  }

  public record Theme(
      String sectionId,
      String kind,
      String title,
      @Nullable String subtitle,
      List<String> productIds,
      int sortOrder,
      @Nullable Instant startsAt,
      @Nullable Instant endsAt,
      boolean showTimer,
      @Nullable Integer autoSize,
      @Nullable String timerStyle) {
    /** 0.12.0 BR-84 남은 시간 머리 모양. */
    public static final String TIMER_LABEL = "LABEL";

    public static final String TIMER_BAND = "BAND";

    public Theme {
      productIds = productIds == null ? List.of() : List.copyOf(productIds);
      // 타이머가 꺼져 있으면 모양은 뜻이 없다(null), 켜져 있는데 비면 LABEL
      timerStyle = !showTimer ? null : TIMER_BAND.equals(timerStyle) ? TIMER_BAND : TIMER_LABEL;
    }

    public Theme(
        String sectionId,
        String kind,
        String title,
        @Nullable String subtitle,
        List<String> productIds,
        int sortOrder,
        @Nullable Instant startsAt,
        @Nullable Instant endsAt,
        boolean showTimer,
        @Nullable Integer autoSize) {
      this(
          sectionId,
          kind,
          title,
          subtitle,
          productIds,
          sortOrder,
          startsAt,
          endsAt,
          showTimer,
          autoSize,
          null);
    }

    public Theme(
        String sectionId,
        String kind,
        String title,
        @Nullable String subtitle,
        List<String> productIds,
        int sortOrder,
        @Nullable Instant startsAt,
        @Nullable Instant endsAt) {
      this(sectionId, kind, title, subtitle, productIds, sortOrder, startsAt, endsAt, false, null);
    }

    public boolean auto() {
      return RANKING_AUTO.equals(kind);
    }
  }

  /** MD 탭(0.9.0) — 그 탭에 보일 테마 sectionId(순서). */
  public record Tab(
      String tabKey,
      String title,
      List<String> sectionIds,
      int sortOrder,
      @Nullable Instant startsAt,
      @Nullable Instant endsAt,
      boolean showTimer) {
    public Tab {
      sectionIds = sectionIds == null ? List.of() : List.copyOf(sectionIds);
    }
  }

  public record Body(
      List<Banner> banners,
      List<Theme> themes,
      List<Tab> tabs,
      @Nullable String note,
      List<String> homeOrder,
      @Nullable Integer bannerIntervalSeconds) {
    public Body {
      banners = banners == null ? List.of() : List.copyOf(banners);
      themes = themes == null ? List.of() : List.copyOf(themes);
      tabs = tabs == null ? List.of() : List.copyOf(tabs);
      homeOrder = homeOrder == null ? List.of() : List.copyOf(homeOrder);
    }

    public Body(List<Banner> banners, List<Theme> themes, List<Tab> tabs, @Nullable String note) {
      this(banners, themes, tabs, note, List.of(), null);
    }

    public Body(List<Banner> banners, List<Theme> themes, @Nullable String note) {
      this(banners, themes, List.of(), note, List.of(), null);
    }

    public static Body empty() {
      return new Body(List.of(), List.of(), List.of(), null, List.of(), null);
    }

    /** MD 탭에 넣은 테마(HOME 에서 뺀다). */
    public Set<String> tabbedSections() {
      Set<String> out = new HashSet<>();
      tabs.forEach(x -> out.addAll(x.sectionIds()));
      return out;
    }
  }

  /** 0.15.0 막지 않는 경고(필드, 코드, 지금 크기·권장). */
  public record Warning(String field, String code, @Nullable String detail) {}

  /**
   * 배너 그림 규격 경고(0.15.0 — 사용자 2026-11-24 "서버에서 막지 않고 경고만 표시", 자리별 비율 ±10%·최소 크기 — {@link
   * BannerImageSpec}). 제출을 막지 않는다. imageSize = 올린 그림의 {가로, 세로}(없는 그림·읽지 못한 그림은 null — 없는 그림은 제출 검사가
   * IMAGE_NOT_FOUND 로 막으므로 여기서는 크기를 못 읽은 것만 BANNER_IMAGE_SIZE_UNKNOWN).
   */
  public static List<Warning> bannerImageWarnings(
      Body body, Predicate<String> imageExists, Function<String, int @Nullable []> imageSize) {
    List<Warning> out = new ArrayList<>();
    for (int i = 0; i < body.banners().size(); i++) {
      Banner b = body.banners().get(i);
      BannerImageSpec spec = BannerImageSpec.of(b.placement());
      if (spec == null || !imageExists.test(b.imageId())) {
        continue;
      }
      String at = "banners[" + i + "].imageId";
      int[] size = imageSize.apply(b.imageId());
      if (size == null) {
        out.add(new Warning(at, "BANNER_IMAGE_SIZE_UNKNOWN", "가로·세로를 읽지 못했습니다."));
        continue;
      }
      String code = spec.problem(size[0], size[1]);
      if (code != null) {
        out.add(
            new Warning(
                at,
                code,
                "지금 "
                    + size[0]
                    + "×"
                    + size[1]
                    + " — "
                    + b.placement()
                    + " 권장 "
                    + spec.ratioLabel()
                    + " "
                    + spec.recommendedWidth()
                    + "×"
                    + spec.recommendedHeight()
                    + "(최소 "
                    + spec.minWidth()
                    + "×"
                    + spec.minHeight()
                    + "). 앱에서 가장자리가 잘리거나 흐려 보입니다."));
      }
    }
    return out;
  }

  /** 검사에서 걸린 것(필드, 사유). */
  public record Problem(String field, String reason) {}

  private LayoutRules() {}

  /**
   * 제출 검사.
   *
   * @param displayable 진열할 수 있는 상품인가
   * @param imageExists 올린 배너 이미지인가
   */
  public static List<Problem> check(
      Body body, Predicate<String> displayable, Predicate<String> imageExists) {
    List<Problem> out = new ArrayList<>();
    Set<String> sections = new HashSet<>();
    for (Theme t : body.themes()) {
      sections.add(t.sectionId());
    }
    Set<String> bannerIds = new HashSet<>();
    for (int i = 0; i < body.banners().size(); i++) {
      Banner b = body.banners().get(i);
      String at = "banners[" + i + "]";
      if (!bannerIds.add(b.bannerId())) {
        out.add(new Problem(at + ".bannerId", "DUPLICATE_ID"));
      }
      if (!PLACEMENTS.contains(b.placement())) {
        out.add(new Problem(at + ".placement", "INVALID_PLACEMENT"));
      }
      boolean inline = HOME_INLINE.equals(b.placement());
      if (inline
          ? b.groupId() == null || !GROUP_ID.matcher(b.groupId()).matches()
          : b.groupId() != null) {
        out.add(new Problem(at + ".groupId", "INVALID_GROUP"));
      }
      if (!imageExists.test(b.imageId())) {
        out.add(new Problem(at + ".imageId", "IMAGE_NOT_FOUND"));
      }
      if (!linkOk(b, sections, displayable)) {
        out.add(new Problem(at + ".linkRef", "INVALID_LINK"));
      }
      if (!periodOk(b.startsAt(), b.endsAt())) {
        out.add(new Problem(at + ".endsAt", "INVALID_PERIOD"));
      }
      if (promotional(b.altText())) {
        out.add(new Problem(at + ".altText", "PROMOTION_NOT_ALLOWED"));
      }
    }
    Set<String> seenSections = new HashSet<>();
    for (int i = 0; i < body.themes().size(); i++) {
      Theme t = body.themes().get(i);
      String at = "themes[" + i + "]";
      if (!seenSections.add(t.sectionId())) {
        out.add(new Problem(at + ".sectionId", "DUPLICATE_ID"));
      }
      if (!periodOk(t.startsAt(), t.endsAt())) {
        out.add(new Problem(at + ".endsAt", "INVALID_PERIOD"));
      }
      if (promotional(t.title()) || promotional(t.subtitle())) {
        out.add(new Problem(at + ".title", "PROMOTION_NOT_ALLOWED"));
      }
      if (t.showTimer() && t.endsAt() == null) {
        out.add(new Problem(at + ".endsAt", "TIMER_NEEDS_END"));
      }
      if (t.auto()) {
        Integer n = t.autoSize();
        if (!t.productIds().isEmpty() || n == null || n < 1 || n > 30) {
          out.add(new Problem(at + ".autoSize", "INVALID_AUTO_THEME"));
        }
      } else if (t.productIds().isEmpty()) {
        out.add(new Problem(at + ".productIds", "EMPTY_THEME"));
      }
      Set<String> seenProducts = new HashSet<>();
      for (String p : t.productIds()) {
        if (!seenProducts.add(p)) {
          out.add(new Problem(at + ".productIds", "DUPLICATE_PRODUCT"));
        } else if (!displayable.test(p)) {
          out.add(new Problem(p, "PRODUCT_NOT_PUBLISHED"));
        }
      }
    }
    Set<String> tabKeys = new HashSet<>();
    Set<String> tabbed = new HashSet<>();
    for (int i = 0; i < body.tabs().size(); i++) {
      Tab tab = body.tabs().get(i);
      String at = "tabs[" + i + "]";
      if (!tabKeys.add(tab.tabKey())) {
        out.add(new Problem(at + ".tabKey", "DUPLICATE_ID"));
      }
      if (promotional(tab.title())) {
        out.add(new Problem(at + ".title", "PROMOTION_NOT_ALLOWED"));
      }
      if (!periodOk(tab.startsAt(), tab.endsAt())) {
        out.add(new Problem(at + ".endsAt", "INVALID_PERIOD"));
      }
      if (tab.showTimer() && tab.endsAt() == null) {
        out.add(new Problem(at + ".endsAt", "TIMER_NEEDS_END"));
      }
      if (tab.sectionIds().isEmpty()) {
        out.add(new Problem(at + ".sectionIds", "UNKNOWN_SECTION"));
      }
      for (String s : tab.sectionIds()) {
        if (!sections.contains(s)) {
          out.add(new Problem(at + ".sectionIds", "UNKNOWN_SECTION"));
        } else if (!tabbed.add(s)) {
          out.add(new Problem(at + ".sectionIds", "DUPLICATE_ID"));
        }
      }
    }
    // 0.10.0 넘김 간격·홈 순서
    Integer iv = body.bannerIntervalSeconds();
    if (iv != null && (iv < 2 || iv > 30)) {
      out.add(new Problem("bannerIntervalSeconds", "INVALID_INTERVAL"));
    }
    Set<String> groups = new HashSet<>();
    for (Banner b : body.banners()) {
      if (HOME_INLINE.equals(b.placement()) && b.groupId() != null) {
        groups.add(b.groupId());
      }
    }
    Set<String> homeThemes = new HashSet<>(sections);
    homeThemes.removeAll(body.tabbedSections());
    Set<String> ordered = new HashSet<>();
    for (int i = 0; i < body.homeOrder().size(); i++) {
      String ref = body.homeOrder().get(i);
      String at = "homeOrder[" + i + "]";
      if (!ordered.add(ref)) {
        out.add(new Problem(at, "DUPLICATE_ID"));
      } else if (!groups.contains(ref) && !homeThemes.contains(ref)) {
        out.add(new Problem(at, "UNKNOWN_SECTION"));
      }
    }
    for (String g : groups) {
      if (!ordered.contains(g)) {
        out.add(new Problem("homeOrder", "UNPLACED_GROUP"));
      }
    }
    return out;
  }

  public static boolean promotional(@Nullable String text) {
    return text != null && PROMOTION.matcher(text.replaceAll("\\s+", " ")).find();
  }

  private static boolean linkOk(Banner b, Set<String> sections, Predicate<String> displayable) {
    String ref = b.linkRef() == null ? "" : b.linkRef().strip();
    return switch (b.linkType()) {
      case "NONE" -> ref.isEmpty();
      case "SECTION" -> sections.contains(ref);
      case "PRODUCT" -> !ref.isEmpty() && displayable.test(ref);
      case "CATEGORY" -> !ref.isEmpty();
      default -> false;
    };
  }

  static boolean periodOk(@Nullable Instant startsAt, @Nullable Instant endsAt) {
    return startsAt == null || endsAt == null || startsAt.isBefore(endsAt);
  }

  /** 지금 보이는가(기간 안). */
  public static boolean active(@Nullable Instant startsAt, @Nullable Instant endsAt, Instant now) {
    return (startsAt == null || !now.isBefore(startsAt))
        && (endsAt == null || now.isBefore(endsAt));
  }

  /** 잠금 해시(본문 JSON 그대로 SHA-256). */
  public static String hash(String canonicalJson) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 이미지 형식(파일 앞부분) — JPEG·PNG·WEBP, 아니면 null. */
  public static @Nullable String imageType(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
      return "image/png";
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
      return "image/webp";
    }
    return null;
  }

  public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
}
