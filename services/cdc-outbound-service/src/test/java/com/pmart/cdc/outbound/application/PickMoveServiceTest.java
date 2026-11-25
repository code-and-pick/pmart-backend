package com.pmart.cdc.outbound.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.cdc.outbound.infrastructure.LocationClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PickMoveServiceTest {

  @Test
  void stagingIsChosenByFloorAndTemperatureZoneNotByLetter() {
    var c = PickMoveService.stagingCandidates("CDC01-C-5-61-03-2-1", "01");
    assertThat(c).hasSize(26).contains("CDC01-A-5-91-01-0-1", "CDC01-H-5-91-01-0-1");
    assertThat(PickMoveService.stagingCandidates("SHELF-1", "01")).isEmpty();
    // 새 배치(R-22): 5층 상온 C 선반 → 상온 A-5-91(냉동 H-5-91 은 온도대가 달라 아님)
    Map<String, LocationClient.Place> neu =
        Map.of(
            "CDC01-C-5-61-03-2-1", place("CDC01-C-5-61-03-2-1", "PICK_FACE", "AMBIENT"),
            "CDC01-A-5-91-01-0-1", place("CDC01-A-5-91-01-0-1", "STAGING", "AMBIENT"),
            "CDC01-H-5-91-01-0-1", place("CDC01-H-5-91-01-0-1", "STAGING", "FROZEN"));
    assertThat(PickMoveService.pickStaging("CDC01-C-5-61-03-2-1", c, neu))
        .isEqualTo("CDC01-A-5-91-01-0-1");
    // 옛 배치(LAN dd3d185): 5층 냉동 A 선반 → 냉동 A-5-91, 상온 C-5-91 아님
    var old = PickMoveService.stagingCandidates("CDC01-A-5-61-03-2-1", "01");
    Map<String, LocationClient.Place> was =
        Map.of(
            "CDC01-A-5-61-03-2-1", place("CDC01-A-5-61-03-2-1", "PICK_FACE", "FROZEN"),
            "CDC01-A-5-91-01-0-1", place("CDC01-A-5-91-01-0-1", "STAGING", "FROZEN"),
            "CDC01-C-5-91-01-0-1", place("CDC01-C-5-91-01-0-1", "STAGING", "AMBIENT"));
    assertThat(PickMoveService.pickStaging("CDC01-A-5-61-03-2-1", old, was))
        .isEqualTo("CDC01-A-5-91-01-0-1");
    // 옛 배치: 5층 상온 C 선반 → 상온 C-5-91, 6층 냉장 B 선반 → 냉장 B-6-91
    var oldC = PickMoveService.stagingCandidates("CDC01-C-5-61-03-2-1", "01");
    assertThat(
            PickMoveService.pickStaging(
                "CDC01-C-5-61-03-2-1",
                oldC,
                Map.of(
                    "CDC01-C-5-61-03-2-1", place("CDC01-C-5-61-03-2-1", "PICK_FACE", "AMBIENT"),
                    "CDC01-A-5-91-01-0-1", place("CDC01-A-5-91-01-0-1", "STAGING", "FROZEN"),
                    "CDC01-C-5-91-01-0-1", place("CDC01-C-5-91-01-0-1", "STAGING", "AMBIENT"))))
        .isEqualTo("CDC01-C-5-91-01-0-1");
    var oldB = PickMoveService.stagingCandidates("CDC01-B-6-63-01-1-1", "02");
    assertThat(
            PickMoveService.pickStaging(
                "CDC01-B-6-63-01-1-1",
                oldB,
                Map.of(
                    "CDC01-B-6-63-01-1-1", place("CDC01-B-6-63-01-1-1", "PICK_FACE", "CHILLED"),
                    "CDC01-B-6-91-02-0-1", place("CDC01-B-6-91-02-0-1", "STAGING", "CHILLED"))))
        .isEqualTo("CDC01-B-6-91-02-0-1");
    // 새 배치: 6층 냉장 G 선반 → 냉장 G-6-91
    var newG = PickMoveService.stagingCandidates("CDC01-G-6-63-01-1-1", "02");
    assertThat(
            PickMoveService.pickStaging(
                "CDC01-G-6-63-01-1-1",
                newG,
                Map.of(
                    "CDC01-G-6-63-01-1-1", place("CDC01-G-6-63-01-1-1", "PICK_FACE", "CHILLED"),
                    "CDC01-G-6-91-02-0-1", place("CDC01-G-6-91-02-0-1", "STAGING", "CHILLED"))))
        .isEqualTo("CDC01-G-6-91-02-0-1");
    // 원위치를 모르거나 맞는 자리가 없으면 null
    assertThat(PickMoveService.pickStaging("CDC01-B-6-61-01-1-1", c, neu)).isNull();
  }

  static LocationClient.Place place(String code, String type, String zone) {
    return new LocationClient.Place(code, type, "ACTIVE", zone);
  }
}
