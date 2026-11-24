package com.pmart.product.content.api;

import com.pmart.product.content.api.generated.model.ContentDraft;
import com.pmart.product.content.api.generated.model.ContentImage;
import com.pmart.product.content.api.generated.model.ContentImportBatch;
import com.pmart.product.content.application.ContentDraftService;
import com.pmart.product.content.application.ContentQueries;
import com.pmart.product.content.infrastructure.DraftStore.BatchRow;
import com.pmart.product.content.infrastructure.DraftStore.DraftRow;
import com.pmart.product.content.infrastructure.DraftStore.ImageRow;
import com.pmart.product.content.infrastructure.DraftStore.ItemRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 저장 행 → 계약 DTO 변환(필드 이름을 계약에 맞춘 Map 을 Jackson 으로 바꾼다). */
@Component
class RegistrationMapping {

  private final ObjectMapper mapper;
  private final ContentDraftService drafts;

  RegistrationMapping(ObjectMapper mapper, ContentDraftService drafts) {
    this.mapper = mapper;
    this.drafts = drafts;
  }

  <T> T convert(Object value, Class<T> type) {
    return mapper.convertValue(value, type);
  }

  ContentImage image(ImageRow i) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("imageId", i.imageId());
    m.put("role", i.role());
    m.put("sortOrder", i.sortOrder());
    m.put("url", ContentQueries.IMAGE_PATH + i.imageId());
    m.put("contentType", i.contentType());
    m.put("width", i.width());
    m.put("height", i.height());
    m.put("bytes", i.bytes());
    m.put("sha256", i.sha256());
    return convert(m, ContentImage.class);
  }

  ContentDraft draft(DraftRow d) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("sourceSystem", d.sourceSystem());
    input.put("sourceKey", d.sourceKey());
    input.put("productId", d.productId());
    input.put("name", d.name());
    input.put("description", d.description());
    input.put("storageGuide", d.storageGuide());
    input.put("returnGuide", d.returnGuide());
    input.put("notices", mapper.readTree(d.notices()));
    input.put("options", mapper.readTree(d.options()));
    if (d.labelFacts() != null) { // 0.6.0 표시사항 값
      tools.jackson.databind.JsonNode f = mapper.readTree(d.labelFacts());
      input.put("nutrition", f.get("nutrition"));
      input.put("allergens", f.get("allergens"));
    }
    var problems = drafts.problems(d);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("draftId", d.draftId());
    m.put("sourceSystem", d.sourceSystem());
    m.put("sourceKey", d.sourceKey());
    m.put("productId", d.productId());
    m.put("state", d.state());
    m.put("input", input);
    m.put("problems", problems);
    m.put("warnings", drafts.warnings(d)); // 0.8.0 사진 규격 — 막지 않음
    m.put("submittable", "DRAFT".equals(d.state()) && problems.isEmpty());
    m.put("draftHash", d.draftHash());
    m.put("approvalId", d.approvalId());
    m.put("contentVersion", d.contentVersion());
    // BR-44: 심사 전 초안이면 신규 / 기존 게시 콘텐츠 갱신
    if ("DRAFT".equals(d.state()) || "SUBMITTED".equals(d.state())) {
      var published = drafts.existingPublished(d);
      m.put("registration", published.isPresent() ? "UPDATE" : "NEW");
      published.ifPresent(
          p -> {
            m.put("publishedContentVersion", p.contentVersion());
            m.put("publishedDraftId", p.draftId());
            m.put("publishedAt", p.publishedAt().toString());
          });
    }
    m.put("version", d.version());
    m.put("updatedAt", d.updatedAt().toString());
    ContentDraft out = convert(m, ContentDraft.class);
    return out.images(drafts.images(d.draftId()).stream().map(this::image).toList());
  }

  ContentImportBatch batch(BatchRow b, List<ItemRow> items) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("batchId", b.batchId());
    m.put("format", b.format());
    m.put("sourceSystem", b.sourceSystem());
    m.put("state", b.state());
    m.put("productCount", b.productCount());
    m.put("createdCount", b.createdCount());
    m.put("updatedCount", b.updatedCount());
    m.put("unchangedCount", b.unchangedCount());
    m.put("imageCount", b.imageCount());
    m.put("uploadedImageCount", b.uploadedImageCount());
    m.put("createdAt", b.createdAt().toString());
    // BR-41: 승인 대기 중이면 제출 결과(승인 요청 본문)를 다시 준다
    drafts.pendingSubmission(b.batchId()).ifPresent(s -> m.put("pendingSubmission", s));
    ContentImportBatch out = convert(m, ContentImportBatch.class);
    List<com.pmart.product.content.api.generated.model.ContentImportBatchItemsInner> rows =
        new ArrayList<>();
    for (ItemRow i : items) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("sourceKey", i.sourceKey());
      r.put("draftId", i.draftId());
      r.put("outcome", i.outcome());
      var row =
          convert(
              r, com.pmart.product.content.api.generated.model.ContentImportBatchItemsInner.class);
      rows.add(row.draft(draft(drafts.get(i.draftId()))));
    }
    return out.items(rows);
  }
}
