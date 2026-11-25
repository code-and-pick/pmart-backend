package com.pmart.cdc.outbound.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.cdc.outbound.domain.BatchPlan.Item;
import com.pmart.cdc.outbound.domain.BatchPlan.ShareState;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BatchPlanTest {

  static final Instant D1 = Instant.parse("2026-11-26T01:00:00Z");
  static final Instant D2 = Instant.parse("2026-11-26T05:00:00Z");

  static Item item(
      String trf,
      String mfc,
      Instant due,
      @Nullable String region,
      String sku,
      String loc,
      int qty,
      @Nullable String method) {
    return new Item(trf, mfc, due, region, sku, "L-" + sku, loc, qty, method);
  }

  @Test
  void noMethodStaysDiscretePerTrfZoneFloorAndMergesSameLine() {
    var tasks =
        BatchPlan.plan(
            List.of(
                item("T1", "M1", D1, "R1", "S1", "CDC01-C-5-61-01-1-1", 10, null),
                item("T1", "M1", D1, "R1", "S2", "CDC01-C-5-61-02-1-1", 5, null),
                item("T1", "M1", D1, "R1", "S1", "CDC01-C-5-61-01-1-1", 3, null),
                item("T1", "M1", D1, "R1", "S3", "CDC01-B-6-61-01-2-1", 7, null),
                item("T1", "M1", D1, "R1", "S4", "CDC01-B-6-61-01-3-1", 0, null),
                item("T1", "M1", D1, "R1", "S5", "SHELF-1", 2, null),
                item("T2", "M2", D2, "R1", "S1", "CDC01-C-5-61-01-1-1", 4, null)),
            null);
    assertThat(tasks).hasSize(4); // T1 C-5, T1 B-6, T1 코드 밖, T2 C-5
    var first = tasks.get(0);
    assertThat(first.pickMode()).isEqualTo("DISCRETE");
    assertThat(first.trfId()).isEqualTo("T1");
    assertThat(first.zone()).isEqualTo("C");
    assertThat(first.lines()).hasSize(2);
    assertThat(first.lines().get(0).qty()).isEqualTo(13);
    assertThat(tasks.get(1).lines()).hasSize(1); // 0 은 뺀다
    assertThat(tasks.get(2).zone()).isNull();
    assertThat(tasks.get(3).trfId()).isEqualTo("T2");
  }

  @Test
  void regionBatchMergesTrfsOfSameRegionAndLineBatchMergesAll() {
    var tasks =
        BatchPlan.plan(
            List.of(
                item("T1", "BJG", D2, "BUSAN", "S1", "CDC01-E-5-61-01-1-1", 36, "FC"),
                item("T2", "BNG", D1, "BUSAN", "S1", "CDC01-E-5-61-01-1-1", 24, "FC"),
                item("T3", "MOK", D1, "HONAM", "S1", "CDC01-E-5-61-01-1-1", 5, "FC"),
                item("T1", "BJG", D2, "BUSAN", "S9", "CDC01-B-6-62-01-1-1", 3, "LINE"),
                item("T3", "MOK", D1, "HONAM", "S9", "CDC01-B-6-62-01-1-1", 2, "LINE"),
                item("T1", "BJG", D2, "BUSAN", "S7", "CDC01-A-5-61-01-1-1", 1, "DPS"),
                item("T4", "XXX", D1, null, "S1", "CDC01-E-5-61-01-1-1", 1, "FC")),
            null);
    assertThat(tasks)
        .extracting(BatchPlan.Task::pickMode)
        .containsExactly("REGION_BATCH", "REGION_BATCH", "SKU_BATCH", "DISCRETE", "DISCRETE");
    var busan = tasks.get(0);
    assertThat(busan.regionCode()).isEqualTo("BUSAN");
    assertThat(busan.trfId()).isNull();
    assertThat(busan.dueAt()).isEqualTo(D1); // 몫 중 가장 이른 기한
    assertThat(busan.lines()).hasSize(1);
    assertThat(busan.lines().get(0).qty()).isEqualTo(60); // A36·B24 = 60
    assertThat(busan.lines().get(0).shares())
        .extracting(BatchPlan.Share::trfId, BatchPlan.Share::qty)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("T1", 36),
            org.assertj.core.groups.Tuple.tuple("T2", 24));
    var line = tasks.get(2);
    assertThat(line.pickMethod()).isEqualTo("LINE");
    assertThat(line.regionCode()).isNull();
    assertThat(line.lines().get(0).shares()).hasSize(2); // 지역이 달라도 SKU 묶음
    assertThat(tasks.get(3).pickMethod()).isEqualTo("DPS");
    assertThat(tasks.get(4).trfId()).isEqualTo("T4"); // 지역 없는 TRF 는 단건
  }

  @Test
  void maxLinesSplitsBatchOnly() {
    var items =
        List.of(
            item("T1", "BJG", D1, "BUSAN", "S1", "CDC01-E-5-61-01-1-1", 1, "FC"),
            item("T1", "BJG", D1, "BUSAN", "S2", "CDC01-E-5-61-02-1-1", 1, "FC"),
            item("T1", "BJG", D1, "BUSAN", "S3", "CDC01-E-5-61-03-1-1", 1, "FC"),
            item("T1", "BJG", D1, "BUSAN", "S4", "CDC01-C-5-01-01-1-1", 1, null),
            item("T1", "BJG", D1, "BUSAN", "S5", "CDC01-C-5-01-02-1-1", 1, null),
            item("T1", "BJG", D1, "BUSAN", "S6", "CDC01-C-5-01-03-1-1", 1, null));
    var tasks = BatchPlan.plan(items, 2);
    assertThat(tasks).extracting(t -> t.lines().size()).containsExactly(2, 1, 3);
  }

  @Test
  void pickedFillsEarliestDueFirstShortFromLatest() {
    var shares =
        List.of(
            new ShareState("T-B", D2, 24, 0, 0),
            new ShareState("T-A", D1, 36, 0, 0),
            new ShareState("T-C", D1, 10, 0, 0));
    // 70 계획에 실피킹 56·부족 14 — 기한 이른 A·C 먼저 채우고, 부족은 기한 늦은 B 부터
    var r = BatchPlan.attribute(shares, 56, 14);
    assertThat(r.get("T-A")).containsExactly(36, 0);
    assertThat(r.get("T-C")).containsExactly(10, 0);
    assertThat(r.get("T-B")).containsExactly(10, 14);
    // 같은 기한이면 trfId 순 — 부족은 뒤쪽(T-C)부터
    var tie =
        BatchPlan.attribute(
            List.of(new ShareState("T-A", D1, 5, 0, 0), new ShareState("T-C", D1, 5, 0, 0)), 6, 4);
    assertThat(tie.get("T-A")).containsExactly(5, 0);
    assertThat(tie.get("T-C")).containsExactly(1, 4);
    // 나눠 보낸 실적 — 이미 채운 몫은 건너뜀
    var later =
        BatchPlan.attribute(
            List.of(new ShareState("T-A", D1, 5, 5, 0), new ShareState("T-B", D2, 5, 1, 0)), 2, 0);
    assertThat(later).containsOnlyKeys("T-B");
    assertThatThrownBy(() -> BatchPlan.attribute(List.of(new ShareState("T-A", D1, 5, 4, 0)), 2, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dpsSplitsPerAisleOtherModesHaveNoAisle() {
    var tasks =
        BatchPlan.plan(
            List.of(
                item("T1", "BJG", D1, "BUSAN", "S1", "CDC01-A-5-61-01-1-1", 2, "DPS"),
                item("T1", "BJG", D1, "BUSAN", "S2", "CDC01-A-5-61-02-1-1", 1, "DPS"),
                item("T1", "BJG", D1, "BUSAN", "S3", "CDC01-A-5-62-01-1-1", 4, "DPS"),
                item("T2", "BNG", D2, "BUSAN", "S3", "CDC01-A-5-62-01-1-1", 1, "DPS"),
                item("T1", "BJG", D1, "BUSAN", "S4", "CDC01-A-5-01-01-2-1", 3, null),
                item("T1", "BJG", D1, "BUSAN", "S5", "CDC01-E-5-61-01-1-1", 3, "FC")),
            null);
    // T1 통로 61·62, T2 통로 62(TRF 끼리 묶지 않음), 랙 단건, FC 지역 묶음
    assertThat(tasks)
        .extracting(BatchPlan.Task::trfId, BatchPlan.Task::aisle)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("T1", "61"),
            org.assertj.core.groups.Tuple.tuple("T1", "62"),
            org.assertj.core.groups.Tuple.tuple("T2", "62"),
            org.assertj.core.groups.Tuple.tuple("T1", null),
            org.assertj.core.groups.Tuple.tuple(null, null));
    assertThat(tasks.get(0).lines()).hasSize(2);
  }
}
