package com.pmart.delivery.execution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionRulesTest {

  @Test
  void pickupNeedsKnownListAndEveryPackage() {
    assertThat(ExecutionRules.allHandedOver(List.of(), List.of("P1"))).isFalse(); // 목록 모름
    assertThat(ExecutionRules.allHandedOver(List.of("P1", "P2"), List.of("P1"))).isFalse();
    assertThat(ExecutionRules.allHandedOver(List.of("P1", "P2"), List.of("P2", "P1", "P3")))
        .isTrue();
  }

  @Test
  void deliveredLinesSumPerOrderLine() {
    assertThat(ExecutionRules.deliveredLines(List.of(Map.of("L1", 2), Map.of("L1", 1, "L2", 3))))
        .containsExactlyInAnyOrder(
            Map.of("orderLineId", "L1", "qty", 3), Map.of("orderLineId", "L2", "qty", 3));
  }

  @Test
  void photoTypeFromLeadingBytes() {
    assertThat(ExecutionRules.photoType(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}))
        .contains("image/jpeg");
    assertThat(
            ExecutionRules.photoType(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
        .contains("image/png");
    assertThat(
            ExecutionRules.photoType(
                new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}))
        .contains("image/webp");
    assertThat(ExecutionRules.photoType("GIF89a".getBytes())).isEmpty();
    assertThat(ExecutionRules.photoType(new byte[0])).isEmpty();
  }
}
