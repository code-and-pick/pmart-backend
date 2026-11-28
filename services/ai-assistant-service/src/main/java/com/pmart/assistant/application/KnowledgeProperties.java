package com.pmart.assistant.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.assistant.knowledge.*}(ADR-0092 문서 관리)와 {@code
 * pmart.assistant.consult-guide.*}(ADR-0093 상담 도우미). 값의 정본은 설정 저장소 {@code
 * ai-assistant-service.yml}(ADR-0029).
 */
@ConfigurationProperties("pmart.assistant")
public class KnowledgeProperties {

  private Knowledge knowledge = new Knowledge();
  private ConsultGuide consultGuide = new ConsultGuide();

  public static class Knowledge {
    /**
     * 색인 원본: {@code files}(저장소 manifest — 예전 그대로, 기본값) | {@code db}(문서 정본 DB, ADR-0092). db 면 색인
     * 작업이 먼저 없는 문서만 저장소에서 가져오고, 승인 반영 뒤 1분 주기 재색인이 돈다.
     */
    private String source = "files";

    /** 초안을 만들고 고칠 수 있는 역할(결정 4 기본값 OPERATOR_ADMIN). 승인 요청 역할은 approval-service 설정이 따로 본다. */
    private List<String> editorRoles = new ArrayList<>(List.of("OPERATOR_ADMIN"));

    /**
     * 같이 쓰는 문서(customer_app)의 원본 — 고객 RAG 주소(ADR-0094 결정 3, LAN
     * http://customer-assistant-service:8509). 비어 있으면 맞추지 않는다(가져온 사본 그대로).
     */
    private @org.jspecify.annotations.Nullable String sharedSourceBaseUrl;

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public boolean dbSource() {
      return "db".equalsIgnoreCase(source);
    }

    public @org.jspecify.annotations.Nullable String getSharedSourceBaseUrl() {
      return sharedSourceBaseUrl;
    }

    public void setSharedSourceBaseUrl(@org.jspecify.annotations.Nullable String v) {
      this.sharedSourceBaseUrl = v;
    }

    public List<String> getEditorRoles() {
      return editorRoles;
    }

    public void setEditorRoles(List<String> editorRoles) {
      this.editorRoles = editorRoles;
    }
  }

  public static class ConsultGuide {
    /** 쓸 수 있는 역할(결정 4 기본값 — 상담 접수를 쓰는 역할). */
    private List<String> roles = new ArrayList<>(List.of("CS_AGENT", "OPERATOR_ADMIN"));

    /** 본인 확인 절을 찾는 검색어(1위 조각 하나). 비어 있으면 본인 확인 없이. */
    private String identityQuery = "";

    /** 검색어마다 가져올 근거 수. */
    private int perQuery = 2;

    /** 정책 근거 최대 개수(본인 확인 제외). */
    private int maxPolicies = 6;

    /** 문의 종류(ORDER·DELIVERY…) → 검색어. 없는 종류는 OTHER 를 쓴다. */
    private Map<String, List<String>> categories = new LinkedHashMap<>();

    /** 주문 상태(주문 서비스 값) → 덧붙일 검색어. */
    private Map<String, List<String>> orderStatuses = new LinkedHashMap<>();

    public List<String> getRoles() {
      return roles;
    }

    public void setRoles(List<String> roles) {
      this.roles = roles;
    }

    public String getIdentityQuery() {
      return identityQuery;
    }

    public void setIdentityQuery(String identityQuery) {
      this.identityQuery = identityQuery;
    }

    public int getPerQuery() {
      return perQuery;
    }

    public void setPerQuery(int perQuery) {
      this.perQuery = perQuery;
    }

    public int getMaxPolicies() {
      return maxPolicies;
    }

    public void setMaxPolicies(int maxPolicies) {
      this.maxPolicies = maxPolicies;
    }

    public Map<String, List<String>> getCategories() {
      return categories;
    }

    public void setCategories(Map<String, List<String>> categories) {
      this.categories = categories;
    }

    public Map<String, List<String>> getOrderStatuses() {
      return orderStatuses;
    }

    public void setOrderStatuses(Map<String, List<String>> orderStatuses) {
      this.orderStatuses = orderStatuses;
    }
  }

  public Knowledge getKnowledge() {
    return knowledge;
  }

  public void setKnowledge(Knowledge knowledge) {
    this.knowledge = knowledge;
  }

  public ConsultGuide getConsultGuide() {
    return consultGuide;
  }

  public void setConsultGuide(ConsultGuide consultGuide) {
    this.consultGuide = consultGuide;
  }
}
