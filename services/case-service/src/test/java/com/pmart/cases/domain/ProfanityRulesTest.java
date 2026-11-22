package com.pmart.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProfanityRulesTest {

  final List<String> words = List.of("나쁜말", "BadWord");

  @Test
  void catchesSpacedPunctuatedAndCaseVariants() {
    assertThat(ProfanityRules.contains(words, null, "배송이 왜 이래 나쁜말")).isTrue();
    assertThat(ProfanityRules.contains(words, "제목", "나 쁜.말 진짜")).isTrue();
    assertThat(ProfanityRules.contains(words, "BADWORD", null)).isTrue();
  }

  @Test
  void cleanTextAndEmptyListCatchNothing() {
    assertThat(ProfanityRules.contains(words, "상자가 젖었어요", "환불 부탁드려요")).isFalse();
    assertThat(ProfanityRules.contains(List.of(), "나쁜말")).isFalse();
    assertThat(ProfanityRules.contains(List.of(" ", "."), "아무 글")).as("빈 단어는 무시").isFalse();
  }

  @Test
  void wordsDoNotJoinAcrossTitleAndBody() {
    assertThat(ProfanityRules.contains(words, "나쁜", "말")).as("제목·본문 경계는 붙이지 않음").isFalse();
  }
}
