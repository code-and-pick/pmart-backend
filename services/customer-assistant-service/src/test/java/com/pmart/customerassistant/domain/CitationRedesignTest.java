package com.pmart.customerassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.customerassistant.domain.CitationVerifier.Violation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 인용 검사 다시 설계(2026-11-21 사용자 "2·3·4 진행"): 숫자는 구절과 짝지은 근거 단위에서, 부정은 분명히 짝지어질 때만(표는 칸 단위), 문장 뒷받침은
 * bigram 또는 의미 유사도.
 */
class CitationRedesignTest {

  static final String TIP =
      """
      ## 제5조 (배달팁)
      1. 배달팁은 배송 방식에 따라 다음과 같습니다.

      | 배송 방식 | 배달팁 |
      |---|---|
      | 한집배달 | 4,000원 |
      | 알뜰배달 | 3,000원 |
      | 예약배달 | 3,000원 |

      2. 상품액이 30,000원 이상이거나 첫 구매이면 배달팁은 0원입니다.
      """;

  static final String CANCEL =
      """
      | 주문 단계 | 고객 요청 취소 | 처리 |
      |---|---|---|
      | 결제 완료 ~ 상품 준비 전 | 가능 | 고객이 앱 주문 내역에서 직접 취소하거나, 고객센터가 대신 접수 |
      | 상품 준비 시작 뒤 ~ 라이더 인계 전 | 불가 | 고객 앱·고객센터 모두 받지 않음. 받은 뒤 반품으로 안내 |

      1. "상품 준비 시작"은 센터가 그 주문의 첫 피킹을 시작한 때입니다.
      """;

  static final String REVIEW =
      """
      1. 리뷰는 주문 상품마다 1건만 쓸 수 있습니다.
      2. 작성 기한은 그 상품을 처음 받은 때부터 14일이 되는 시각까지입니다.
      3. 리뷰는 처음 등록한 때부터 3일이 지나기 전까지만 고칠 수 있습니다.
      """;

  static final String FEE =
      """
      1. 반품 회수비는 9,000원이며 고객이 부담합니다.
      2. 회수비 결제는 상품 환불과 별개의 결제입니다. 회수비를 냈다고 반품이 승인되거나 환불이 끝난 것은 아닙니다.
      """;

  static final String PII = "1. 사유는 2자 이상 200자 이하로 적어야 합니다.";

  final CitationVerifier verifier = new CitationVerifier(0.5);

  List<Violation> check(String answer, String evidence) {
    return verifier.verify(answer, List.of("D1"), Map.of("D1", evidence)).violations();
  }

  @Test
  void numbersMustComeFromTheAlignedRowNotAnywhereInEvidence() {
    assertThat(check("한집배달 배달팁은 4,000원이고, 알뜰배달과 예약배달은 3,000원입니다.", TIP)).isEmpty();
    assertThat(check("한집배달 배달팁은 3,000원입니다.", TIP)).contains(Violation.NUMBER_MISMATCH);
    assertThat(check("리뷰는 상품을 처음 받은 때부터 14일 안에 쓸 수 있습니다.", REVIEW)).isEmpty();
    assertThat(check("리뷰는 상품을 처음 받은 때부터 3일 안에 쓸 수 있습니다.", REVIEW))
        .contains(Violation.NUMBER_MISMATCH);
  }

  @Test
  void partialNumbersAndListMarkersDoNotCount() {
    assertThat(check("사유는 20자 이상으로 적어야 합니다.", PII)).contains(Violation.NUMBER_MISMATCH);
    assertThat(check("리뷰는 주문 상품마다 2건까지 쓸 수 있습니다.", REVIEW))
        .contains(Violation.NUMBER_MISMATCH); // "2." 목록 번호에 속지 않는다
  }

  @Test
  void koreanNumberWordsMatchDigits() {
    assertThat(EvidenceAlignment.numbers("4천 원, 3만 원, 1만 5천 원, 30,000원"))
        .containsExactly(4000.0, 30000.0, 15000.0, 30000.0);
    assertThat(EvidenceAlignment.numbers("1. 첫 항목")).containsExactly(1.0);
    assertThat(EvidenceAlignment.units("1. 첫 항목은 2개입니다.")).containsExactly("첫 항목은 2개입니다.");
  }

