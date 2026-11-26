package com.pmart.cdc.location.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 7구간 LOC 코드 {@code 센터-구역-층-통로-베이-단-슬롯}(계약 LocationCode 패턴) 나누기와 센터별 문법 검사.
 *
 * <p>칠곡(CDC01) 문법은 docs/simulator/cdc/chilgok-dc.md CDC-3~5(사용자 2026-11-24·25 결정):
 *
 * <ul>
 *   <li>구역 글자 H 냉동·G 냉장·A~F 상온 — temperatureZone 은 글자에서 나온다(CDC-4, 0.6.0 R-22 — 사용자 2026-11-26 기사
 *       사진, 상온 랙 통로가 A 부터).
 *   <li>통로 01~60 = 팔레트 랙(입고·적치 보관): 층 5~7, 베이 01~48(R-17 랙 묶음 — 통로 한쪽 12 × 2), 단 1~4(R-12), 슬롯 1~2,
 *       RESERVE(R-13 — QUARANTINE·TEMPORARY 도 허용). **1단은 PICK_FACE 도 된다**(R-19 박스 피킹면 — 사용자
 *       2026-11-25).
 *   <li>통로 61~89 = 피킹 선반(R-13, MFC 선반 모양): 층 5~7, 베이 = 유닛(01~), 단 1~5, 칸 1~3, PICK_FACE. 단 **오토스토어
 *       61~62** (R-19 — 0.6.0 R-22 7층 F, 0.7.1 R-25 6층 C 더함): 베이 = 격자, 단 1~16, 칸 1~2.
 *   <li>통로 90 = DOCK(층마다 북쪽 벽 도크 — 0.6.0 R-22, 지하 B1 접안 없앰), 91 = STAGING 출고대기, 92 = STAGING 층 승강기
 *       문 — 모두 층 5~7·단 0·슬롯 1(CDC-5).
 *   <li>통로 93 = 바닥 레인(R-19, 6층 야채 분배): 층 5~7, 베이 01~, 단 1, 칸 1~, PICK_FACE. 94~99 는 비워 둔다.
 * </ul>
 *
 * 층 배치(어느 층에 어느 구역)는 자주 바뀌는 도면 값이라 검사하지 않는다 — 데이터(일괄 등록)로 넣는다.
 */
public final class LocationCode {

  public static final Pattern PATTERN =
      Pattern.compile("^[A-Z0-9]+-[A-Z]-[A-Z0-9]+-[0-9]{2}-[0-9]{2}-[0-9]+-[0-9]+$");

  public static final String CHILGOK = "CDC01";

  /** 칠곡 구역 글자 → 온도대. */
  public static final Map<String, String> CHILGOK_ZONES =
      Map.of(
          "A", "AMBIENT",
          "B", "AMBIENT",
          "C", "AMBIENT",
          "D", "AMBIENT",
          "E", "AMBIENT",
          "F", "AMBIENT",
          "G", "CHILLED",
          "H", "FROZEN");

  /** 오토스토어 구역-층(0.7.1 R-25 — 사용자 2026-11-26 "6F·7F 상온": 6층 C·7층 F, 통로 61~62). */
  public static final java.util.Set<String> AUTOSTORE_AREAS = java.util.Set.of("C-6", "F-7");

  /** 나눈 값. */
  public record Parts(
      String center, String area, String floor, String aisle, String bay, int level, int slot) {}

  /** 위반 하나(필드·규칙 이름). */
  public record Violation(String field, String rule) {}

  private LocationCode() {}

  public static Optional<Parts> parse(@Nullable String code) {
    if (code == null || !PATTERN.matcher(code).matches()) {
      return Optional.empty();
    }
    String[] p = code.split("-");
    return Optional.of(
        new Parts(p[0], p[1], p[2], p[3], p[4], Integer.parseInt(p[5]), Integer.parseInt(p[6])));
  }

  /**
   * 등록 행 검사.
   *
   * @return 위반 목록(비면 통과)
   */
  public static List<Violation> check(
      String code, String centerId, String temperatureZone, String locationType) {
    return check(code, centerId, temperatureZone, locationType, null, null);
  }

  /** 피킹면 최소·목표량까지 본다(PICK_FACE 만, 목표 ≥ 최소 — 둘 다 설정값이라 비워도 된다). */
  public static List<Violation> check(
      String code,
      String centerId,
      String temperatureZone,
      String locationType,
      @Nullable Integer pickFaceMin,
      @Nullable Integer pickFaceTarget) {
    List<Violation> out = new ArrayList<>();
    if ((pickFaceMin != null || pickFaceTarget != null) && !"PICK_FACE".equals(locationType)) {
      out.add(new Violation("pickFaceMinQty", "PICK_FACE_ONLY"));
    }
    if (pickFaceMin != null && pickFaceTarget != null && pickFaceTarget < pickFaceMin) {
      out.add(new Violation("pickFaceTargetQty", "TARGET_BELOW_MIN"));
    }
    Optional<Parts> parsed = parse(code);
    if (parsed.isEmpty()) {
      out.add(new Violation("locationCode", "PATTERN"));
      return out;
    }
    Parts p = parsed.get();
    if (!p.center().equals(centerId)) {
      out.add(new Violation("locationCode", "CENTER_PREFIX_MISMATCH"));
    }
    if (CHILGOK.equals(centerId)) {
      chilgok(p, temperatureZone, locationType, out);
    }
    return out;
  }

