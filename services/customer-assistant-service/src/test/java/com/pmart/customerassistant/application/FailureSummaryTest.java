package com.pmart.customerassistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

class FailureSummaryTest {

  @Test
  void showsCauseChainWithFixedTextAndHttpStatusOnly() {
    var http =
        HttpServerErrorException.create(
            HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, "모델 응답 본문".getBytes(), null);
    var failure =
        new AnswerGenerator.DependencyFailure(
            "채팅 모델 호출 실패", new IllegalStateException("질문 일부", http));

    assertThat(FailureSummary.of(failure))
        .isEqualTo(
            "DependencyFailure(채팅 모델 호출 실패) > IllegalStateException > InternalServerError(HTTP 500)")
        .doesNotContain("질문 일부")
        .doesNotContain("모델 응답 본문");
  }

  @Test
  void nullAndShallowFailures() {
    assertThat(FailureSummary.of(null)).isEqualTo("unknown");
    assertThat(FailureSummary.of(new SocketTimeoutException("x")))
        .isEqualTo("SocketTimeoutException");
  }
}
