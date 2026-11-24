package com.pmart.product.content.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.content.api.generated.RegistrationApi;
import com.pmart.product.content.api.generated.model.ContentDraft;
import com.pmart.product.content.api.generated.model.ContentDraftInput;
import com.pmart.product.content.api.generated.model.ContentDraftSummary;
import com.pmart.product.content.api.generated.model.ContentImage;
import com.pmart.product.content.api.generated.model.ContentImportBatch;
import com.pmart.product.content.api.generated.model.ContentSubmissionResult;
import com.pmart.product.content.api.generated.model.ImageRole;
import com.pmart.product.content.api.generated.model.ListContentDrafts200Response;
import com.pmart.product.content.api.generated.model.ListContentDrafts200ResponsePage;
import com.pmart.product.content.api.generated.model.StatusCounts;
import com.pmart.product.content.api.generated.model.StatusCountsCountsInner;
import com.pmart.product.content.application.ContentDraftListing;
import com.pmart.product.content.application.ContentDraftService;
import com.pmart.product.content.application.Operator;
import com.pmart.product.content.domain.ContentInput;
import com.pmart.product.content.infrastructure.DraftStore;
import com.pmart.product.content.infrastructure.DraftStore.BatchRow;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/** 계약 RegistrationApi — 임시 백오피스 콘텐츠 등록. 업무 규칙은 application/domain 에 있고 여기서는 변환만 한다. */
@RestController
public class RegistrationController implements RegistrationApi {

  private final ContentDraftService drafts;
  private final RegistrationMapping mapping;
  private final ObjectMapper mapper;
  private final ContentDraftListing listing;

  /** 상품 사진 규격(0.8.0) — 초안 warnings 와 같은 값. 직원 토큰이면 누구나. */
  @Override
  public org.springframework.http.ResponseEntity<
          com.pmart.product.content.api.generated.model.GetContentImageSpecs200Response>
      getContentImageSpecs() {
    var items =
        new java.util.ArrayList<
            com.pmart.product.content.api.generated.model
                .GetContentImageSpecs200ResponseItemsInner>();
    items.add(
        new com.pmart.product.content.api.generated.model
                .GetContentImageSpecs200ResponseItemsInner()
            .role(com.pmart.product.content.api.generated.model.ImageRole.MAIN)
            .ratioLabel("1:1")
            .ratioTolerance(
                java.math.BigDecimal.valueOf(
                    com.pmart.product.content.domain.ContentImageSpec.RATIO_TOLERANCE))
            .recommendedWidth(com.pmart.product.content.domain.ContentImageSpec.MAIN_MIN)
            .recommendedHeight(com.pmart.product.content.domain.ContentImageSpec.MAIN_MIN)
            .maxCount(1));
    for (var role :
        java.util.List.of(
            com.pmart.product.content.api.generated.model.ImageRole.DESCRIPTION,
            com.pmart.product.content.api.generated.model.ImageRole.DETAIL)) {
      items.add(
          new com.pmart.product.content.api.generated.model
                  .GetContentImageSpecs200ResponseItemsInner()
              .role(role)
              .recommendedWidth(
                  com.pmart.product.content.domain.ContentImageSpec.DETAIL_MIN_WIDTH));
    }
    return org.springframework.http.ResponseEntity.ok(
        new com.pmart.product.content.api.generated.model.GetContentImageSpecs200Response()
            .maxBytes((long) com.pmart.product.content.domain.ContentRules.MAX_IMAGE_BYTES)
            .contentTypes(
                new java.util.ArrayList<>(
                    java.util.List.of("image/jpeg", "image/png", "image/webp")))
            .maxImagesPerDraft(com.pmart.product.content.domain.ContentRules.MAX_IMAGES_PER_DRAFT)
            .items(items));
  }

  public RegistrationController(
      ContentDraftService drafts,
      RegistrationMapping mapping,
      ObjectMapper mapper,
      ContentDraftListing listing) {
    this.drafts = drafts;
    this.mapping = mapping;
    this.mapper = mapper;
    this.listing = listing;
  }

  /** 0.4.0 PCT-1 CD-1: 초안 목록(최근 바뀐 순). */
  @Override
  public ResponseEntity<ListContentDrafts200Response> listContentDrafts(
      @Nullable List<String> state,
      @Nullable String productId,
      Integer size,
      @Nullable String cursor) {
    ContentDraftListing.Page page =
        listing.list(state, productId, size == null ? 50 : size, cursor);
    ListContentDrafts200Response out =
        new ListContentDrafts200Response()
            .page(
                new ListContentDrafts200ResponsePage()
                    .size(page.rows().size())
                    .nextCursor(page.nextCursor()));
    for (DraftStore.SummaryRow r : page.rows()) {
      out.addItemsItem(
          new ContentDraftSummary()
              .draftId(r.draftId())
              .productId(r.productId())
              .state(ContentDraftSummary.StateEnum.fromValue(r.state()))
              .name(r.name())
              .imageId(r.imageId())
              .approvalId(r.approvalId())
              .updatedAt(r.updatedAt().atOffset(ZoneOffset.UTC))
              .version(r.version()));
    }
    return ResponseEntity.ok(out);
  }