  private static void chilgok(
      Parts p, String temperatureZone, String locationType, List<Violation> out) {
    String zoneTemp = CHILGOK_ZONES.get(p.area());
    if (zoneTemp == null) {
      out.add(new Violation("locationCode", "UNKNOWN_AREA"));
    } else if (!zoneTemp.equals(temperatureZone)) {
      out.add(new Violation("temperatureZone", "TEMPERATURE_ZONE_MISMATCH"));
    }
    int aisle = Integer.parseInt(p.aisle());
    boolean upperFloor = p.floor().equals("5") || p.floor().equals("6") || p.floor().equals("7");
    int bay = Integer.parseInt(p.bay());
    if (aisle >= 1 && aisle <= 60) {
      // 팔레트 랙 = 입고·적치 보관 자리(R-13): 4단 2슬롯 모두 RESERVE
      if (!upperFloor) {
        out.add(new Violation("locationCode", "RACK_FLOOR"));
      }
      if (bay < 1 || bay > 48) {
        out.add(new Violation("locationCode", "RACK_BAY"));
      }
      if (p.level() < 1 || p.level() > 4) {
        out.add(new Violation("locationCode", "RACK_LEVEL"));
      }
      if (p.slot() < 1 || p.slot() > 2) {
        out.add(new Violation("locationCode", "RACK_SLOT"));
      }
      boolean boxPickFace = locationType.equals("PICK_FACE") && p.level() == 1; // R-19 1단 박스 피킹면
      if (!locationType.equals("RESERVE")
          && !locationType.equals("QUARANTINE")
          && !locationType.equals("TEMPORARY")
          && !boxPickFace) {
        out.add(
            new Violation(
                "locationType",
                locationType.equals("PICK_FACE") ? "RACK_PICK_FACE_LEVEL" : "RACK_TYPE"));
      }
    } else if (aisle >= 61 && aisle <= 89) {
      // 피킹 선반(R-13, MFC 선반과 같은 모양): 베이 = 유닛, 5단 3칸, 칸 하나 = SKU 하나, PICK_FACE
      if (!upperFloor) {
        out.add(new Violation("locationCode", "SHELF_FLOOR"));
      }
      if (bay < 1) {
        out.add(new Violation("locationCode", "SHELF_BAY"));
      }
      boolean autostore = AUTOSTORE_AREAS.contains(p.area() + "-" + p.floor()) && aisle <= 62;
      if (autostore) {
        // 오토스토어(R-19, 0.7.1 R-25 — 6층 C·7층 F 61~62): 베이 = 격자, 16단, 칸 2
        if (p.level() < 1 || p.level() > 16) {
          out.add(new Violation("locationCode", "AUTOSTORE_LEVEL"));
        }
        if (p.slot() < 1 || p.slot() > 2) {
          out.add(new Violation("locationCode", "AUTOSTORE_SLOT"));
        }
      } else {
        if (p.level() < 1 || p.level() > 5) {
          out.add(new Violation("locationCode", "SHELF_LEVEL"));
        }
        if (p.slot() < 1 || p.slot() > 3) {
          out.add(new Violation("locationCode", "SHELF_SLOT"));
        }
      }
      if (!locationType.equals("PICK_FACE")) {
        out.add(new Violation("locationType", "SHELF_TYPE"));
      }
    } else if (aisle == 90) {
      // 0.6.0 R-22: 도크는 층마다 북쪽 벽(사진 도크 문 번호 "4-16" = 층-번호) — 지하 B1 접안 없앰
      if (!upperFloor) {
        out.add(new Violation("locationCode", "DOCK_FLOOR"));
      }
      nonRack(p, locationType, "DOCK", out);
    } else if (aisle == 91 || aisle == 92) {
      if (!upperFloor) {
        out.add(new Violation("locationCode", "STAGING_FLOOR"));
      }
      nonRack(p, locationType, "STAGING", out);
    } else if (aisle == 93) {
      // 바닥 레인(R-19 — 6층 야채 분배): 베이 = 레인, 단 1, 칸 = 레인 자리
      if (!upperFloor) {
        out.add(new Violation("locationCode", "LANE_FLOOR"));
      }
      if (bay < 1) {
        out.add(new Violation("locationCode", "LANE_BAY"));
      }
      if (p.level() != 1) {
        out.add(new Violation("locationCode", "LANE_LEVEL"));
      }
      if (p.slot() < 1) {
        out.add(new Violation("locationCode", "LANE_SLOT"));
      }
      if (!locationType.equals("PICK_FACE")) {
        out.add(new Violation("locationType", "LANE_TYPE"));
      }
    } else {
      out.add(new Violation("locationCode", "RESERVED_AISLE"));
    }
  }

  private static void nonRack(Parts p, String locationType, String type, List<Violation> out) {
    if (p.level() != 0 || p.slot() != 1) {
      out.add(new Violation("locationCode", "NON_RACK_LEVEL_SLOT"));
    }
    if (!locationType.equals(type)) {
      out.add(new Violation("locationType", type + "_TYPE"));
    }
  }
}
