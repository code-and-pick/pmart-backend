package com.pmart.cdc.outbound.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PickPlanTest {

  @Test
  void taskDoneWhenAllLinesSettledPartialWhenShort() {
    assertThat(PickPlan.afterReport("IN_PROGRESS", List.of(new int[] {10, 10, 0})))
        .isEqualTo("DONE");
    assertThat(PickPlan.afterReport("IN_PROGRESS", List.of(new int[] {10, 8, 2})))
        .isEqualTo("PARTIAL");
    assertThat(
            PickPlan.afterReport(
                "IN_PROGRESS", List.of(new int[] {10, 10, 0}, new int[] {5, 2, 0})))
        .isEqualTo("IN_PROGRESS");
  }
}
