package com.pmart.customerassistant.application;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClientResponseException;

/**
 * 의존성 실패를 로그에 남길 한 줄 — 예외 이름의 연쇄, 우리가 정한 고정 문구({@link AnswerGenerator.DependencyFailure}), HTTP
 * 상태만.
 *
 * <p>다른 예외의 메시지는 남기지 않는다(모델 응답·질문 일부가 섞일 수 있다). 2026-11-16: LAN 503 이 "DependencyFailure" 이름만 남아
 * Ollama 오류인지 응답이 비었는지 알 수 없었다. 운영자용 서비스(ai-assistant-service)에도 같은 클래스가 있다 — 한쪽을 고치면 같이 고친다.
 */
final class FailureSummary {

  private static final int MAX_DEPTH = 5;

  private FailureSummary() {}

  static String of(@Nullable Throwable failure) {
    List<String> parts = new ArrayList<>();
    Throwable t = failure;
    for (int i = 0; t != null && i < MAX_DEPTH; i++, t = t.getCause()) {
      String part = t.getClass().getSimpleName();
      if (t instanceof AnswerGenerator.DependencyFailure) {
        part += "(" + t.getMessage() + ")";
      } else if (t instanceof RestClientResponseException http) {
        part += "(HTTP " + http.getStatusCode().value() + ")";
      }
      parts.add(part);
    }
    return parts.isEmpty() ? "unknown" : String.join(" > ", parts);
  }
}
