package com.pmart.cdc.location.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.cdc.location.domain.LocationCode.Violation;
import org.junit.jupiter.api.Test;

class LocationCodeTest {

  static String rules(String code, String temp, String type) {
    return String.join(
        ",", LocationCode.check(code, "CDC01", temp, type).stream().map(Violation::rule).toList());
  }

  @Test
  void parsesSevenParts() {
    var p = LocationCode.parse("CDC01-A-5-02-24-3-1").orElseThrow();
    assertThat(p.area()).isEqualTo("A");
    assertThat(p.floor()).isEqualTo("5");
    assertThat(p.aisle()).isEqualTo("02");
    assertThat(p.bay()).isEqualTo("24");
    assertThat(p.level()).isEqualTo(3);
    assertThat(p.slot()).isEqualTo(1);
    assertThat(LocationCode.parse("CDC01-A-5-2-24-3-1")).isEmpty();
  }

  @Test
  void chilgokExamplesFromDecisionsPass() {
    assertThat(rules("CDC01-A-5-02-24-3-1", "AMBIENT", "RESERVE")).isEmpty();
    assertThat(rules("CDC01-H-5-05-10-1-2", "FROZEN", "RESERVE")).isEmpty();
    assertThat(rules("CDC01-A-5-61-03-2-1", "AMBIENT", "PICK_FACE")).isEmpty(); // R-13 예
    assertThat(rules("CDC01-G-6-63-27-5-3", "CHILLED", "PICK_FACE")).isEmpty();
    assertThat(rules("CDC01-G-6-90-03-0-1", "CHILLED", "DOCK")).isEmpty();
    assertThat(rules("CDC01-G-6-91-02-0-1", "CHILLED", "STAGING")).isEmpty();
    assertThat(rules("CDC01-E-7-92-01-0-1", "AMBIENT", "STAGING")).isEmpty();
    assertThat(rules("CDC01-A-5-01-01-4-1", "AMBIENT", "QUARANTINE")).isEmpty();
    assertThat(rules("CDC01-A-5-01-01-3-2", "AMBIENT", "TEMPORARY")).isEmpty();
  }

  @Test
  void zoneLetterDecidesTemperature() {
    assertThat(rules("CDC01-H-5-01-01-3-1", "AMBIENT", "RESERVE"))
        .isEqualTo("TEMPERATURE_ZONE_MISMATCH");
    assertThat(rules("CDC01-Z-5-01-01-3-1", "AMBIENT", "RESERVE")).isEqualTo("UNKNOWN_AREA");
  }

  @Test
  void rackLimitsFourLevelsTwoSlotsFortyEightBays() {
    assertThat(rules("CDC01-A-5-01-01-5-1", "AMBIENT", "RESERVE")).isEqualTo("RACK_LEVEL");
    assertThat(rules("CDC01-A-5-01-48-3-1", "AMBIENT", "RESERVE")).isEmpty();
    assertThat(rules("CDC01-A-5-01-49-3-1", "AMBIENT", "RESERVE")).isEqualTo("RACK_BAY");
    assertThat(rules("CDC01-A-5-01-01-3-3", "AMBIENT", "RESERVE")).isEqualTo("RACK_SLOT");
    // R-19: 1단 박스 피킹면만 PICK_FACE(그 위 단은 보관)
    assertThat(rules("CDC01-A-5-01-01-1-1", "AMBIENT", "PICK_FACE")).isEmpty();
    assertThat(rules("CDC01-A-5-01-01-3-1", "AMBIENT", "PICK_FACE"))
        .isEqualTo("RACK_PICK_FACE_LEVEL");
    assertThat(rules("CDC01-A-5-60-01-1-1", "AMBIENT", "RESERVE")).isEmpty();
    assertThat(rules("CDC01-A-B1-01-01-3-1", "AMBIENT", "RESERVE")).isEqualTo("RACK_FLOOR");
  }

  @Test
  void shelvesFromAisle61FiveLevelsThreeSlotsPickFace() {
    assertThat(rules("CDC01-A-5-61-03-6-1", "AMBIENT", "PICK_FACE")).isEqualTo("SHELF_LEVEL");
    assertThat(rules("CDC01-A-5-61-03-2-4", "AMBIENT", "PICK_FACE")).isEqualTo("SHELF_SLOT");
    assertThat(rules("CDC01-A-5-61-00-2-1", "AMBIENT", "PICK_FACE")).isEqualTo("SHELF_BAY");
    assertThat(rules("CDC01-A-5-61-03-2-1", "AMBIENT", "RESERVE")).isEqualTo("SHELF_TYPE");
    assertThat(rules("CDC01-A-B1-61-03-2-1", "AMBIENT", "PICK_FACE")).isEqualTo("SHELF_FLOOR");
  }

