package com.pmart.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NoticesTest {

  @Test
  void earningAdjustedShowsAmountAndDateOnlyWhenPaid() {
    var paid = Notices.earningAdjusted("DLV-1", true, 3900L, "2026-11-20");
    assertThat(paid.body()).contains("3,900원").contains("11월 20일");
    var none = Notices.earningAdjusted("DLV-1", false, null, null);
    assertThat(none.body()).contains("지급되지 않아요").doesNotContain("원이");
  }

  @Test
  void riderRestrictionLiftedOnlyWhenSomethingWasRestricted() {
    assertThat(Notices.riderRestriction("NORMAL", "DISPATCH_RESTRICTED", null).title())
        .isEqualTo("계정 제한이 풀렸어요");
    assertThat(Notices.riderRestriction("NORMAL", null, null)).isNotNull(); // previous 없는 옛 사건은 그대로
    assertThat(Notices.riderRestriction("NORMAL", "NORMAL", null)).isNull();
    assertThat(Notices.riderRestriction("DISPATCH_RESTRICTED", "NORMAL", null).body())
        .doesNotContain("사유");
  }

  @Test
  void orderConfirmedShowsAmountAndArrivalWindow() {
    var n = Notices.orderConfirmed("ORD-1", 32100L, "2026-11-17", "13:00", "14:00");
    assertThat(n.body()).contains("32,100원").contains("11월 17일 13:00~14:00");
    assertThat(n.destinationType()).isEqualTo("ORDER");
    assertThat(n.destinationRef()).isEqualTo("ORD-1");
    // 도착 창이 없으면 그 문장을 빼고, 지어내지 않는다
    assertThat(Notices.orderConfirmed("ORD-1", null, null, null, null).body())
        .doesNotContain("도착 예정")
        .doesNotContain("원");
  }

  @Test
  void cancellationDoesNotClaimRefundCompleted() {
    var n = Notices.orderCancelled("ORD-1", "OUT_OF_STOCK");
    assertThat(n.title()).isEqualTo("품절로 주문이 취소되었어요");
    assertThat(n.body()).doesNotContain("환불되었").contains("환불 결과가 나오면");
    assertThat(Notices.orderCancelled("ORD-1", "CUSTOMER_REQUEST").title()).isEqualTo("주문이 취소되었어요");
  }

  @Test
  void refundOnlyForKnownResults() {
    var ok = Notices.refund("SUCCESS", 5000L, "ORD-1", "CLM-1");
    assertThat(ok).isNotNull();
    assertThat(ok.body()).startsWith("5,000원을");
    assertThat(ok.destinationType()).isEqualTo("REFUND");
    assertThat(ok.destinationRef()).isEqualTo("CLM-1");
    assertThat(Notices.refund("FAILED", 5000L, "ORD-1", null).destinationType()).isEqualTo("ORDER");
    assertThat(Notices.refund("UNKNOWN", 5000L, "ORD-1", "CLM-1")).isNull();
  }

  @Test
  void shortageUsesProductNameOrGenericLabel() {
    assertThat(Notices.shortage("ORD-1", "사과 1kg").body()).startsWith("사과 1kg의");
    assertThat(Notices.shortage("ORD-1", null).body()).startsWith("주문한 상품의");
  }
}
