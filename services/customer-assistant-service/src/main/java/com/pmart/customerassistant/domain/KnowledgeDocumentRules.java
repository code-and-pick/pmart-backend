package com.pmart.customerassistant.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 고객 RAG 문서 형식 규칙(ADR-0094 — ADR-0092 결정 3 과 같은 기준). 초안을 저장할 때 본다 — 프레임워크 없음.
 *
 * <ul>
 *   <li>문서 ID = {@code docs/rag/rag-docs/<영역>/<이름>.md}, 영역은 지금 있는 폴더만
 *   <li>본문: 첫 줄 {@code # 제목}, {@value #MAX_BYTES} 바이트 이하, 금지 표현 없음({@code RealDocumentsIngestTest}
 *       와 같은 기준 — 정해지지 않은 내용을 넣지 않는다)
 *   <li>{@code customer_app} 은 저장소가 정본(고객 RAG 와 같은 파일, 결정 2) — 고치기 막음
 * </ul>
 */
public final class KnowledgeDocumentRules {

  public static final String PREFIX = "docs/rag/rag-docs/customer_app/";
  public static final String FAQ_PREFIX = "docs/business/customer-faq/";
  public static final int MAX_BYTES = 128 * 1024;

  /** 영역 이름 = 고객 RAG manifest 의 domain(AI 답변 근거 / FAQ 목록 원본). */
  public static final String APP_DOMAIN = "customer-app";

  public static final String FAQ_DOMAIN = "customer-faq";

  /** 저장소가 정본인 영역 — 고객 RAG 에는 없다(ADR-0094 — 모두 DB). */
  public static final Set<String> READ_ONLY_DOMAINS = Set.of();

  /** 금지 표현 — 정해지지 않은 내용의 표시. FAQ 문서는 지위 태그([확정 정책])를 쓰는 형식이라 검사하지 않는다(FaqExtractor 가 뗀다). */
  public static final List<String> FORBIDDEN_PHRASES = List.of("가정", "설계안", "확정 정책");

  private static final Pattern APP_ID =
      Pattern.compile("docs/rag/rag-docs/customer_app/([A-Za-z0-9_-]{1,100})\\.md");
  private static final Pattern FAQ_ID =
      Pattern.compile("docs/business/customer-faq/([A-Za-z0-9_-]{1,100})\\.md");

  private KnowledgeDocumentRules() {}

  /** 형식 문제 하나(필드·설명). */
  public record Problem(String field, String message) {}

  /** 문서 ID 의 영역, 형식이 아니면 null. */
  public static @Nullable String domainOf(@Nullable String documentId) {
    if (documentId == null) {
      return null;
    }
    if (APP_ID.matcher(documentId).matches()) {
      return APP_DOMAIN;
    }
    if (FAQ_ID.matcher(documentId).matches()) {
      return FAQ_DOMAIN;
    }
    return null;
  }

  public static boolean readOnly(String documentId) {
    String d = domainOf(documentId);
    return d != null && READ_ONLY_DOMAINS.contains(d);
  }

  /** 첫 줄 {@code # 제목} 의 제목, 없으면 null. */
  public static @Nullable String title(@Nullable String content) {
    if (content == null) {
      return null;
    }
    String first = content.strip().lines().findFirst().orElse("");
    if (!first.startsWith("# ")) {
      return null;
    }
    String t = first.substring(2).strip();
    return t.isEmpty() || t.length() > 300 ? null : t;
  }

  /** 초안 검사 — 문제가 없으면 빈 목록. */
  public static List<Problem> check(
      @Nullable String documentId, String op, @Nullable String content) {
    List<Problem> bad = new ArrayList<>();
    if (domainOf(documentId) == null) {
      bad.add(
          new Problem(
              "documentId",
              "문서 ID 는 " + PREFIX + "<영문·숫자·_- 이름>.md 또는 " + FAQ_PREFIX + "<이름>.md 입니다."));
    }
    if (!"UPSERT".equals(op) && !"RETIRE".equals(op)) {
      bad.add(new Problem("op", "UPSERT 또는 RETIRE 입니다."));
      return bad;
    }
    if ("RETIRE".equals(op)) {
      if (content != null) {
        bad.add(new Problem("content", "내리기 초안에는 본문을 넣지 않습니다."));
      }
      return bad;
    }
    if (content == null || content.isBlank()) {
      bad.add(new Problem("content", "본문이 필요합니다."));
      return bad;
    }
    if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      bad.add(new Problem("content", "본문은 " + MAX_BYTES / 1024 + "KB 이하입니다."));
    }
    if (title(content) == null) {
      bad.add(new Problem("content", "첫 줄은 '# 제목'(300자 이하)입니다."));
    }
    for (String phrase :
        FAQ_DOMAIN.equals(domainOf(documentId)) ? List.<String>of() : FORBIDDEN_PHRASES) {
      if (content.contains(phrase)) {
        bad.add(new Problem("content", "'" + phrase + "' 표현은 넣지 않습니다 — 정해진 내용만 운영자 RAG 문서에 둡니다."));
      }
    }
    return bad;
  }
}
