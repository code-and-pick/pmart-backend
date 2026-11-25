package com.pmart.cdc.outbound.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PickMoveServiceTest {

  @Test
  void stagingSlotKeepsColdZonesAndMapsChilgokAmbientByFloor() {
    // 냉동·냉장은 구역 글자 그대로
    assertThat(PickMoveService.stagingCode("CDC01-A-5-61-03-2-1", "01"))
        .isEqualTo("CDC01-A-5-91-01-0-1");
    assertThat(PickMoveService.stagingCode("CDC01-B-6-93-01-1-7", "02"))
        .isEqualTo("CDC01-B-6-91-02-0-1");
    // 칠곡 상온: 5층 → C, 7층 → G
    assertThat(PickMoveService.stagingCode("CDC01-E-5-61-01-1-1", "03"))
        .isEqualTo("CDC01-C-5-91-03-0-1");
    assertThat(PickMoveService.stagingCode("CDC01-H-7-61-10-16-2", "05"))
        .isEqualTo("CDC01-G-7-91-05-0-1");
    // 다른 센터는 구역 그대로, 7구간이 아니면 없음
    assertThat(PickMoveService.stagingCode("CDC08-E-5-61-01-1-1", "01"))
        .isEqualTo("CDC08-E-5-91-01-0-1");
    assertThat(PickMoveService.stagingCode("SHELF-1", "01")).isNull();
  }
}