  @Test
  void nonRackAislesUseLevelZeroSlotOne() {
    assertThat(rules("CDC01-G-6-91-02-1-1", "CHILLED", "STAGING")).isEqualTo("NON_RACK_LEVEL_SLOT");
    assertThat(rules("CDC01-G-B1-90-03-0-1", "CHILLED", "DOCK")).isEqualTo("DOCK_FLOOR");
    assertThat(rules("CDC01-G-6-91-02-0-1", "CHILLED", "RESERVE")).isEqualTo("STAGING_TYPE");
    assertThat(rules("CDC01-G-6-95-02-0-1", "CHILLED", "STAGING")).isEqualTo("RESERVED_AISLE");
  }

  @Test
  void pickFaceMinTargetOnlyOnPickFace() {
    assertThat(LocationCode.check("CDC01-A-5-61-01-1-1", "CDC01", "AMBIENT", "PICK_FACE", 2, 6))
        .isEmpty();
    assertThat(LocationCode.check("CDC01-A-5-61-01-1-1", "CDC01", "AMBIENT", "PICK_FACE", 6, 2))
        .extracting(Violation::rule)
        .containsExactly("TARGET_BELOW_MIN");
    assertThat(LocationCode.check("CDC01-A-5-01-01-3-1", "CDC01", "AMBIENT", "RESERVE", 2, null))
        .extracting(Violation::rule)
        .containsExactly("PICK_FACE_ONLY");
  }

  @Test
  void otherCentersOnlyNeedPatternAndPrefix() {
    assertThat(LocationCode.check("CDC02-Q-3-99-99-9-9", "CDC02", "AMBIENT", "RESERVE")).isEmpty();
    assertThat(LocationCode.check("CDC02-Q-3-99-99-9-9", "CDC03", "AMBIENT", "RESERVE"))
        .extracting(Violation::rule)
        .containsExactly("CENTER_PREFIX_MISMATCH");
  }

  @Test
  void stateTransitions() {
    assertThat(LocationStates.next("DRAFT", "ACTIVATE")).contains("ACTIVE");
    assertThat(LocationStates.next("ACTIVE", "BLOCK")).contains("BLOCKED");
    assertThat(LocationStates.next("BLOCKED", "UNBLOCK")).contains("ACTIVE");
    assertThat(LocationStates.next("BLOCKED", "DEACTIVATE")).contains("INACTIVE");
    assertThat(LocationStates.next("INACTIVE", "ACTIVATE")).contains("ACTIVE");
    assertThat(LocationStates.next("DRAFT", "RETIRE")).contains("RETIRED");
    assertThat(LocationStates.next("INACTIVE", "RETIRE")).isEmpty();
    assertThat(LocationStates.next("ACTIVE", "RETIRE")).isEmpty();
    assertThat(LocationStates.next("RETIRED", "ACTIVATE")).isEmpty();
  }

  @org.junit.jupiter.api.Test
  void r19RackFirstLevelPickFaceFloorLaneAndAutostore() {
    // 랙 1단 박스 피킹면만 PICK_FACE, 2단은 아님
    assertThat(rules("CDC01-A-5-01-01-1-1", "AMBIENT", "PICK_FACE")).isEmpty();
    assertThat(rules("CDC01-A-5-01-01-2-1", "AMBIENT", "PICK_FACE"))
        .isEqualTo("RACK_PICK_FACE_LEVEL");
    assertThat(rules("CDC01-A-5-01-01-1-1", "AMBIENT", "STAGING")).isEqualTo("RACK_TYPE");
    // 바닥 레인 93
    assertThat(rules("CDC01-G-6-93-13-1-44", "CHILLED", "PICK_FACE")).isEmpty();
    assertThat(rules("CDC01-G-6-93-01-2-1", "CHILLED", "PICK_FACE")).isEqualTo("LANE_LEVEL");
    assertThat(rules("CDC01-G-6-93-01-1-1", "CHILLED", "RESERVE")).isEqualTo("LANE_TYPE");
    assertThat(rules("CDC01-G-6-94-01-1-1", "CHILLED", "PICK_FACE")).isEqualTo("RESERVED_AISLE");
    // 7층 H 61~62 오토스토어 16단 2칸, 다른 선반은 5단 3칸 그대로
    assertThat(rules("CDC01-F-7-61-94-16-2", "AMBIENT", "PICK_FACE")).isEmpty();
    assertThat(rules("CDC01-F-7-62-01-17-1", "AMBIENT", "PICK_FACE")).isEqualTo("AUTOSTORE_LEVEL");
    assertThat(rules("CDC01-F-7-61-01-1-3", "AMBIENT", "PICK_FACE")).isEqualTo("AUTOSTORE_SLOT");
    assertThat(rules("CDC01-F-7-63-01-6-1", "AMBIENT", "PICK_FACE")).isEqualTo("SHELF_LEVEL");
    assertThat(rules("CDC01-E-7-61-01-6-1", "AMBIENT", "PICK_FACE")).isEqualTo("SHELF_LEVEL");
  }
}
