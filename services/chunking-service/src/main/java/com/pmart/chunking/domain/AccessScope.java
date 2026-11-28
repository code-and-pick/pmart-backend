package com.pmart.chunking.domain;

import java.util.List;

/**
 * 접근 범위 = 들어갈 ES 색인(계획 §1-1 — 고객 색인과 운영자 색인을 나눈다: 내부 문서가 고객 검색에 나오지 않게). 문서 저장소(pmart-all-docs) 경로로
 * 정한다.
 *
 * <ul>
 *   <li>{@code operator/**} → 운영자
 *   <li>{@code customer/customer_app/**} → 고객 + 운영자(고객 앱 안내는 운영자 RAG 도 근거로 쓴다 — 원본 하나, ADR-0094 결정
 *       3)
 *   <li>{@code customer/faq/**} → 고객
 *   <li>그 밖 → 없음(색인하지 않음)
 * </ul>
 */
public enum AccessScope {
  OPERATOR("rag-operator"),
  CUSTOMER("rag-customer");

  private final String alias;

  AccessScope(String alias) {
    this.alias = alias;
  }

  /** 검색이 쓰는 별칭(실제 색인은 {@code <별칭>-v1}). */
  public String alias() {
    return alias;
  }

  public static List<AccessScope> of(String path) {
    if (path.startsWith("operator/")) {
      return List.of(OPERATOR);
    }
    if (path.startsWith("customer/customer_app/")) {
      return List.of(CUSTOMER, OPERATOR);
    }
    if (path.startsWith("customer/faq/")) {
      return List.of(CUSTOMER);
    }
    return List.of();
  }
}
