package com.pmart.integration.domain;

import com.pmart.integration.domain.FileChange.ChangeStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * GitHub push 웹훅 본문 → 바뀐 파일 목록. 커밋 순서대로 각 파일의 마지막 상태를 남긴다(추가 뒤 삭제 = 삭제, 삭제 뒤 추가 = 추가). 지정 브랜치가 아니거나
 * 브랜치 삭제 push({@code deleted: true})면 빈 목록.
 */
public final class PushEvents {

  private PushEvents() {}

  public static List<FileChange> changes(JsonNode push, String branch, String deliveryId) {
    if (!("refs/heads/" + branch).equals(push.path("ref").asString(""))
        || push.path("deleted").asBoolean(false)) {
      return List.of();
    }
    String repo = push.path("repository").path("full_name").asString("");
    String after = push.path("after").asString("");
    Map<String, ChangeStatus> last = new LinkedHashMap<>();
    for (JsonNode c : push.path("commits")) {
      for (JsonNode p : c.path("added")) {
        last.put(p.asString(), ChangeStatus.ADDED);
      }
      for (JsonNode p : c.path("modified")) {
        last.merge(
            p.asString(), ChangeStatus.MODIFIED, (old, n) -> old == ChangeStatus.ADDED ? old : n);
      }
      for (JsonNode p : c.path("removed")) {
        last.put(p.asString(), ChangeStatus.REMOVED);
      }
    }
    List<FileChange> out = new ArrayList<>();
    last.forEach((path, st) -> out.add(new FileChange(repo, after, path, st, deliveryId)));
    return out;
  }
}
