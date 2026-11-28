package com.pmart.embedding.domain;

/**
 * 접근 범위 = ES 별칭(chunking-service AccessScope 와 같은 이름 — ADR-0096). 범위마다 벡터를 따로 둔다(고객 검색에 내부 문서가 섞이지
 * 않게).
 */
public enum Scope {
  OPERATOR("rag-operator"),
  CUSTOMER("rag-customer");

  private final String alias;

  Scope(String alias) {
    this.alias = alias;
  }

  public String alias() {
    return alias;
  }
}
