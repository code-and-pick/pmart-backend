package com.pmart.product.content.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.content.domain.ContentInput;
import com.pmart.product.content.domain.ContentProblem;
import com.pmart.product.content.domain.ContentRules;
import com.pmart.product.content.domain.ContentRules.ImageSlot;
import com.pmart.product.content.domain.ImageFormat;
import com.pmart.product.content.domain.ImageRole;
import com.pmart.product.content.infrastructure.CatalogStore;
import com.pmart.product.content.infrastructure.ContentBundleReader;
import com.pmart.product.content.infrastructure.ContentBundleReader.SeedContent;
import com.pmart.product.content.infrastructure.ContentBundleReader.SeedImage;
import com.pmart.product.content.infrastructure.DraftStore;
import com.pmart.product.content.infrastructure.DraftStore.BatchRow;
import com.pmart.product.content.infrastructure.DraftStore.DraftRow;
import com.pmart.product.content.infrastructure.DraftStore.ImageRow;
import com.pmart.product.content.infrastructure.DraftStore.ItemRow;
import com.pmart.product.content.infrastructure.ImageProbe;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 콘텐츠 초안(개별·seed-v1 배치)·이미지와 제출 준비. 초안은 승인 전까지 고객에게 보이지 않고, 게시는 {@link ContentApprovalApplier} 가
 * DISPLAY 승인 결과로만 한다(ADR-0014). 원천 키(sourceSystem, sourceKey) 가 있으면 열린 초안을 갱신(upsert)해 다시 올려도
 * 초안·이미지가 늘지 않는다. 이미지 본체는 내용 주소(sha256)로 저장소에 한 번만 올린다.
 */
@Service
public class ContentDraftService {

  public static final String DRAFT_PREFIX = "contentDraft:";
  public static final String BATCH_PREFIX = "contentImportBatch:";

  /** 제출 준비 결과(계약 ContentSubmissionResult). */
  public record Submission(
      List<String> submittedDraftIds,
      List<String> excludedDraftIds,
      Map<String, Object> approvalRequest) {}

  /** 이미지 1장 저장 결과. uploaded 는 저장소에 새로 올렸는지. */
  record Stored(ImageRow row, boolean uploaded) {}

  private final DraftStore drafts;
  private final CatalogStore catalog;
  private final ImageStorage storage;
  private final ImageProbe probe;
  private final ContentBundleReader bundles;
  private final RequestIdempotency idempotency;
  private final ObjectMapper mapper;
  private final Clock clock;

  /** 올린 이미지의 축소본을 뒤에서 미리 만든다(2026-11-20). */
  private final VariantPreparer variantPreparer;