  /** 0.4.0 PCT-1 CD-2: 상태별 수(모든 상태 0 까지). */
  @Override
  public ResponseEntity<StatusCounts> countContentDrafts(@Nullable String productId) {
    StatusCounts out = new StatusCounts().asOf(OffsetDateTime.now(ZoneOffset.UTC));
    listing
        .counts(productId)
        .forEach(
            (k, n) ->
                out.addCountsItem(new StatusCountsCountsInner().key(k).count(n).capped(false)));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<ContentDraft> createContentDraft(
      String idempotencyKey, String xSourceSystem, ContentDraftInput body) {
    var row = drafts.create(idempotencyKey, Operator.current(xSourceSystem), input(body)).value();
    return ResponseEntity.status(HttpStatus.CREATED).body(mapping.draft(drafts.get(row.draftId())));
  }

  @Override
  public ResponseEntity<ContentDraft> getContentDraft(String draftId) {
    Operator.require();
    return ResponseEntity.ok(mapping.draft(drafts.get(draftId)));
  }

  @Override
  public ResponseEntity<ContentDraft> updateContentDraft(
      String draftId,
      String idempotencyKey,
      String xSourceSystem,
      ContentDraftInput body,
      @Nullable Integer xExpectedVersion) {
    var row =
        drafts
            .update(
                idempotencyKey,
                Operator.current(xSourceSystem),
                draftId,
                xExpectedVersion,
                input(body))
            .value();
    return ResponseEntity.ok(mapping.draft(drafts.get(row.draftId())));
  }

  @Override
  public ResponseEntity<ContentImage> uploadContentImage(
      String draftId,
      String idempotencyKey,
      String xSourceSystem,
      MultipartFile file,
      ImageRole role,
      @Nullable Integer sortOrder) {
    var row =
        drafts
            .uploadImage(
                idempotencyKey,
                Operator.current(xSourceSystem),
                draftId,
                com.pmart.product.content.domain.ImageRole.valueOf(role.getValue()),
                sortOrder,
                bytes(file))
            .value();
    return ResponseEntity.status(HttpStatus.CREATED).body(mapping.image(row));
  }

  @Override
  public ResponseEntity<ContentSubmissionResult> submitContentDraft(
      String draftId, String idempotencyKey, String xSourceSystem) {
    var s = drafts.submit(idempotencyKey, Operator.current(xSourceSystem), draftId).value();
    return ResponseEntity.ok(mapping.convert(s, ContentSubmissionResult.class));
  }

  @Override
  public ResponseEntity<ContentImportBatch> uploadContentImportBatch(
      String idempotencyKey, String xSourceSystem, MultipartFile file) {
    BatchRow b =
        drafts
            .uploadBatch(
                idempotencyKey,
                Operator.current(xSourceSystem),
                bundle(file),
                file.getOriginalFilename())
            .value();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(mapping.batch(drafts.batch(b.batchId()), drafts.batchItems(b.batchId())));
  }

  @Override
  public ResponseEntity<ContentImportBatch> getContentImportBatch(String batchId) {
    Operator.require();
    return ResponseEntity.ok(mapping.batch(drafts.batch(batchId), drafts.batchItems(batchId)));
  }

  @Override
  public ResponseEntity<ContentSubmissionResult> submitContentImportBatch(
      String batchId, String idempotencyKey, String xSourceSystem) {
    var s = drafts.submitBatch(idempotencyKey, Operator.current(xSourceSystem), batchId).value();
    return ResponseEntity.ok(mapping.convert(s, ContentSubmissionResult.class));
  }

  private ContentInput input(ContentDraftInput body) {
    return mapper.convertValue(body, ContentInput.class);
  }

  /** 업로드 파일을 메모리에 올리지 않는다 — 해시는 스트림으로, 읽을 때 다시 연다(BR-48: getBytes() 로 큰 묶음 OOM). */
  private static com.pmart.product.content.application.UploadedBundle bundle(MultipartFile file) {
    try {
      return com.pmart.product.content.application.UploadedBundle.from(file::getInputStream);
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "업로드 파일을 읽을 수 없습니다.");
    }
  }

  /** 이미지 한 장(작음, 상한은 multipart 설정). */
  private static byte[] bytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "업로드 파일을 읽을 수 없습니다.");
    }
  }
}
