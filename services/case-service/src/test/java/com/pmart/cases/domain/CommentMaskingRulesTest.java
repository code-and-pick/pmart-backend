package com.pmart.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommentMaskingRulesTest {

  @Test
  void masksPhoneShapes() {
    assertThat(CommentMaskingRules.mask("연락은 010-1234-5678 로")).isEqualTo("연락은 010-****-**** 로");
    assertThat(CommentMaskingRules.mask("01012345678")).isEqualTo("010-****-****");
    assertThat(CommentMaskingRules.mask("02 123 4567 집")).isEqualTo("02-****-**** 집");
    assertThat(CommentMaskingRules.mask("031.1234.5678")).isEqualTo("031-****-****");
  }

  @Test
  void masksEmail() {
    assertThat(CommentMaskingRules.mask("메일 hong.gil@example.co.kr 주세요"))
        .isEqualTo("메일 h***@*** 주세요");
  }

  @Test
  void leavesOrdinaryTextAndNumbers() {
    assertThat(CommentMaskingRules.mask("빨리 답해 줬어요 5점")).isEqualTo("빨리 답해 줬어요 5점");
    assertThat(CommentMaskingRules.mask("주문 3개 중 2개만 왔어요")).isEqualTo("주문 3개 중 2개만 왔어요");
    assertThat(CommentMaskingRules.mask(null)).isNull();
  }
}
