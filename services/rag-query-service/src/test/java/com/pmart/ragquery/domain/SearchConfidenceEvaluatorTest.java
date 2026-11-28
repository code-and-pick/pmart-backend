package com.pmart.ragquery.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SearchConfidenceEvaluatorTest {

  final SearchConfidenceEvaluator ev = new SearchConfidenceEvaluator(0.7, 0.1);

  static SearchHit hit(String id, String type, double raw) {
    return new SearchHit(id, "d.md", "정책 > 제1조", "제1조", type, raw, raw);
  }

  /** 2차 검증 기록 149문항: Python(analyze2.py) 판정과 같아야 한다. */
  @Test
  void decisionsMatchPythonOnRecordedFeatures() throws Exception {
    JsonNode root;
    try (InputStream in = getClass().getResourceAsStream("/parity/python-decisions.json")) {
      root = JsonMapper.builder().build().readTree(in);
    }
    int n = 0;
    for (JsonNode c : root.path("cases")) {
      var f =
          new SearchConfidenceEvaluator.Features(
              0, 0, c.path("relGap").asDouble(), c.path("coverage").asDouble(), 0, 0, 0);
      assertThat(ev.decide(f).acceptAtEs())
          .as(c.path("id").asString())
          .isEqualTo(c.path("pythonAccept").asBoolean());
      n++;
    }
    assertThat(n).isEqualTo(149);
  }

  @Test
  void featuresFromTokens() {
    var hits = List.of(hit("a", "POLICY", 20.0), hit("b", "POLICY", 15.0));
    var f =
        ev.features(
            hits,
            List.of("장바구니", "보존", "기간", "이", "3"),
            List.of("장바구니", "보존", "기간", "30", "일", "만료"),
            List.of("장바구니", "정책", "보존", "기간"),
            List.of("보존", "기간"));
    // 질문 낱말: 장바구니·보존·기간·3(숫자 한 글자는 남김, '이' 는 뺌) → 본문에 3/4
    assertThat(f.coverage()).isEqualTo(0.75);
    assertThat(f.titleMatch()).isEqualTo(0.75);
    assertThat(f.relGap()).isEqualTo(0.25);
    assertThat(f.gap()).isEqualTo(5.0);
    // 이웃 쌍: (장바구니,보존)(보존,기간)(기간,3) 중 본문에 이웃한 것 2/3
    assertThat(f.phraseMatch()).isEqualTo(0.6667);
    assertThat(f.faqQuestionMatch()).isZero(); // 1위가 FAQ 가 아님
    assertThat(ev.decide(f).acceptAtEs()).isTrue();
  }

  @Test
  void faqQuestionMatchOnlyForFaqAndLowGapRejects() {
    var hits = List.of(hit("a", "FAQ", 10.0), hit("b", "APP_GUIDE", 9.5));
    var f =
        ev.features(
            hits,
            List.of("리뷰", "수정", "언제"),
            List.of("리뷰", "수정", "언제", "3일"),
            List.of("리뷰"),
            List.of("리뷰", "수정", "언제", "까지"));
    assertThat(f.faqQuestionMatch()).isEqualTo(0.75);
    assertThat(f.coverage()).isEqualTo(1.0);
    assertThat(f.relGap()).isEqualTo(0.05);
    var d = ev.decide(f);
    assertThat(d.acceptAtEs()).isFalse();
    assertThat(d.reason()).startsWith("relGap");
  }

  @Test
  void noHits() {
    var f = ev.features(List.of(), List.of("질문"), List.of(), List.of(), List.of());
    assertThat(ev.decide(f).acceptAtEs()).isFalse();
  }
}
