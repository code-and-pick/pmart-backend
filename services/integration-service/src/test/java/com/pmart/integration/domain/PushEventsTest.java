package com.pmart.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.integration.domain.FileChange.ChangeStatus;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PushEventsTest {

  static JsonNode json(String s) {
    return JsonMapper.builder().build().readTree(s);
  }

  static final String PUSH =
      """
      {"ref":"refs/heads/main","before":"aaa","after":"ccc","deleted":false,
       "repository":{"full_name":"code-and-pick/pmart-all-docs"},
       "commits":[
         {"id":"bbb","added":["operator/mfc/09-new.md","tmp.md"],"modified":["operator/mfc/01-a.md"],"removed":[]},
         {"id":"ccc","added":[],"modified":["operator/mfc/09-new.md"],"removed":["tmp.md","customer/faq/02-cancel.md"]}
       ]}
      """;

  @Test
  void lastStatusPerFileAcrossCommits() {
    var changes = PushEvents.changes(json(PUSH), "main", "d-1");
    assertThat(changes)
        .extracting(FileChange::path, FileChange::status)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("operator/mfc/09-new.md", ChangeStatus.ADDED),
            org.assertj.core.groups.Tuple.tuple("tmp.md", ChangeStatus.REMOVED),
            org.assertj.core.groups.Tuple.tuple("operator/mfc/01-a.md", ChangeStatus.MODIFIED),
            org.assertj.core.groups.Tuple.tuple("customer/faq/02-cancel.md", ChangeStatus.REMOVED));
    assertThat(changes)
        .allSatisfy(
            c -> {
              assertThat(c.commit()).isEqualTo("ccc");
              assertThat(c.repository()).isEqualTo("code-and-pick/pmart-all-docs");
              assertThat(c.deliveryId()).isEqualTo("d-1");
            });
  }

  @Test
  void otherBranchOrBranchDeletionHasNoChanges() {
    assertThat(
            PushEvents.changes(json(PUSH.replace("refs/heads/main", "refs/heads/x")), "main", "d"))
        .isEmpty();
    assertThat(
            PushEvents.changes(
                json(PUSH.replace("\"deleted\":false", "\"deleted\":true")), "main", "d"))
        .isEmpty();
  }

  @Test
  void signature() {
    byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    String sig = WebhookSignature.sign("s3cret", body);
    assertThat(sig).startsWith("sha256=");
    assertThat(WebhookSignature.valid("s3cret", body, sig)).isTrue();
    assertThat(WebhookSignature.valid("other", body, sig)).isFalse();
    assertThat(WebhookSignature.valid("s3cret", "{\"a\":2}".getBytes(StandardCharsets.UTF_8), sig))
        .isFalse();
    assertThat(WebhookSignature.valid("", body, sig)).isFalse();
    assertThat(WebhookSignature.valid("s3cret", body, null)).isFalse();
  }

  @Test
  void extraction() {
    String c = ContentExtraction.normalize((char) 0xFEFF + "# 오배송 처리\r\n\r\n## 제1조\r\n본문\r\n\r\n");
    assertThat(c).isEqualTo("# 오배송 처리\n\n## 제1조\n본문\n");
    assertThat(ContentExtraction.title(c)).isEqualTo("오배송 처리");
    assertThat(ContentExtraction.title("본문만\n")).isNull();
    assertThat(ContentExtraction.sha256(c)).hasSize(64);
  }
}
