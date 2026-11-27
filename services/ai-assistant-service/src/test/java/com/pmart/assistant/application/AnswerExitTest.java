package com.pmart.assistant.application;

import static com.pmart.assistant.domain.Chunks.chunk;
import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.application.AnswerGenerator.Exit;
import com.pmart.assistant.application.GenerativeAnswerGeneratorTest.FakeChat;
import com.pmart.assistant.domain.Authority;
import com.pmart.assistant.domain.CitationVerifier;
import com.pmart.assistant.domain.RetrievedChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 종료 지점 기록(2026-11-27 ES-First 계획 P1 ①): 문서 경로가 어디서 끝났는지·시도마다 결과·1위 유사도가 답과 지표에 남는다. */
class AnswerExitTest {

  private final FakeChat chat = new FakeChat();
  private final List<RetrievedChunk> ranked =
      List.of(chunk("a", Authority.CONFIRMED, 0.9, "봉투값은 주문당 100원 1회다. 전체 취소 시 전액 환불한다."));

  private GenerativeAnswerGenerator gen(double floor) {
    return new GenerativeAnswerGenerator(
        chat,
        "fake-chat",
        new CitationVerifier(0.5),
        new ExtractiveAnswerGenerator(4000, 5),
        JsonMapper.builder().build(),
        () -> floor);
  }

  @Test
  void exitsTellWhereTheDocumentPathEnded() {
    assertThat(gen(0).generate("봉투값?", List.of(), List.of()).exit())
        .isEqualTo(Exit.BELOW_THRESHOLD);

    chat.replies.add("{\"answer\":\"봉투값은 주문당 100원 1회다.\",\"usedIds\":[\"D1\"],\"grounded\":true}");
    var first = gen(0).generate("봉투값?", ranked, List.of());
    assertThat(first.exit()).isEqualTo(Exit.GENERATED_FIRST);
    assertThat(first.attempts()).containsExactly("PASS");
    assertThat(first.topSimilarity()).isEqualTo(0.9);

    chat.replies.add("not json");
    chat.replies.add("{\"answer\":\"봉투값은 주문당 100원 1회다.\",\"usedIds\":[\"D1\"],\"grounded\":true}");
    var retry = gen(0).generate("봉투값?", ranked, List.of());
    assertThat(retry.exit()).isEqualTo(Exit.GENERATED_RETRY);
    assertThat(retry.attempts()).containsExactly("JSON_INVALID", "PASS");

    chat.replies.add("{\"answer\":\"\",\"usedIds\":[],\"grounded\":false}");
    var ungrounded = gen(0).generate("봉투값?", ranked, List.of());
    assertThat(ungrounded.exit()).isEqualTo(Exit.MODEL_UNGROUNDED);
    assertThat(ungrounded.attempts()).containsExactly("UNGROUNDED");

    chat.replies.add("{\"answer\":\"봉투값은 주문당 500원이다.\",\"usedIds\":[\"D1\"],\"grounded\":true}");
    chat.replies.add("{\"answer\":\"봉투값은 무료다.\",\"usedIds\":[\"D3\"],\"grounded\":true}");
    var extract = gen(0.5).generate("봉투값?", ranked, List.of());
    assertThat(extract.exit()).isEqualTo(Exit.EXTRACTIVE_FALLBACK);
    assertThat(extract.attempts()).hasSize(2).allMatch(a -> !a.equals("PASS"));
    assertThat(extract.attempts().get(0)).contains("NUMBER_MISMATCH");

    chat.replies.add("{\"answer\":\"봉투값은 주문당 500원이다.\",\"usedIds\":[\"D1\"],\"grounded\":true}");
    chat.replies.add("{\"answer\":\"봉투값은 무료다.\",\"usedIds\":[\"D3\"],\"grounded\":true}");
    assertThat(gen(0.95).generate("봉투값?", ranked, List.of()).exit())
        .isEqualTo(Exit.BELOW_FALLBACK_FLOOR);
  }

  @Test
  void metricsCountExitsAttemptsAndRules() {
    var reg = new SimpleMeterRegistry();
    var m = new AnswerMetrics(reg);
    chat.replies.add("{\"answer\":\"봉투값은 주문당 500원이다.\",\"usedIds\":[\"D1\"],\"grounded\":true}");
    chat.replies.add("{\"answer\":\"봉투값은 주문당 100원 1회다.\",\"usedIds\":[\"D1\"],\"grounded\":true}");
    m.generated(gen(0).generate("봉투값?", ranked, List.of()));
    m.exit("PRODUCT_INFO");

    assertThat(reg.get("pmart.assistant.answers").tag("exit", "GENERATED_RETRY").counter().count())
        .isEqualTo(1);
    assertThat(reg.get("pmart.assistant.answers").tag("exit", "PRODUCT_INFO").counter().count())
        .isEqualTo(1);
    assertThat(
            reg.get("pmart.assistant.generation.attempts")
                .tag("result", "CHECK_FAILED")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            reg.get("pmart.assistant.citation.violations")
                .tag("rule", "NUMBER_MISMATCH")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            reg.get("pmart.assistant.top.similarity")
                .tag("exit", "GENERATED_RETRY")
                .summary()
                .count())
        .isEqualTo(1);
    // 지표 없는 판(시험)은 아무것도 하지 않는다
    AnswerMetrics.none().exit("PRODUCT_INFO");
  }
}
