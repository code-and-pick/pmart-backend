package com.pmart.product.content.domain;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 상품 사진 규격(0.8.0 — 2026-11-24 사용자 "사진이 들어가는 곳 옆에 사진 규격 필요"·"가로 세로 크기나 비율이 필요한건데"·"저 대안대로 작업"). 고객
 * 앱은 대표 사진을 정사각형 칸에, 상세 설명·상세정보 표 사진은 화면 폭에 맞춰 길게 그린다. **막지 않고 경고만**(warnings) — 일괄 등록(seed zip)으로
 * 크기가 제각각인 사진이 들어오기 때문(제출 막기 problems 와 따로).
 */
public final class ContentImageSpec {

  /** 대표 사진 권장 — 1:1, 1000×1000 이상. */
  public static final int MAIN_MIN = 1000;

  /** 상세 설명·상세정보 표 권장 가로. 세로는 자유. */
  public static final int DETAIL_MIN_WIDTH = 1000;

  /** 대표 사진 비율 허용 범위(±10%). */
  public static final double RATIO_TOLERANCE = 0.10;

  public static final String MAIN_IMAGE_NOT_SQUARE = "MAIN_IMAGE_NOT_SQUARE";
  public static final String MAIN_IMAGE_SMALL = "MAIN_IMAGE_SMALL";
  public static final String IMAGE_NARROW = "IMAGE_NARROW";
  public static final String IMAGE_SIZE_UNKNOWN = "IMAGE_SIZE_UNKNOWN";

  /** 사진 한 장(역할·순서·가로·세로). */
  public record Slot(
      String role, int sortOrder, @Nullable Integer width, @Nullable Integer height) {}

  private ContentImageSpec() {}

  public static List<ContentProblem> warnings(List<Slot> images) {
    List<ContentProblem> out = new ArrayList<>();
    for (Slot s : images) {
      String field = "images[" + s.role() + ":" + s.sortOrder() + "]";
      if (s.width() == null || s.height() == null) {
        out.add(new ContentProblem(IMAGE_SIZE_UNKNOWN, field, "가로·세로를 읽지 못했습니다."));
        continue;
      }
      int w = s.width();
      int h = s.height();
      if ("MAIN".equals(s.role())) {
        if (Math.abs((double) w / h - 1) > RATIO_TOLERANCE) {
          out.add(
              new ContentProblem(
                  MAIN_IMAGE_NOT_SQUARE,
                  field,
                  "대표 사진은 1:1(정사각형)이 권장입니다 — 지금 " + w + "×" + h + ", 앱에서 가장자리가 잘립니다."));
        }
        if (w < MAIN_MIN || h < MAIN_MIN) {
          out.add(
              new ContentProblem(
                  MAIN_IMAGE_SMALL,
                  field,
                  "대표 사진은 " + MAIN_MIN + "×" + MAIN_MIN + " 이상이 권장입니다 — 지금 " + w + "×" + h + "."));
        }
      } else if (w < DETAIL_MIN_WIDTH) {
        out.add(
            new ContentProblem(
                IMAGE_NARROW,
                field,
                "상세 사진은 가로 " + DETAIL_MIN_WIDTH + "px 가 권장입니다 — 지금 가로 " + w + "px(흐려 보일 수 있음)."));
      }
    }
    return out;
  }
}
