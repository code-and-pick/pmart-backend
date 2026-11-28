package com.pmart.customerassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 빠진 사실 검사(2026-11-28 — 운영자 E02·고객 C13·C15·C17 평가에서 본 답을 그대로). */
class AnswerCompletenessTest {

  /** backoffice/05 제15조 3항 표(줄임). */
  static final String IDENTITY_TABLE =
      """
      | 문의 종류 | 연락한 번호로 회원이 조회될 때 | 조회되지 않을 때 |
      |---|---|---|
      | 주문·취소·배송·반품 | 1. 주문한 상품(또는 주문 일시) 2. 주문자 본인인지(아니면 주문자와의 관계) | 1. 주문할 때 입력한 연락처 2. 주문한 상품(또는 주문 일시) 3. 주문자 본인인지(아니면 주문자와의 관계) |
      """;

  @Test
  void answerUsingOneOfSeveralNumberedItemsInACellIsMissingItems() {
    // E02: 세 항목 중 첫 항목만
    assertThat(AnswerCompleteness.missingInlineItems("주문할 때 입력한 연락처", List.of(IDENTITY_TABLE)))
        .isTrue();
    // 모두 쓰면 통과
    assertThat(
            AnswerCompleteness.missingInlineItems(
                "주문할 때 입력한 연락처, 주문한 상품(또는 주문 일시), 주문자 본인인지를 묻습니다.", List.of(IDENTITY_TABLE)))
        .isFalse();
  }

  @Test
  void articleListItemsAreNotGroups() {
    // 조문의 1항·2항(줄마다 번호)은 묶음이 아니다 — 질문에 맞는 항만 쓴 답을 버리지 않는다
    String article =
        """
        ## 제4조 (최소 주문금액)
        1. 최소 주문금액은 15,000원입니다.
        2. 판정은 기간 할인 뒤, 쿠폰 할인 전 상품액으로 합니다.
        """;
    assertThat(AnswerCompleteness.missingInlineItems("최소 주문금액은 15,000원입니다.", List.of(article)))
        .isFalse();
    assertThat(AnswerCompleteness.inlineGroups(article)).isEmpty();
  }

  @Test
  void numbersThatAreNotAnItemListAreIgnored() {
    assertThat(AnswerCompleteness.items("가격은 1. 5 단위로 바뀝니다")).isEmpty();
    assertThat(AnswerCompleteness.items("1. 사진 2. 주문번호")).containsExactly("사진", "주문번호");
  }

  @Test
  void referralOnlyAnswers() {
    // C17·C15·A13 꼴
    assertThat(
            AnswerCompleteness.referralOnly(
                "PMart 상품 누락·파손 처리 안내 > 제1조 (목적)에 따라, 주문한 상품이 파손되었을 경우 처리 기준이 적용됩니다."))
        .isTrue();
    assertThat(
            AnswerCompleteness.referralOnly(
                "배송 완료로 표시되었으나 상품을 받지 못했다면 'PMart 미수령·오배송 처리 안내'에 따라 고객센터에 알려 주세요."))
        .isTrue();
    // 내용이 있는 문장이 하나라도 있으면 통과
    assertThat(
            AnswerCompleteness.referralOnly(
                "피킹 중에 결품이 나면 그 품목만 부분 취소하고 환불합니다. 자세한 절차는 MFC 피킹·포장 정책을 따릅니다."))
        .isFalse();
    assertThat(AnswerCompleteness.referralOnly("봉투값은 주문당 100원이며 전체 취소 때 환불합니다.")).isFalse();
  }

  @Test
  void purposeSections() {
    assertThat(AnswerCompleteness.purposeSection("PMart 상품 누락·파손 처리 안내 > 제1조 (목적)")).isTrue();
    assertThat(AnswerCompleteness.purposeSection("PMart 주문 취소 정책 > 제2조 (취소 가능 시점)")).isFalse();
    assertThat(AnswerCompleteness.purposeSection(null)).isFalse();
  }

  @Test
  void rankingPushesPurposeArticlesDown() {
    RankingPolicy policy = new RankingPolicy(0.1, RankingPolicy.defaultWeights(), 0);
    RetrievedChunk purpose = chunk("p", "안내 > 제1조 (목적)", 0.62);
    RetrievedChunk rule = chunk("r", "안내 > 제3조 (파손)", 0.55);
    assertThat(policy.rank(List.of(purpose, rule), 2))
        .extracting(RetrievedChunk::chunkId)
        .containsExactly("r", "p");
    // 다른 근거가 없으면 목적 조항도 그대로 쓰인다(임계값 판정은 유사도)
    assertThat(policy.rank(List.of(purpose), 2)).hasSize(1);
  }

  static RetrievedChunk chunk(String id, String section, double sim) {
    return new RetrievedChunk(
        id,
        "d",
        "d",
        section,
        Authority.CONFIRMED,
        Lifecycle.ACTIVE,
        null,
        List.of(),
        0,
        null,
        null,
        null,
        null,
        "customer",
        "본문",
        sim,
        0);
  }
}
