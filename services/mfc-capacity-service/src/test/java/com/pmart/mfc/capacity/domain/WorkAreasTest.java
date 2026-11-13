package com.pmart.mfc.capacity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkAreasTest {

  @Test
  void allowedAreasAreThoseOverlappingEffectiveTaskTypesInContractOrder() {
    assertThat(WorkAreas.allowed(Set.of("PACK", "RETURN")))
        .containsExactly("STOCK_MOVE", "PICK_PACK");
    assertThat(WorkAreas.allowed(Set.of("RECEIVE", "INSPECT")))
        .containsExactly("INBOUND", "INSPECTION");
    assertThat(WorkAreas.allowed(Set.of())).isEmpty();
    // 배차 요청(점원)은 어느 업무에도 없다, 실사는 아직 작업 유형이 없다
    assertThat(WorkAreas.allowed(Set.of("DISPATCH"))).isEmpty();
    assertThat(WorkAreas.TASK_TYPES.get("STOCK_COUNT")).isEmpty();
  }

  @Test
  void narrowKeepsOnlyChosenAreaWithinEffective() {
    Set<String> eff = Set.of("PICK", "PACK", "MOVE");
    assertThat(WorkAreas.narrow(eff, null)).isEqualTo(eff);
    assertThat(WorkAreas.narrow(eff, "PICK_PACK")).containsExactly("PACK", "PICK");
    assertThat(WorkAreas.narrow(eff, "STOCK_MOVE")).containsExactly("MOVE");
    assertThat(WorkAreas.narrow(eff, "INBOUND")).isEmpty();
    assertThatThrownBy(() -> WorkAreas.narrow(eff, "NOPE"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
