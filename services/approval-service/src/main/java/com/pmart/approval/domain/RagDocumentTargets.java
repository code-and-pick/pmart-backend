package com.pmart.approval.domain;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.common.web.PmartException.Detail;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * RAG 문서 승인 종류 {@code RAG_DOCUMENT}(ADR-0092·ADR-0094, 2026-11-28)의 변경 요청 형식. 정본에 고객 RAG
 * customer-assistant-service 도 있다(ADR-0094). 정본은 ai-assistant-service — 문서 하나를 초안 하나로 바꾼다(승인 하나 =
 * 문서 하나).
 *
 * <ul>
 *   <li>{@code targetService = ai-assistant-service}, {@code targetRef} = 초안 ID({@code KDR-…})
 *   <li>{@code targetExpectedVersion} 필수(0 이상) — 초안의 기준 판(새 문서는 0). 승인 뒤 지금 판이 다르면 정본이 반영하지 않는다
 *   <li>행 정확히 하나: lineKey = 문서 ID({@code docs/rag/rag-docs/<영역>/<이름>.md}), {@code attributes.op}
 *       UPSERT·RETIRE, {@code attributes.draftId} = targetRef, UPSERT 는 {@code
 *       attributes.contentSha256}(초안 본문 sha256, 소문자 16진 64자)
 * </ul>
 *
 * 형식만 본다 — 초안이 있는지·본문이 같은지는 정본이 반영 때 판정하고 회신한다.
 */
public final class RagDocumentTargets {

  public static final String TYPE = "RAG_DOCUMENT";
  public static final String TARGET_SERVICE = "ai-assistant-service";
  public static final String CUSTOMER_TARGET_SERVICE = "customer-assistant-service";
  static final java.util.Set<String> TARGET_SERVICES =
      java.util.Set.of(TARGET_SERVICE, CUSTOMER_TARGET_SERVICE);

  private static final Pattern DRAFT_ID = Pattern.compile("KDR-[A-Za-z0-9-]{1,36}");
  private static final Pattern DOCUMENT_ID =
      Pattern.compile(
          "docs/rag/rag-docs/[a-z0-9_]{1,40}/[A-Za-z0-9_-]{1,100}\\.md"
              + "|docs/business/customer-faq/[A-Za-z0-9_-]{1,100}\\.md");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  /** 검사할 행. */
  public record Line(
      @Nullable String lineKey,
      @Nullable String op,
      @Nullable String draftId,
      @Nullable String contentSha256) {}

  private RagDocumentTargets() {}

  public static boolean isRagDocumentType(String type) {
    return TYPE.equals(type);
  }

  /**
   * 변경 요청 생성 때 형식 검사.
   *
   * @throws PmartException VALIDATION_FAILED(400) — 어긋난 항목을 details 에
   */
  public static void validate(
      String targetService,
      @Nullable String targetRef,
      @Nullable Integer targetExpectedVersion,
      List<Line> lines) {
    List<Detail> bad = new ArrayList<>();
    if (!TARGET_SERVICES.contains(targetService)) {
      bad.add(
          new Detail(
              "targetService", "RAG 문서 승인의 정본은 " + TARGET_SERVICES + " 중 하나입니다: " + targetService));
    }
    if (targetRef == null || !DRAFT_ID.matcher(targetRef).matches()) {
      bad.add(new Detail("targetRef", "RAG 문서 승인의 대상은 초안 ID(KDR-…)입니다."));
    }
    if (targetExpectedVersion == null || targetExpectedVersion < 0) {
      bad.add(new Detail("targetExpectedVersion", "초안의 기준 판(새 문서는 0)이 필요합니다."));
    }
    if (lines.size() != 1) {
      bad.add(new Detail("lines", "RAG 문서 승인은 문서 하나(행 하나)입니다."));
    } else {
      Line l = lines.get(0);
      if (l.lineKey() == null || !DOCUMENT_ID.matcher(l.lineKey()).matches()) {
        bad.add(
            new Detail(
                "lines[0].lineKey",
                "문서 ID 는 docs/rag/rag-docs/<영역>/<이름>.md 또는 docs/business/customer-faq/<이름>.md 형식입니다."));
      }
      if (!"UPSERT".equals(l.op()) && !"RETIRE".equals(l.op())) {
        bad.add(new Detail("lines[0].attributes.op", "UPSERT 또는 RETIRE 입니다."));
      }
      if (l.draftId() == null || !l.draftId().equals(targetRef)) {
        bad.add(new Detail("lines[0].attributes.draftId", "draftId 는 targetRef 와 같아야 합니다."));
      }
      if ("UPSERT".equals(l.op())
          && (l.contentSha256() == null || !SHA256.matcher(l.contentSha256()).matches())) {
        bad.add(
            new Detail("lines[0].attributes.contentSha256", "초안 본문 sha256(소문자 16진 64자)이 필요합니다."));
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, TYPE + " 변경 요청 형식이 맞지 않습니다.", bad);
    }
  }
}
