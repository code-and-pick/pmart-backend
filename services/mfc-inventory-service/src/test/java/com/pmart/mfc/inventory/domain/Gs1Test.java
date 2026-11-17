package com.pmart.mfc.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class Gs1Test {

  static final LocalDate EXP = LocalDate.of(2026, 12, 31);

  @Test
  void humanReadablePutsFixedFieldsFirstAndOmitsMissingExpiry() {
    assertThat(Gs1.humanReadable("02990000012345", "L261117000001", EXP))
        .isEqualTo("(01)02990000012345(17)261231(10)L261117000001");
    assertThat(Gs1.humanReadable("02990000012345", "L261117000001", null))
        .isEqualTo("(01)02990000012345(10)L261117000001");
    assertThat(Gs1.humanReadable(null, "L1", EXP)).isNull();
  }

  @Test
  void gtin14PadsKnownLengthsOnly() {
    assertThat(Gs1.gtin14("2990000012345")).isEqualTo("02990000012345");
    assertThat(Gs1.gtin14("08801155720019")).isEqualTo("08801155720019");
    assertThat(Gs1.gtin14("12345678")).isEqualTo("00000012345678");
    assertThat(Gs1.gtin14("ABC123")).isNull();
    assertThat(Gs1.gtin14("12345")).isNull();
  }

  @Test
  void parsesHumanReadableInAnyOrder() {
    var p = Gs1.parse("(01)02990000012345(10)L261117000001(17)261231");
    assertThat(p.gtin()).isEqualTo("02990000012345");
    assertThat(p.lot()).isEqualTo("L261117000001");
    assertThat(p.expiry()).isEqualTo(EXP);
  }

  @Test
  void parsesScannerRawWithSymbologyIdAndGroupSeparator() {
    // 생성 순서(가변 끝) — 구분자 없음
    var a = Gs1.parse("]C1" + "0102990000012345" + "17261231" + "10L261117000001");
    assertThat(a.gtin()).isEqualTo("02990000012345");
    assertThat(a.expiry()).isEqualTo(EXP);
    assertThat(a.lot()).isEqualTo("L261117000001");
    // 가변(10)이 가운데면 GS 로 끊는다
    var b = Gs1.parse("010299000001234510L261117000001" + Gs1.GS + "17261231");
    assertThat(b.lot()).isEqualTo("L261117000001");
    assertThat(b.expiry()).isEqualTo(EXP);
  }

  @Test
  void dayZeroMeansEndOfMonth() {
    assertThat(Gs1.parse("(17)270200").expiry()).isEqualTo(LocalDate.of(2027, 2, 28));
  }

  @Test
  void rejectsBadValues() {
    assertThatThrownBy(() -> Gs1.parse("")).isInstanceOf(Gs1.Invalid.class);
    assertThatThrownBy(() -> Gs1.parse("(01)123")).isInstanceOf(Gs1.Invalid.class);
    assertThatThrownBy(() -> Gs1.parse("(99)X")).isInstanceOf(Gs1.Invalid.class);
    assertThatThrownBy(() -> Gs1.parse("(17)261340")).isInstanceOf(Gs1.Invalid.class);
    assertThatThrownBy(() -> Gs1.parse("(10)" + "X".repeat(21))).isInstanceOf(Gs1.Invalid.class);
    assertThatThrownBy(() -> Gs1.parse("01029900")).isInstanceOf(Gs1.Invalid.class);
    assertThatThrownBy(() -> Gs1.parse("garbage(01)")).isInstanceOf(Gs1.Invalid.class);
  }
}
