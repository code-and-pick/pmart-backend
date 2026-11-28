package com.pmart.customerassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductQuestionTest {

  @ParameterizedTest
  @CsvSource({
    "두부 있어요?, 두부",
    "혹시 두부를 파나요?, 두부",
    "콜라 팔아요, 콜라",
    "생수 가격 알려줘, 생수",
    "우유 값이 얼마야?, 우유",
    "서울우유 1L 얼마예요?, 서울우유 1L",
    "계란 살 수 있나요?, 계란",
    "라면 품절이에요?, 라면",
    "피마트에서 바나나 판매하나요?, 바나나",
    "햇반 지금 팔아요?, 햇반",
    "두부 혹시 있어요?, 두부",
    "술빵 얼마예요?, 술빵"
  })
  void productQuestionsYieldKeyword(String question, String keyword) {
    assertThat(ProductQuestion.keyword(question)).contains(keyword);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "봉투값은 얼마예요?",
        "배달팁은 얼마인가요?",
        "반품 회수비 얼마예요?",
        "주문 취소할 수 있나요?",
        "리뷰는 언제까지 쓸 수 있나요?",
        "두부 재고 몇 개 남았어?",
        "상품 있어요?",
        "결품이 나면 어떻게 처리하나요?",
        "개인정보 원문 열람 사유는 뭐야?",
        // 주류는 상품 검색 답이 아니라 문서(구매 불가)로(사용자 2026-11-28)
        "주류도 살 수 있나요?",
        "소주 얼마예요?",
        "참이슬 소주 있어요?",
        "맥주 팔아요?",
        "막걸리 가격 알려줘",
        "술 있어요?"
      })
  void policyAndNonProductQuestionsAreNotProductQuestions(String question) {
    assertThat(ProductQuestion.keyword(question)).isEmpty();
  }

  @Test
  void customerTextListsItemsWithoutCenterOrInventedPrice() {
    String text =
        ProductAnswerText.customer(
            "두부",
            List.of(
                new ProductAnswerText.Item("국산콩 두부 300g", 2500L, "ON_SALE", null),
                new ProductAnswerText.Item("연두부", null, "TEMPORARILY_SOLD_OUT", null)));
    assertThat(text)
        .contains("'두부'")
        .contains("국산콩 두부 300g · 2,500원 · 판매 중")
        .contains("연두부 · 가격 준비 중 · 일시품절")
        .contains("주소에 따라 받아 볼 수 없는 상품")
        .doesNotContain("센터")
        .doesNotContain("· 0원");
  }
}
