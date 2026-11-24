package com.pmart.storefront.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.storefront.domain.LayoutRules;
import com.pmart.storefront.domain.LayoutRules.Body;
import com.pmart.storefront.infrastructure.DisplayStore;
import com.pmart.storefront.infrastructure.LayoutStore;
import com.pmart.storefront.infrastructure.LayoutStore.DraftRow;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 진열 편성 초안·제출·배너 이미지(2026-11-20 ADR-0075 ② — BO-05). 초안은 고객 화면을 바꾸지 않는다. 제출은 검사({@link
 * LayoutRules})를 통과하면 본문 해시를 잠그고 approval-service 로 보낼 변경 요청 본문(종류 DISPLAY, 정본 storefront-service)을
 * 준다. 게시는 승인 사건을 받아 {@link LayoutPublisher} 가 한다.
 *
 * <p>작성자: MD·OPERATOR_ADMIN(가격·전시 담당 — BackOffice 역할표). 명령은 Idempotency-Key 로 멱등.
 */
@Service
public class LayoutService {

  public static final String TARGET_SERVICE = "storefront-service";
  public static final List<String> EDITORS = List.of("MD", "OPERATOR_ADMIN");

  /** 초안 읽기(조회·목록·초안 이미지) — 편성 담당 + DISPLAY 승인자(승인 전에 내용을 본다, 0.7.1). */
  public static final List<String> READERS = List.of("MD", "OPERATOR_ADMIN", "DISPLAY_APPROVER");

  /** 초안 보기(본문은 도메인 모양). */
  /** 초안 + 0.15.0 배너 그림 규격 경고(막지 않음). */
  public record Draft(DraftRow row, Body body, List<LayoutRules.Warning> warnings) {}

  public record Submission(String draftId, String draftHash, Map<String, Object> approvalRequest) {}

  public record Image(LayoutStore.ImageRow row, InputStream body) {}

  private final LayoutStore store;
  private final DisplayStore display;
  private final BannerImageStorage images;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;

  public LayoutService(
      LayoutStore store,
      DisplayStore display,
      BannerImageStorage images,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.display = display;
    this.images = images;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.tx = tx;
    this.clock = clock;
  }

