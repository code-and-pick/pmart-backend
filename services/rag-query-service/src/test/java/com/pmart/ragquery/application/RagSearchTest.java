package com.pmart.ragquery.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.ragquery.domain.SearchHit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagSearchTest {

  static SearchHit h(String id, String type, double s) {
    return new SearchHit(id, id + ".md", "", "", type, s, s);
  }

  @Test
  void rrfAddsBothListsAndKeepsOneRowPerChunk() {
    var a = List.of(h("x", null, 0), h("y", null, 0), h("z", null, 0));
    var b = List.of(h("y", null, 0), h("w", null, 0));
    var out = RagSearch.rrf(a, b, 60);
    // y: 1/62 + 1/61 이 가장 크다, 그다음 x(1/61), w(1/62), z(1/63)
    assertThat(out).extracting(SearchHit::chunkId).containsExactly("y", "x", "w", "z");
  }

  @Test
  void policyBoostReordersOnlyOperatorAndKeepsRaw() {
    var props =
        new RagQueryProperties(null, null, 1.5, 0, 0.7, 0.1, 3, 0.58, 60, Duration.ofSeconds(1));
    var s = new RagSearch(null, null, props);
    var raw = List.of(h("app", "APP_GUIDE", 20), h("pol", "POLICY", 15));
    assertThat(s.boost(raw, "OPERATOR"))
        .extracting(SearchHit::chunkId)
        .containsExactly("pol", "app");
    assertThat(s.boost(raw, "OPERATOR").get(0).raw()).isEqualTo(15.0);
    assertThat(s.boost(raw, "CUSTOMER"))
        .extracting(SearchHit::chunkId)
        .containsExactly("app", "pol");
  }
}
