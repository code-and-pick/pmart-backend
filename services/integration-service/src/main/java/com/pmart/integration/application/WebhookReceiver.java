package com.pmart.integration.application;

import com.pmart.integration.domain.FileChange;
import com.pmart.integration.domain.PushEvents;
import com.pmart.integration.domain.WebhookSignature;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.MessageHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 웹훅 한 건 판정: 서명 → 이벤트 종류(push·delete·ping) → 저장소·브랜치 → 바뀐 파일 목록. 파일 처리는 흐름 뒤쪽이 따로(비동기) 한다 — 여기서는
 * GitHub 에 빨리 답한다(GitHub 는 10초 안에 답을 기다린다).
 */
@Component
public class WebhookReceiver {

  private static final Logger log = LoggerFactory.getLogger(WebhookReceiver.class);

  /** 같은 전달을 두 번 받으면(중계 재전송) 한 번만 처리한다 — 최근 전달 ID 개수. */
  private static final int SEEN_SIZE = 500;

  private final DocsProperties props;
  private final ObjectMapper mapper;
  private final Set<String> seen =
      Collections.synchronizedSet(
          Collections.newSetFromMap(
              new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                  return size() > SEEN_SIZE;
                }
              }));

  public WebhookReceiver(DocsProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
  }

  /** 웹훅 결과: GitHub 에 줄 상태·결과와, 처리할 파일 변경. */
  public record Reply(HttpStatus status, String result, List<FileChange> changes) {
    public Map<String, Object> body() {
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("result", result);
      b.put("files", changes.size());
      return b;
    }
  }

  public Reply receive(byte[] body, MessageHeaders headers) {
    if (!WebhookSignature.valid(
        props.webhookSecret(), body, header(headers, "x-hub-signature-256"))) {
      log.warn("웹훅 서명 불일치 — 거절");
      return new Reply(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", List.of());
    }
    String event = String.valueOf(header(headers, "x-github-event"));
    String delivery = String.valueOf(header(headers, "x-github-delivery"));
    if (!seen.add(delivery)) {
      return new Reply(HttpStatus.OK, "DUPLICATE_DELIVERY", List.of());
    }
    JsonNode json = mapper.readTree(body);
    String repo = json.path("repository").path("full_name").asString("");
    if (!repo.isEmpty() && !repo.equalsIgnoreCase(props.repository())) {
      log.warn("다른 저장소의 웹훅 — 무시 repository={}", repo);
      return new Reply(HttpStatus.ACCEPTED, "IGNORED_REPOSITORY", List.of());
    }
    switch (event) {
      case "ping":
        return new Reply(HttpStatus.OK, "PONG", List.of());
      case "delete":
        {
          // 브랜치·태그 삭제(파일 삭제는 push 의 removed 로 온다). 따라가는 브랜치가 지워지면 알린다 — 문서는 그대로 둔다
          String ref = json.path("ref").asString("");
          String type = json.path("ref_type").asString("");
          if ("branch".equals(type) && props.branch().equals(ref)) {
            log.warn("따라가는 브랜치가 삭제됨 ref={} — 문서 변경으로 보지 않음", ref);
          } else {
            log.info("ref 삭제 type={} ref={} — 문서 변경 없음", type, ref);
          }
          return new Reply(HttpStatus.ACCEPTED, "REF_DELETED", List.of());
        }
      case "push":
        {
          List<FileChange> changes = PushEvents.changes(json, props.branch(), delivery);
          if (changes.isEmpty()) {
            return new Reply(HttpStatus.ACCEPTED, "NO_CHANGES", List.of());
          }
          log.info(
              "push 받음 commit={} files={} delivery={}",
              json.path("after").asString(""),
              changes.size(),
              delivery);
          return new Reply(HttpStatus.ACCEPTED, "ACCEPTED", changes);
        }
      default:
        return new Reply(HttpStatus.ACCEPTED, "IGNORED_EVENT", List.of());
    }
  }

  /** 헤더 이름 대소문자 무시(HTTP 헤더 매핑이 이름을 바꿀 수 있다). */
  static @Nullable String header(MessageHeaders headers, String name) {
    for (var e : headers.entrySet()) {
      if (e.getKey().equalsIgnoreCase(name) && e.getValue() != null) {
        Object v = e.getValue();
        if (v instanceof List<?> l) {
          return l.isEmpty() ? null : String.valueOf(l.get(0));
        }
        return String.valueOf(v);
      }
    }
    return null;
  }
}
