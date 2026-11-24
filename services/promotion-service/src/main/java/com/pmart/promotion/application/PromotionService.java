package com.pmart.promotion.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.domain.PromotionRules.Input;
import com.pmart.promotion.domain.PromotionRules.Problem;
import com.pmart.promotion.infrastructure.PromotionStore;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import com.pmart.promotion.infrastructure.RecipientStore;
import com.pmart.promotion.infrastructure.RecipientStore.RecipientRow;
import com.pmart.promotion.infrastructure.ThemeCopyStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 행사(기간 할인·쿠폰) 만들기·고치기·제출·조기 종료 제출(ADR-0082). 만들고 고치고 제출은 MARKETING·OPERATOR_ADMIN, 보기는 + CS_AGENT
 * (지정 발급할 행사를 고른다). 발효·종료는 승인 사건으로 {@link PromotionApprovals} 가 한다.
 */
@Service
public class PromotionService {

  public static final String TARGET_SERVICE = "promotion-service";
  public static final List<String> EDITORS = List.of("MARKETING", "OPERATOR_ADMIN");
  public static final List<String> READERS = List.of("MARKETING", "OPERATOR_ADMIN", "CS_AGENT");

  /** 행사 보기(행 + 대상 + 지금 제출하면 막히는 곳 + 단계 + 0.3.0 테마 이름·받을 고객). */
  public record View(
      PromotionRow row,
      List<String[]> targets,
      List<Problem> problems,
      String phase,
      Map<String, String> themeTitles,
      PromotionRules.@Nullable Recipients recipients,
      List<RecipientRow> recipientResults) {}

  /** 고를 수 있는 진열 테마(0.3.0). */
  public record ThemeTargets(
      int layoutVersion, @Nullable Instant syncedAt, List<ThemeCopyStore.ThemeSummary> themes) {}

  public record Submission(
      String promotionId, String draftHash, Map<String, Object> approvalRequest) {}

  public record Page(List<View> items, @Nullable String nextCursor) {}

