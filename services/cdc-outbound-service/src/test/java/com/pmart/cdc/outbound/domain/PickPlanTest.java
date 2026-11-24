package com.pmart.cdc.outbound.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.cdc.outbound.domain.PickPlan.Held;
import java.util.List;
import org.junit.jupiter.api.Test;

class PickPlanTest {

  @Test
  void groupsByZoneAndFloorAndMergesSameLine() {
    var groups =
        PickPlan.group(
            List.of(
                new Held("E-00000001", "L1", "CDC01-C-5-61-01-1-1", 10),
                new Held("E-00000002", "L2", "CDC01-C-5-61-02-1-1", 5),
                new Held("E-00000001", "L1", "CDC01-C-5-61-01-1-1", 3),
                new Held("E-00000003", "L3", "CDC01-B-6-61-01-2-1", 7),
                new Held("E-00000004", "L4", "CDC01-B-6-61-01-3-1", 0),
                new Held("E-00000005", "L5", "SHELF-1", 2)));
    assertThat(groups).hasSize(3);
    assertThat(groups.get(0).zone()).isEqualTo("C");
    assertThat(groups.get(0).floor()).isEqualTo("5");
    assertThat(groups.get(0).lines()).hasSize(2);
    assertThat(groups.get(0).lines().get(0).qty()).isEqualTo(13);
    assertThat(groups.get(1).zone()).isEqualTo("B");
    assertThat(groups.get(1).lines()).hasSize(1); // 0 은 뺀다
    assertThat(groups.get(2).zone()).isNull();
  }

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
