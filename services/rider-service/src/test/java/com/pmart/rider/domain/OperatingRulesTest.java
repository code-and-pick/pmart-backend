package com.pmart.rider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.rider.domain.Rider.OperatingStatus;
import com.pmart.rider.domain.Rider.Restriction;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperatingRulesTest {

  @Test
  void offDutyForcesNewDispatchOff() {
    var d = OperatingRules.decide(rider(Restriction.NORMAL), OperatingStatus.OFF_DUTY, true, null);
    assertThat(d.rejection()).isNull();
    assertThat(d.newDispatchEnabled()).isFalse();
  }

  @Test
  void onDutyDefaultsToFirstAreaMfc() {
    var d = OperatingRules.decide(rider(Restriction.NORMAL), OperatingStatus.ON_DUTY, true, null);
    assertThat(d.rejection()).isNull();
    assertThat(d.mfcId()).isEqualTo("MFC-A");
    assertThat(d.newDispatchEnabled()).isTrue();
  }

  @Test
  void restrictedRiderMayDriveButNotTakeNewDispatch() {
    Rider r = rider(Restriction.DISPATCH_RESTRICTED);
    assertThat(OperatingRules.decide(r, OperatingStatus.ON_DUTY, true, null).rejection())
        .isEqualTo(OperatingRules.Rejection.RESTRICTED);
    assertThat(OperatingRules.decide(r, OperatingStatus.ON_DUTY, false, null).rejection()).isNull();
  }

  @Test
  void suspendedOrTerminatedCannotStart() {
    for (Restriction x : List.of(Restriction.SUSPENDED, Restriction.TERMINATED)) {
      assertThat(OperatingRules.decide(rider(x), OperatingStatus.ON_DUTY, false, null).rejection())
          .isEqualTo(OperatingRules.Rejection.RESTRICTED);
      assertThat(OperatingRules.decide(rider(x), OperatingStatus.OFF_DUTY, false, null).rejection())
          .isNull();
    }
  }

  @Test
  void mfcOutsideAreasIsRejected() {
    assertThat(
            OperatingRules.decide(rider(Restriction.NORMAL), OperatingStatus.ON_DUTY, true, "MFC-Z")
                .rejection())
        .isEqualTo(OperatingRules.Rejection.MFC_NOT_IN_AREAS);
  }

  private static Rider rider(Restriction restriction) {
    Instant now = Instant.parse("2026-11-13T00:00:00Z");
    return new Rider(
        "RD-1",
        0,
        "req",
        null,
        "01012345678",
        "MOTORCYCLE",
        null,
        null,
        null,
        null,
        null,
        OperatingStatus.OFF_DUTY,
        false,
        null,
        now,
        false,
        Rider.DispatchMode.AI_RECOMMENDED,
        restriction,
        null,
        null,
        null,
        now,
        List.of(new Rider.Area("MFC-A", ""), new Rider.Area("MFC-B", "")),
        null,
        "123456",
        null,
        false);
  }
}
