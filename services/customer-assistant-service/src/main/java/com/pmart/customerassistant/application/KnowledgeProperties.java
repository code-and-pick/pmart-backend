package com.pmart.customerassistant.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code pmart.assistant.knowledge.*}(ADR-0094 고객 RAG 문서 관리 — ADR-0092 와 같은 구조). 값의 정본은 설정 저장소
 * {@code customer-assistant-service.yml}(ADR-0029).
 */
@ConfigurationProperties("pmart.assistant")
public class KnowledgeProperties {

  private Knowledge knowledge = new Knowledge();

  public static class Knowledge {
    /**
     * 색인 원본: {@code files}(저장소 manifest — 예전 그대로, 기본값) | {@code db}(문서 정본 DB, ADR-0092). db 면 색인
     * 작업이 먼저 없는 문서만 저장소에서 가져오고, 승인 반영 뒤 1분 주기 재색인이 돈다.
     */
    private String source = "files";

    /** 초안을 만들고 고칠 수 있는 역할(결정 4 기본값 OPERATOR_ADMIN). 승인 요청 역할은 approval-service 설정이 따로 본다. */
    private List<String> editorRoles = new ArrayList<>(List.of("OPERATOR_ADMIN"));

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public boolean dbSource() {
      return "db".equalsIgnoreCase(source);
    }

    public List<String> getEditorRoles() {
      return editorRoles;
    }

    public void setEditorRoles(List<String> editorRoles) {
      this.editorRoles = editorRoles;
    }
  }

  public Knowledge getKnowledge() {
    return knowledge;
  }

  public void setKnowledge(Knowledge knowledge) {
    this.knowledge = knowledge;
  }
}