  public static PmartPrincipal editor() {
    PmartPrincipal who = PmartPrincipal.current().orElse(null);
    if (who == null || EDITORS.stream().noneMatch(who::hasRole)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "상품·전시 담당(MD)·운영 관리자만 진열을 편성할 수 있습니다.");
    }
    return who;
  }

  static PmartPrincipal reader() {
    PmartPrincipal who = PmartPrincipal.current().orElse(null);
    if (who == null || READERS.stream().noneMatch(who::hasRole)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "상품·전시 담당(MD)·운영 관리자·전시 승인자만 진열 초안을 볼 수 있습니다.");
    }
    return who;
  }

  public Draft create(String requestId, String sourceSystem, Body body, boolean fromCurrent) {
    PmartPrincipal who = editor();
    String draftId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createLayoutDraft", who.subject()),
                Map.of("body", json(body), "fromCurrent", fromCurrent),
                String.class,
                () -> {
                  Body start = fromCurrent ? currentBody().orElse(Body.empty()) : body;
                  String id = "LD-" + UUID.randomUUID();
                  store.insertDraft(id, json(start), who.subject(), now());
                  return id;
                })
            .value();
    return get(draftId);
  }

  public Draft update(
      String draftId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expected,
      Body body) {
    PmartPrincipal who = editor();
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "updateLayoutDraft", who.subject()),
        Map.of("draftId", draftId, "body", json(body)),
        String.class,
        () -> {
          DraftRow d = find(draftId);
          if (!"DRAFT".equals(d.state()) && !"REJECTED".equals(d.state())) {
            throw PmartException.invalidState("제출·게시된 초안은 고칠 수 없습니다(새 초안을 만드세요).");
          }
          int version = expected == null ? d.version() : expected;
          if (store.updateDraft(draftId, json(body), version, now()) == 0) {
            throw PmartException.versionConflict("초안이 그 사이 바뀌었습니다. 다시 조회한 뒤 고치세요.");
          }
          return draftId;
        });
    return get(draftId);
  }

  public Draft get(String draftId) {
    reader();
    DraftRow d = find(draftId);
    return draft(d);
  }

  public List<Draft> list(@Nullable String state) {
    reader();
    return store.drafts(state, 50).stream().map(this::draft).toList();
  }

  private Draft draft(DraftRow d) {
    Body body = body(d.body());
    return new Draft(
        d,
        body,
        LayoutRules.bannerImageWarnings(
            body,
            id -> store.image(id).isPresent(),
            id ->
                store
                    .image(id)
                    .filter(x -> x.width() != null && x.height() != null)
                    .map(x -> new int[] {x.width(), x.height()})
                    .orElse(null)));
  }

  public int currentVersion() {
    return store.current().map(LayoutStore.VersionRow::layoutVersion).orElse(0);
  }

  /** 제출 — 검사 → 해시 잠금 → 승인 요청 본문. 같은 Idempotency-Key 는 같은 결과. */
  public Submission submit(String draftId, String requestId, String sourceSystem) {
    PmartPrincipal who = editor();
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "submitLayoutDraft", who.subject()),
            Map.of("draftId", draftId),
            Submission.class,
            () ->
                tx.execute(
                    st -> {
                      DraftRow d =
                          store
                              .lockDraft(draftId)
                              .orElseThrow(
                                  () ->
                                      PmartException.notFound(
                                          "DRAFT_NOT_FOUND", "진열 편성 초안이 없습니다."));
                      if (!"DRAFT".equals(d.state()) && !"REJECTED".equals(d.state())) {
                        throw PmartException.invalidState("이미 제출·게시된 초안입니다.");
                      }
                      Body body = body(d.body());
                      List<LayoutRules.Problem> problems =
                          LayoutRules.check(
                              body, display::displayable, id -> store.image(id).isPresent());
                      if (!problems.isEmpty()) {
                        throw new PmartException(
                            "LAYOUT_INVALID",
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            "진열 편성 초안에 고칠 곳이 있습니다.",
                            false,
                            problems.stream()
                                .map(p -> new PmartException.Detail(p.field(), p.reason()))
                                .toList());
                      }
                      String hash = LayoutRules.hash(json(body));
                      store.submit(draftId, hash, now());
                      return new Submission(draftId, hash, approvalRequest(draftId, hash, body));
                    }))
        .value();
  }

  /** approval-service POST /change-requests 본문(DISPLAY, 정본 storefront-service). */
  private static Map<String, Object> approvalRequest(String draftId, String hash, Body body) {
    Map<String, Object> attrs = new LinkedHashMap<>();
    attrs.put("draftId", draftId);
    attrs.put("draftHash", hash);
    attrs.put("banners", body.banners().size());
    attrs.put("themes", body.themes().size());
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("lineKey", draftId);
    line.put("attributes", attrs);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", "DISPLAY");
    m.put("targetService", TARGET_SERVICE);
    m.put("targetRef", draftId);
    m.put("allowedActions", List.of("PUBLISH_LAYOUT"));
    m.put(
        "summary", "홈 진열 편성 — 배너 " + body.banners().size() + "개·테마 " + body.themes().size() + "개");
    m.put("lines", List.of(line));
    return m;
  }

  // ---------------------------------------------------------------- 배너 이미지

  public LayoutStore.ImageRow uploadImage(String requestId, String sourceSystem, byte[] bytes) {
    PmartPrincipal who = editor();
    String type = LayoutRules.imageType(bytes);
    if (type == null || bytes.length > LayoutRules.MAX_IMAGE_BYTES) {
      throw new PmartException(
          "IMAGE_INVALID",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "JPEG·PNG·WEBP, 5MB 이하만 올릴 수 있습니다.",
          false,
          List.of());
    }
    String sha = sha256(bytes);
    String imageId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "uploadBannerImage", who.subject()),
                Map.of("sha256", sha, "size", bytes.length),
                String.class,
                () -> {
                  String id = "BIMG-" + UUID.randomUUID();
                  String key = "banner-images/" + id;
                  images.put(key, bytes, type);
                  int[] size = dimensions(bytes);
                  store.insertImage(
                      new LayoutStore.ImageRow(
                          id,
                          key,
                          type,
                          bytes.length,
                          sha,
                          size == null ? null : size[0],
                          size == null ? null : size[1],
                          who.subject(),
                          now()));
                  return id;
                })
            .value();
    return store.image(imageId).orElseThrow();
  }

  /** 게시된 진열에 쓰인 이미지만(편성 담당·전시 승인자 토큰이면 초안 것도). */
  public Image image(String imageId) {
    LayoutStore.ImageRow row =
        store
            .image(imageId)
            .orElseThrow(() -> PmartException.notFound("IMAGE_NOT_FOUND", "이미지가 없습니다."));
    boolean operator =
        PmartPrincipal.current().map(p -> READERS.stream().anyMatch(p::hasRole)).orElse(false);
    if (!operator && !store.imagePublished(imageId)) {
      throw PmartException.notFound("IMAGE_NOT_FOUND", "이미지가 없습니다.");
    }
    return new Image(row, images.get(row.objectKey()));
  }

  // ---------------------------------------------------------------- 내부

  public Optional<Body> currentBody() {
    return store.current().map(v -> body(v.body()));
  }

  private DraftRow find(String draftId) {
    return store
        .draft(draftId)
        .orElseThrow(() -> PmartException.notFound("DRAFT_NOT_FOUND", "진열 편성 초안이 없습니다."));
  }

  String json(Body body) {
    return mapper.writeValueAsString(body);
  }

  Body body(String json) {
    return mapper.readValue(json, Body.class);
  }

  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 머리말의 가로·세로(다 풀지 않는다 — 작은 파일로 거대한 그림을 막는 리뷰 지적과 같은 이유). */
  private static int @Nullable [] dimensions(byte[] bytes) {
    try (javax.imageio.stream.ImageInputStream in =
        javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      java.util.Iterator<javax.imageio.ImageReader> it = javax.imageio.ImageIO.getImageReaders(in);
      if (!it.hasNext()) {
        return null;
      }
      javax.imageio.ImageReader r = it.next();
      try {
        r.setInput(in, true, true);
        return new int[] {r.getWidth(0), r.getHeight(0)};
      } finally {
        r.dispose();
      }
    } catch (java.io.IOException | RuntimeException e) {
      return null;
    }
  }
}