  public ContentDraftService(
      DraftStore drafts,
      CatalogStore catalog,
      ImageStorage storage,
      ImageProbe probe,
      ContentBundleReader bundles,
      RequestIdempotency idempotency,
      ObjectMapper mapper,
      Clock clock,
      VariantPreparer variantPreparer) {
    this.variantPreparer = variantPreparer;
    this.drafts = drafts;
    this.catalog = catalog;
    this.storage = storage;
    this.probe = probe;
    this.bundles = bundles;
    this.idempotency = idempotency;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ---------- 개별 초안 ----------

  public Outcome<DraftRow> create(String requestId, Operator op, ContentInput in) {
    return idempotency.execute(
        op.key(requestId, "createContentDraft"),
        in,
        DraftRow.class,
        () -> {
          if (in.hasSource()) {
            Optional<DraftRow> open = drafts.findOpen(in.sourceSystem(), in.sourceKey());
            if (open.isPresent()) {
              requireEditable(open.get());
              replace(open.get(), in);
              return get(open.get().draftId());
            }
          }
          return insert(op, in);
        });
  }

  public Outcome<DraftRow> update(
      String requestId,
      Operator op,
      String draftId,
      @Nullable Integer expectedVersion,
      ContentInput in) {
    return idempotency.execute(
        op.key(requestId, "updateContentDraft"),
        Map.of("draftId", draftId, "input", in),
        DraftRow.class,
        () -> {
          DraftRow d = get(draftId);
          if (expectedVersion != null && expectedVersion != d.version()) {
            throw PmartException.versionConflict(
                "초안이 이미 바뀌었습니다(기대 " + expectedVersion + ", 현재 " + d.version() + ").");
          }
          requireEditable(d);
          replace(d, in);
          return get(draftId);
        });
  }

  public DraftRow get(String draftId) {
    return drafts
        .find(draftId)
        .orElseThrow(() -> PmartException.notFound("DRAFT_NOT_FOUND", "초안이 없습니다: " + draftId));
  }

  public List<ImageRow> images(String draftId) {
    return drafts.images(draftId);
  }

  /** 사진 규격 경고(0.8.0 — 막지 않음, {@link com.pmart.product.content.domain.ContentImageSpec}). */
  public List<ContentProblem> warnings(DraftRow d) {
    return com.pmart.product.content.domain.ContentImageSpec.warnings(
        images(d.draftId()).stream()
            .map(
                i ->
                    new com.pmart.product.content.domain.ContentImageSpec.Slot(
                        i.role(), i.sortOrder(), i.width(), i.height()))
            .toList());
  }

  /** 지금 제출하면 막히는 사유(DRAFT) 또는 저장된 반려 사유. */
  /** 지금 게시돼 있는 콘텐츠(BR-44). */
  public record Published(int contentVersion, String draftId, Instant publishedAt) {}

  /**
   * 심사 전 초안이 이미 게시된 콘텐츠를 갱신하는가(BR-44) — 연결 상품의 최신 게시본, 없으면 같은 원천 키의 최근 게시 초안. 게시·반려된 초안은 대상이
   * 아니다(empty).
   */
  public java.util.Optional<Published> existingPublished(DraftRow d) {
    if (!"DRAFT".equals(d.state()) && !"SUBMITTED".equals(d.state())) {
      return java.util.Optional.empty();
    }
    if (d.productId() != null) {
      var latest = catalog.latestContent(d.productId());
      if (latest.isPresent()) {
        var c = latest.get();
        return java.util.Optional.of(
            new Published(c.contentVersion(), c.draftId(), c.publishedAt()));
      }
    }
    if (d.sourceSystem() != null && d.sourceKey() != null) {
      return drafts
          .findLatestPublished(d.sourceSystem(), d.sourceKey())
          .filter(p -> !p.draftId().equals(d.draftId()) && p.contentVersion() != null)
          .map(p -> new Published(p.contentVersion(), p.draftId(), p.updatedAt()));
    }
    return java.util.Optional.empty();
  }

  public List<ContentProblem> problems(DraftRow d) {
    if ("DRAFT".equals(d.state())) {
      return ContentRules.submissionProblems(d.name(), d.productId(), slots(images(d.draftId())));
    }
    return List.of(mapper.readValue(d.problems(), ContentProblem[].class));
  }

  public Outcome<ImageRow> uploadImage(
      String requestId,
      Operator op,
      String draftId,
      ImageRole role,
      @Nullable Integer sortOrder,
      byte[] bytes) {
    int order = sortOrder == null ? 0 : sortOrder;
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("draftId", draftId);
    request.put("role", role.name());
    request.put("sortOrder", order);
    request.put("sha256", ContentRules.sha256(bytes));
    return idempotency.execute(
        op.key(requestId, "uploadContentImage"),
        request,
        ImageRow.class,
        () -> {
          DraftRow d = get(draftId);
          requireEditable(d);
          ContentRules.slotProblem(role, order).ifPresent(p -> imageInvalid(p));
          ContentRules.fileProblem(bytes).ifPresent(p -> imageInvalid(p));
          List<ImageRow> current = images(draftId);
          boolean newSlot =
              current.stream()
                  .noneMatch(i -> i.role().equals(role.name()) && i.sortOrder() == order);
          if (newSlot && current.size() >= ContentRules.MAX_IMAGES_PER_DRAFT) {
            imageInvalid("초안 1건의 이미지는 최대 " + ContentRules.MAX_IMAGES_PER_DRAFT + "장입니다.");
          }
          Stored s = store(d, role, order, bytes, null, null);
          refreshHash(draftId);
          return s.row();
        });
  }

  public Outcome<Submission> submit(String requestId, Operator op, String draftId) {
    return idempotency.execute(
        op.key(requestId, "submitContentDraft"),
        Map.of("draftId", draftId),
        Submission.class,
        () -> {
          DraftRow d = get(draftId);
          if ("SUBMITTED".equals(d.state())) {
            return submission(DRAFT_PREFIX + draftId, List.of(d), List.of(), summary(d));
          }
          requireEditable(d);
          List<ContentProblem> problems = problems(d);
          if (problems.stream().anyMatch(p -> ContentProblem.PRODUCT_NOT_LINKED.equals(p.code()))) {
            throw new PmartException(
                ContentProblem.PRODUCT_NOT_LINKED,
                ErrorCode.DOMAIN_REJECTED.status(),
                "상품에 연결되지 않은 초안은 제출할 수 없습니다. 상품 승인(SKU 발급) 뒤 다시 시도하세요.",
                false,
                List.of(new PmartException.Detail("productId", "PRODUCT_NOT_LINKED")));
          }
          if (!problems.isEmpty()) {
            throw new PmartException(
                "DRAFT_INVALID",
                ErrorCode.DOMAIN_REJECTED.status(),
                "제출 조건을 채우지 않았습니다.",
                false,
                problems.stream()
                    .map(p -> new PmartException.Detail(p.field(), p.code()))
                    .toList());
          }
          lock(d);
          DraftRow locked = get(draftId);
          return submission(DRAFT_PREFIX + draftId, List.of(locked), List.of(), summary(locked));
        });
  }

  // ---------- 배치(seed-v1) ----------

  public Outcome<BatchRow> uploadBatch(
      String requestId, Operator op, byte[] zip, @Nullable String fileName) {
    return uploadBatch(requestId, op, UploadedBundle.of(zip), fileName);
  }

  public Outcome<BatchRow> uploadBatch(
      String requestId, Operator op, UploadedBundle zip, @Nullable String fileName) {
    return idempotency.execute(
        op.key(requestId, "uploadContentImportBatch"),
        Map.of("sha256", zip.sha256()),
        BatchRow.class,
        () -> {
          List<SeedContent> products;
          try (java.io.InputStream in = zip.open()) {
            products = bundles.read(in);
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          // 이미지 검사를 먼저 끝내 반쯤 적재된 배치를 만들지 않는다
          for (SeedContent p : products) {
            if (p.images().size() > ContentRules.MAX_IMAGES_PER_DRAFT) {
              imageInvalid(p.seedProductId() + ": 이미지가 너무 많습니다.");
            }
            Set<String> slots = new HashSet<>();
            for (SeedImage i : p.images()) {
              ContentRules.slotProblem(i.role(), i.sortOrder())
                  .or(() -> ContentRules.fileProblem(i.bytes()))
                  .ifPresent(msg -> imageInvalid(p.seedProductId() + " " + i.file() + ": " + msg));
              if (!slots.add(i.role() + ":" + i.sortOrder())) {
                imageInvalid(p.seedProductId() + ": 같은 자리의 이미지가 둘입니다(" + i.file() + ").");
              }
            }
          }
          String batchId = "CB-" + UUID.randomUUID();
          Instant at = now();
          String system =
              products.get(0).input().sourceSystem() == null
                  ? ContentBundleReader.DEFAULT_SOURCE_SYSTEM
                  : products.get(0).input().sourceSystem();
          drafts.insert(
              new BatchRow(
                  batchId,
                  "SEED_V1",
                  system,
                  fileName,
                  zip.sha256(),
                  "UPLOADED",
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  op.id(),
                  at));
          int created = 0;
          int updated = 0;
          int unchanged = 0;
          int imageCount = 0;
          int uploaded = 0;
          for (SeedContent p : products) {
            imageCount += p.images().size();
            Upserted u = upsertSeed(op, p);
            uploaded += u.uploaded();
            switch (u.outcome()) {
              case "CREATED" -> created++;
              case "UPDATED" -> updated++;
              case "UNCHANGED" -> unchanged++;
              default -> {}
            }
            drafts.insert(new ItemRow(batchId, p.seedProductId(), u.draftId(), u.outcome()));
          }
          drafts.updateBatchCounts(
              batchId, products.size(), created, updated, unchanged, imageCount, uploaded);
          return batch(batchId);
        });
  }

  public BatchRow batch(String batchId) {
    return drafts
        .findBatch(batchId)
        .orElseThrow(() -> PmartException.notFound("BATCH_NOT_FOUND", "배치가 없습니다: " + batchId));
  }

  public List<ItemRow> batchItems(String batchId) {
    return drafts.items(batchId);
  }

  public Outcome<Submission> submitBatch(String requestId, Operator op, String batchId) {
    return idempotency.execute(
        op.key(requestId, "submitContentImportBatch"),
        Map.of("batchId", batchId),
        Submission.class,
        () -> {
          batch(batchId);
          List<DraftRow> submitted = new ArrayList<>();
          List<String> excluded = new ArrayList<>();
          Set<String> seen = new HashSet<>();
          for (ItemRow item : drafts.items(batchId)) {
            if (!seen.add(item.draftId())) {
              continue;
            }
            DraftRow d = get(item.draftId());
            if ("DRAFT".equals(d.state()) && problems(d).isEmpty()) {
              lock(d);
              submitted.add(get(d.draftId()));
            } else if ("SUBMITTED".equals(d.state())) {
              submitted.add(d);
            } else {
              excluded.add(d.draftId());
            }
          }
          if (submitted.isEmpty()) {
            throw new PmartException(
                "NOTHING_TO_SUBMIT",
                ErrorCode.DOMAIN_REJECTED.status(),
                "제출할 수 있는 초안이 없습니다(상품 연결·상품명·대표 이미지 확인).",
                false,
                List.of());
          }
          drafts.updateBatchState(batchId, "SUBMITTED");
          return submission(
              BATCH_PREFIX + batchId, submitted, excluded, batchSummary(submitted.size(), batchId));
        });
  }

  /** 승인 대기 중인 배치의 제출 결과(BR-41): 잠긴(SUBMITTED) 초안으로 제출 응답과 같은 본문을 다시 만든다. */
  public Optional<Submission> pendingSubmission(String batchId) {
    if (!"SUBMITTED".equals(batch(batchId).state())) {
      return Optional.empty();
    }
    List<DraftRow> submitted = new ArrayList<>();
    List<String> excluded = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (ItemRow item : drafts.items(batchId)) {
      if (!seen.add(item.draftId())) {
        continue;
      }
      DraftRow d = get(item.draftId());
      if ("SUBMITTED".equals(d.state())) {
        submitted.add(d);
      } else {
        excluded.add(d.draftId());
      }
    }
    return submitted.isEmpty()
        ? Optional.empty()
        : Optional.of(
            submission(
                BATCH_PREFIX + batchId,
                submitted,
                excluded,
                batchSummary(submitted.size(), batchId)));
  }

  private static String batchSummary(int count, String batchId) {
    return "상품 콘텐츠 일괄 게시 " + count + "건 (배치 " + batchId + ")";
  }

  // ---------- 상품 연결 (ProductVersionPublished) ----------

  /** 원천 키의 DRAFT 초안을 상품에 연결. inbox 트랜잭션 안에서 호출된다. */
  public int link(String sourceSystem, String sourceKey, String productId) {
    return drafts.linkProduct(sourceSystem, sourceKey, productId, now());
  }

  // ---------- 내부 ----------

  private record Upserted(String draftId, String outcome, int uploaded) {}

  private Upserted upsertSeed(Operator op, SeedContent p) {
    ContentInput in = p.input();
    String noticesJson = json(in.noticesOrEmpty());
    String optionsJson = json(in.optionsOrEmpty());
    List<ImageSlot> incoming = new ArrayList<>();
    for (SeedImage i : p.images()) {
      incoming.add(new ImageSlot(i.role(), i.sortOrder(), ContentRules.sha256(i.bytes())));
    }
    String candidate =
        ContentRules.contentHash(
            in.name(),
            in.description(),
            in.storageGuide(),
            in.returnGuide(),
            noticesJson,
            optionsJson,
            incoming,
            labelFacts(in));

    Optional<DraftRow> open = drafts.findOpen(in.sourceSystem(), in.sourceKey());
    DraftRow target;
    String outcome;
    if (open.isPresent()) {
      DraftRow d = open.get();
      if ("SUBMITTED".equals(d.state())) {
        return new Upserted(d.draftId(), "LOCKED", 0); // 심사 중이면 바꾸지 않는다(승인 범위 고정)
      }
      if (d.contentHash().equals(candidate)) {
        return new Upserted(d.draftId(), "UNCHANGED", 0);
      }
      replace(d, in);
      target = get(d.draftId());
      outcome = "UPDATED";
    } else {
      Optional<DraftRow> published = drafts.findLatestPublished(in.sourceSystem(), in.sourceKey());
      if (published.isPresent() && published.get().contentHash().equals(candidate)) {
        return new Upserted(published.get().draftId(), "UNCHANGED", 0);
      }
      target = insert(op, in);
      outcome = "CREATED";
    }
    // 묶음이 그 상품 이미지의 전체 상태다: 자리별로 맞추고 묶음에 없는 자리는 지운다
    int uploaded = 0;
    Set<String> keep = new HashSet<>();
    for (SeedImage i : p.images()) {
      keep.add(i.role() + ":" + i.sortOrder());
      if (store(target, i.role(), i.sortOrder(), i.bytes(), i.width(), i.height()).uploaded()) {
        uploaded++;
      }
    }
    for (ImageRow old : images(target.draftId())) {
      if (!keep.contains(old.role() + ":" + old.sortOrder())) {
        drafts.deleteImage(old.imageId());
      }
    }
    refreshHash(target.draftId());
    return new Upserted(target.draftId(), outcome, uploaded);
  }

  private DraftRow insert(Operator op, ContentInput in) {
    String productId = resolveProduct(in);
    Instant at = now();
    String notices = json(in.noticesOrEmpty());
    String options = json(in.optionsOrEmpty());
    String facts = labelFacts(in);
    DraftRow row =
        new DraftRow(
            "CD-" + UUID.randomUUID(),
            in.hasSource() ? in.sourceSystem() : null,
            in.hasSource() ? in.sourceKey() : null,
            productId,
            "DRAFT",
            in.name(),
            in.description(),
            in.storageGuide(),
            in.returnGuide(),
            notices,
            options,
            ContentRules.contentHash(
                in.name(),
                in.description(),
                in.storageGuide(),
                in.returnGuide(),
                notices,
                options,
                List.of(),
                facts),
            null,
            null,
            null,
            "[]",
            1,
            op.id(),
            at,
            at,
            facts);
    drafts.insert(row);
    return row;
  }

  /** 입력 교체. 원천 키는 바꾸지 않는다. productId 가 없으면 기존 연결·원천 키 연결을 유지한다. */
  private void replace(DraftRow d, ContentInput in) {
    String productId = in.productId() != null ? resolveProduct(in) : d.productId();
    if (productId == null && d.sourceSystem() != null && d.sourceKey() != null) {
      productId = catalog.productBySource(d.sourceSystem(), d.sourceKey()).orElse(null);
    }
    drafts.replaceInput(
        d.draftId(),
        d.version(),
        productId,
        in.name(),
        in.description(),
        in.storageGuide(),
        in.returnGuide(),
        json(in.noticesOrEmpty()),
        json(in.optionsOrEmpty()),
        labelFacts(in),
        now());
    refreshHash(d.draftId());
  }

  /**
   * 표시사항 값(0.6.0) 저장 모양 — 둘 다 없으면 null. 법정 이름이 아닌 알레르기·음수 영양값 등은 400 VALIDATION_FAILED(지어낸 값·오타를
   * 막는다).
   */
  private @Nullable String labelFacts(ContentInput in) {
    if (in.nutrition() == null && in.allergens() == null) {
      return null;
    }
    List<String> problems =
        com.pmart.product.content.domain.LabelFacts.problems(in.nutrition(), in.allergens());
    if (!problems.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, String.join(" ", problems));
    }
    return json(
        new com.pmart.product.content.domain.LabelFacts.Stored(in.nutrition(), in.allergens()));
  }

  /** 명시 productId 는 수신한 상품이어야 한다. 없으면 원천 키로 이미 받은 상품을 찾는다. */
  private @Nullable String resolveProduct(ContentInput in) {
    if (in.productId() != null && !in.productId().isBlank()) {
      if (catalog.projection(in.productId()).isEmpty()) {
        throw new PmartException(
            "PRODUCT_NOT_FOUND",
            ErrorCode.DOMAIN_REJECTED.status(),
            "아직 수신한 상품이 아닙니다: " + in.productId(),
            false,
            List.of());
      }
      return in.productId();
    }
    if (in.hasSource()) {
      return catalog.productBySource(in.sourceSystem(), in.sourceKey()).orElse(null);
    }
    return null;
  }

  /** 이미지 1장을 자리(role, sortOrder)에 둔다. 같은 내용이 이미 그 자리에 있으면 그대로, 저장소에 같은 sha256 이 있으면 다시 올리지 않는다. */
  private Stored store(
      DraftRow d,
      ImageRole role,
      int sortOrder,
      byte[] bytes,
      @Nullable Integer width,
      @Nullable Integer height) {
    String sha = ContentRules.sha256(bytes);
    Optional<ImageRow> slot =
        images(d.draftId()).stream()
            .filter(i -> i.role().equals(role.name()) && i.sortOrder() == sortOrder)
            .findFirst();
    if (slot.isPresent() && slot.get().sha256().equals(sha)) {
      return new Stored(slot.get(), false);
    }
    ImageFormat format = ImageFormat.detect(bytes).orElseThrow();
    String bucket;
    String key;
    boolean uploaded = false;
    Optional<ImageRow> same = drafts.anyWithSha(sha);
    if (same.isPresent()) {
      bucket = same.get().bucket();
      key = same.get().objectKey();
    } else {
      bucket = storage.bucket();
      String owner = d.productId() != null ? d.productId() : "draft-" + d.draftId();
      key = ContentRules.objectKey(owner, role, sha, format);
      if (!storage.exists(key)) {
        storage.put(key, bytes, format.contentType());
        uploaded = true;
      }
    }
    ImageProbe.Size size = probe.size(bytes);
    slot.ifPresent(s -> drafts.deleteImage(s.imageId()));
    ImageRow row =
        new ImageRow(
            "IMG-" + UUID.randomUUID(),
            d.draftId(),
            role.name(),
            sortOrder,
            bucket,
            key,
            format.contentType(),
            size != null ? Integer.valueOf(size.width()) : width,
            size != null ? Integer.valueOf(size.height()) : height,
            bytes.length,
            sha,
            now());
    drafts.insert(row);
    variantPreparer.afterUpload(); // 커밋 뒤(5초) 축소본을 뒤에서 만든다
    return new Stored(row, uploaded);
  }

  private void refreshHash(String draftId) {
    DraftRow d = get(draftId);
    String hash =
        ContentRules.contentHash(
            d.name(),
            d.description(),
            d.storageGuide(),
            d.returnGuide(),
            // jsonb 는 공백·키 순서를 바꾸므로 타입으로 읽어 같은 방식으로 다시 쓴다
            json(java.util.Arrays.asList(mapper.readValue(d.notices(), String[].class))),
            json(
                java.util.Arrays.asList(
                    mapper.readValue(d.options(), ContentInput.Option[].class))),
            slots(images(draftId)),
            d.labelFacts() == null
                ? null
                : json(
                    mapper.readValue(
                        d.labelFacts(), com.pmart.product.content.domain.LabelFacts.Stored.class)));
    if (!hash.equals(d.contentHash())) {
      drafts.updateContentHash(draftId, hash, now());
    }
  }

  static List<ImageSlot> slots(List<ImageRow> rows) {
    return rows.stream()
        .map(r -> new ImageSlot(ImageRole.valueOf(r.role()), r.sortOrder(), r.sha256()))
        .toList();
  }

  private void lock(DraftRow d) {
    String hash = ContentRules.draftHash(d.contentHash(), d.productId());
    if (!drafts.lock(d.draftId(), d.version(), hash, now())) {
      throw PmartException.versionConflict("초안이 이미 바뀌었습니다. 다시 조회하세요.");
    }
  }

  private Submission submission(
      String targetRef, List<DraftRow> locked, List<String> excluded, String summary) {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (DraftRow d : locked) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("draftId", d.draftId());
      attrs.put("draftHash", d.draftHash());
      attrs.put("productId", d.productId());
      if (d.sourceKey() != null) {
        attrs.put("sourceKey", d.sourceKey());
      }
      attrs.put("name", d.name());
      // BR-44: 승인자가 고객에게 보이던 콘텐츠를 덮어쓰는지 보게
      var published = existingPublished(d);
      attrs.put("registration", published.isPresent() ? "UPDATE" : "NEW");
      published.ifPresent(p -> attrs.put("publishedContentVersion", p.contentVersion()));
      lines.add(
          Map.of(
              "lineKey",
              d.draftId(),
              "targetRef",
              DRAFT_PREFIX + d.draftId(),
              "attributes",
              attrs));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "DISPLAY");
    body.put("targetService", "product-content-service");
    body.put("targetRef", targetRef);
    body.put("allowedActions", List.of("PUBLISH_CONTENT"));
    body.put("summary", summary);
    body.put("lines", lines);
    return new Submission(locked.stream().map(DraftRow::draftId).toList(), excluded, body);
  }

  private static String summary(DraftRow d) {
    return "상품 콘텐츠 게시 1건: " + (d.name() == null ? d.draftId() : d.name());
  }

  private static void requireEditable(DraftRow d) {
    if (!"DRAFT".equals(d.state())) {
      throw new PmartException(
          "DRAFT_NOT_EDITABLE",
          ErrorCode.DOMAIN_REJECTED.status(),
          "제출·게시된 초안은 바꿀 수 없습니다(상태 " + d.state() + ").",
          false,
          List.of());
    }
  }

  private static void imageInvalid(String message) {
    throw new PmartException(
        "IMAGE_INVALID", ErrorCode.DOMAIN_REJECTED.status(), message, false, List.of());
  }

  private String json(Object v) {
    return mapper.writeValueAsString(v);
  }

  private Instant now() {
    return clock.instant();
  }
}