  private final PromotionStore store;
  private final ThemeCopyStore themes;
  private final RecipientStore recipients;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public PromotionService(
      PromotionStore store,
      ThemeCopyStore themes,
      RecipientStore recipients,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.themes = themes;
    this.recipients = recipients;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  static PmartPrincipal require(List<String> roles, String what) {
    PmartPrincipal who = PmartPrincipal.current().orElse(null);
    if (who == null
        || who.type() != PmartPrincipal.PrincipalType.OPERATOR
        || roles.stream().noneMatch(who::hasRole)) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, what + "은(는) " + String.join("·", roles) + " 만 할 수 있습니다.");
    }
    return who;
  }

  /** 새 초안. inputJson 은 받은 본문 그대로(화면 되돌려주기·해시). */
  public View create(String requestId, String sourceSystem, Input in, String inputJson) {
    PmartPrincipal who = require(EDITORS, "행사 만들기");
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createPromotion", who.subject()),
                inputJson,
                String.class,
                () -> {
                  String pid = "PRM-" + UUID.randomUUID();
                  save(pid, in, inputJson, who.subject(), now());
                  return pid;
                })
            .value();
    return view(id);
  }

  public View update(
      String promotionId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expectedVersion,
      Input in,
      String inputJson) {
    PmartPrincipal who = require(EDITORS, "행사 고치기");
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "updatePromotion:" + promotionId, who.subject()),
        inputJson,
        String.class,
        () -> {
          PromotionRow r = locked(promotionId);
          if (!"DRAFT".equals(r.state()) && !"REJECTED".equals(r.state())) {
            throw PmartException.invalidState("제출·발효된 행사는 고칠 수 없습니다(지금 " + r.state() + ").");
          }
          if (expectedVersion != null && expectedVersion != r.version()) {
            throw PmartException.versionConflict("다른 사람이 먼저 고쳤습니다 — 다시 불러오세요.");
          }
          save(promotionId, in, inputJson, r.createdBy(), now());
          return promotionId;
        });
    return view(promotionId);
  }

  private void save(String id, Input in, String inputJson, String createdBy, Instant at) {
    PromotionRules.CouponPolicy c = in.coupon();
    store.saveDraft(
        new PromotionRow(
            id,
            in.kind(),
            in.name(),
            "DRAFT",
            in.startsAt(),
            in.endsAt(),
            null,
            in.discount().type(),
            in.discount().value(),
            in.discount().maxAmount(),
            c == null ? null : c.issueMode(),
            c == null ? null : c.minOrderAmount(),
            c == null ? null : c.totalQuantity(),
            c == null ? null : c.perCustomerLimit(),
            c == null ? null : c.useDays(),
            c == null ? null : c.useUntil(),
            0,
            0,
            inputJson,
            null,
            null,
            null,
            null,
            0,
            createdBy,
            at,
            at));
    List<String[]> targets = new ArrayList<>();
    in.targets().forEach(t -> targets.add(new String[] {t.type(), t.id()}));
    store.replaceTargets(id, targets);
    PromotionRules.Recipients rc = c == null ? null : c.recipients();
    recipients.replace(id, rc == null ? null : rc.accountIds(), rc == null ? null : rc.reason());
  }

  /** 고를 수 있는 진열 테마 — 받아 둔 사본(0.3.0). */
  public ThemeTargets themeTargets() {
    require(READERS, "행사 대상 테마 보기");
    var st = themes.state().orElse(null);
    return new ThemeTargets(
        st == null ? 0 : st.layoutVersion(), st == null ? null : st.syncedAt(), themes.all());
  }

  private Map<String, String> themeKinds(List<String[]> targets) {
    return themes.kinds(themeIds(targets));
  }

  private static List<String> themeIds(List<String[]> targets) {
    return targets.stream().filter(t -> "THEME".equals(t[0])).map(t -> t[1]).toList();
  }

  private PromotionRules.@Nullable Recipients recipientsOf(String promotionId) {
    List<RecipientRow> rows = recipients.of(promotionId);
    if (rows.isEmpty()) {
      return null;
    }
    return new PromotionRules.Recipients(
        rows.stream().map(RecipientRow::accountId).toList(),
        recipients.reason(promotionId).orElse(""));
  }

  public View get(String promotionId) {
    require(READERS, "행사 보기");
    return view(promotionId);
  }

  public Page list(
      @Nullable String kind, @Nullable String state, int size, @Nullable String cursor) {
    require(READERS, "행사 보기");
    Instant afterAt = null;
    String afterId = null;
    if (cursor != null && !cursor.isBlank()) {
      try {
        String s = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int bar = s.indexOf('|');
        afterAt = Instant.parse(s.substring(0, bar));
        afterId = s.substring(bar + 1);
      } catch (RuntimeException e) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다.");
      }
    }
    int limit = Math.max(1, Math.min(size, 200));
    List<PromotionRow> rows = store.list(kind, state, afterAt, afterId, limit + 1);
    String next = null;
    if (rows.size() > limit) {
      rows = rows.subList(0, limit);
      PromotionRow last = rows.get(limit - 1);
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (last.updatedAt() + "|" + last.promotionId()).getBytes(StandardCharsets.UTF_8));
    }
    return new Page(rows.stream().map(this::view).toList(), next);
  }

  /** 발효 제출 — 검사 → 해시 잠금 → 승인 요청 본문(PROMOTION, ACTIVATE_PROMOTION). */
  public Submission submit(String promotionId, String requestId, String sourceSystem) {
    PmartPrincipal who = require(EDITORS, "행사 제출");
    return idempotency
        .execute(
            new RequestKey(
                sourceSystem, requestId, "submitPromotion:" + promotionId, who.subject()),
            promotionId,
            Submission.class,
            () -> {
              PromotionRow r = locked(promotionId);
              if (!"DRAFT".equals(r.state()) && !"REJECTED".equals(r.state())) {
                throw PmartException.invalidState("이미 제출·발효된 행사입니다(지금 " + r.state() + ").");
              }
              List<String[]> targets = store.targets(promotionId);
              PromotionRules.Recipients rc = recipientsOf(promotionId);
              List<Problem> problems =
                  PromotionRules.check(
                      PromotionInputs.fromRow(r, targets, rc), now(), themeKinds(targets));
              if (!problems.isEmpty()) {
                throw new PmartException(
                    "PROMOTION_INVALID",
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "행사에 고칠 곳이 있습니다.",
                    false,
                    problems.stream()
                        .map(p -> new PmartException.Detail(p.field(), p.reason()))
                        .toList());
              }
              String hash = PromotionRules.sha256(r.input());
              store.submitted(promotionId, hash, now());
              return new Submission(
                  promotionId,
                  hash,
                  approvalRequest(
                      r,
                      hash,
                      "ACTIVATE_PROMOTION",
                      "ACTIVATE",
                      summary(r, rc),
                      null,
                      rc == null ? null : rc.accountIds().size()));
            })
        .value();
  }

  /** 조기 종료 제출 — 진행 중·예정(APPROVED) 행사만. */
  public Submission submitEnd(
      String promotionId, String requestId, String sourceSystem, String reason) {
    PmartPrincipal who = require(EDITORS, "행사 조기 종료");
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "endPromotion:" + promotionId, who.subject()),
            Map.of("promotionId", promotionId, "reason", reason),
            Submission.class,
            () -> {
              PromotionRow r = locked(promotionId);
              if (!"APPROVED".equals(r.state())) {
                throw PmartException.invalidState("승인된 행사만 끝낼 수 있습니다(지금 " + r.state() + ").");
              }
              String hash =
                  PromotionRules.sha256("END|" + promotionId + "|" + r.version() + "|" + reason);
              store.endSubmitted(promotionId, hash, reason, now());
              return new Submission(
                  promotionId,
                  hash,
                  approvalRequest(
                      r, hash, "END_PROMOTION", "END", "행사 조기 종료 — " + r.name(), reason, null));
            })
        .value();
  }

  private static String summary(PromotionRow r, PromotionRules.@Nullable Recipients rc) {
    String d = "RATE".equals(r.discountType()) ? r.discountValue() + "%" : r.discountValue() + "원";
    String s = ("PRICE_DISCOUNT".equals(r.kind()) ? "기간 할인 " : "장바구니 쿠폰 ") + r.name() + " — " + d;
    // 0.3.0 — 심사자가 몇 명에게 나가는지 알게(사용자 "추천안대로")
    return rc == null ? s : s + " · 받을 고객 " + rc.accountIds().size() + "명(시작 때 발급)";
  }

  /** approval-service POST /change-requests 본문(PROMOTION, 정본 promotion-service). */
  private static Map<String, Object> approvalRequest(
      PromotionRow r,
      String hash,
      String allowedAction,
      String action,
      String summary,
      @Nullable String reason,
      @Nullable Integer recipientCount) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("promotionId", r.promotionId());
    attrs.put("action", action);
    attrs.put("draftHash", hash);
    attrs.put("kind", r.kind());
    attrs.put("name", r.name());
    attrs.put("startsAt", r.startsAt().toString());
    attrs.put("endsAt", r.endsAt().toString());
    attrs.put("discountType", r.discountType());
    attrs.put("discountValue", r.discountValue());
    if (reason != null) {
      attrs.put("reason", reason);
    }
    if (recipientCount != null) {
      attrs.put("recipientCount", recipientCount);
    }
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("lineKey", r.promotionId());
    line.put("attributes", attrs);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "PROMOTION");
    m.put("targetService", TARGET_SERVICE);
    m.put("targetRef", r.promotionId());
    m.put("allowedActions", List.of(allowedAction));
    m.put("summary", summary);
    m.put("lines", List.of(line));
    return m;
  }

  View view(String promotionId) {
    return view(
        store
            .find(promotionId)
            .orElseThrow(() -> PmartException.notFound("PROMOTION_NOT_FOUND", "행사가 없습니다.")));
  }

  View view(PromotionRow r) {
    Instant now = now();
    List<String[]> targets = store.targets(r.promotionId());
    PromotionRules.Recipients rc = recipientsOf(r.promotionId());
    List<Problem> problems =
        ("DRAFT".equals(r.state()) || "REJECTED".equals(r.state()))
            ? PromotionRules.check(
                PromotionInputs.fromRow(r, targets, rc), now, themeKinds(targets))
            : List.of();
    return new View(
        r,
        targets,
        problems,
        PromotionRules.phase(r.state(), r.startsAt(), r.endsAt(), r.endedAt(), now),
        themes.titles(themeIds(targets)),
        rc,
        rc == null ? List.of() : recipients.of(r.promotionId()));
  }

  private PromotionRow locked(String promotionId) {
    return store
        .lock(promotionId)
        .orElseThrow(() -> PmartException.notFound("PROMOTION_NOT_FOUND", "행사가 없습니다."));
  }

  private Instant now() {
    return clock.instant();
  }
}