  @Test
  void negationUsesTheVerdictCellOfTheAlignedRow() {
    assertThat(check("상품 준비가 시작된 뒤에도 고객센터에서 주문을 취소할 수 있습니다.", CANCEL))
        .contains(Violation.NEGATION_MISMATCH);
    assertThat(check("고객 요청 취소는 상품 준비 전까지만 가능합니다.", CANCEL))
        .doesNotContain(Violation.NEGATION_MISMATCH);
    // 같은 행이지만 "반품 안내" 칸과 짝지어지면 그 칸의 긍정으로 본다
    assertThat(check("상품을 받은 뒤 반품하도록 안내합니다.", CANCEL)).doesNotContain(Violation.NEGATION_MISMATCH);
  }

  /** 2026-11-27 C2 측정 A17: 연결어미(-며) 뒤의 부정이 앞 구절과 같은 조각에 들어가 옳은 답을 거절하던 것. */
  static final String MIN_ORDER =
      """
      1. 최소 주문금액은 15,000원입니다.
      2. 최소 주문금액은 상품액(상품 단가 × 수량의 합계에서 기간 할인을 뺀 금액)으로만 판단하며 배달팁과 봉투값은 넣지 않습니다.
      """;

  @Test
  void negationAfterConnectiveEndingIsComparedOnlyWithItsOwnPart() {
    // 옳은 답(측정 A17 그대로) — 앞부분만 말했으므로 뒤의 "넣지 않습니다"와 비교하지 않는다
    assertThat(check("최소 주문금액은 15,000원이며, 상품액(상품 단가 × 수량의 합계에서 기간 할인을 뺀 금액)으로만 판단합니다.", MIN_ORDER))
        .doesNotContain(Violation.NEGATION_MISMATCH);
    // 진짜 뒤집기 — 한 구절에 두 부분을 담아 뒤를 뒤집어도 잡는다(답 쪽도 같은 연결어미에서 나눈다)
    assertThat(check("최소 주문금액은 상품액으로만 판단하며 배달팁과 봉투값은 넣습니다.", MIN_ORDER))
        .contains(Violation.NEGATION_MISMATCH);
    assertThat(EvidenceAlignment.clauses("상품액으로만 판단하며 배달팁과 봉투값은 넣습니다."))
        .containsExactly("상품액으로만 판단하며", "배달팁과 봉투값은 넣습니다.");
  }

  @Test
  void negationSkipsWhenAlignmentIsAmbiguousAndCatchesClearFlips() {
    assertThat(check("센터가 첫 피킹을 시작하면 고객은 주문을 취소할 수 없습니다.", CANCEL))
        .doesNotContain(Violation.NEGATION_MISMATCH);
    assertThat(check("회수비 9,000원을 결제하면 반품이 승인되고 환불이 끝난 것입니다.", FEE))
        .contains(Violation.NEGATION_MISMATCH);
  }

  @Test
  void semanticSimilarityRescuesParaphrasesOnly() {
    String paraphrase = "한집배달은 4천 원, 알뜰·예약배달은 3천 원이며 3만 원 이상이면 배달팁을 받지 않습니다.";
    assertThat(check(paraphrase, TIP)).contains(Violation.UNSUPPORTED_SENTENCE);
    CitationVerifier semantic =
        new CitationVerifier(
            0.5,
            0.65,
            (sentences, units) -> {
              double[] out = new double[sentences.size()];
              Arrays.fill(out, 0.72);
              return out;
            });
    assertThat(semantic.verify(paraphrase, List.of("D1"), Map.of("D1", TIP)).violations())
        .doesNotContain(Violation.UNSUPPORTED_SENTENCE);
    CitationVerifier low =
        new CitationVerifier(
            0.5,
            0.65,
            (sentences, units) -> {
              double[] out = new double[sentences.size()];
              Arrays.fill(out, 0.6);
              return out;
            });
    assertThat(low.verify("주말에는 배달팁을 두 배로 받습니다.", List.of("D1"), Map.of("D1", TIP)).violations())
        .contains(Violation.UNSUPPORTED_SENTENCE);
  }
}
