package com.pmart.product.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.product.content.domain.ContentImageSpec.Slot;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContentImageSpecTest {

  static List<String> codes(Slot... s) {
    return ContentImageSpec.warnings(List.of(s)).stream().map(ContentProblem::code).toList();
  }

  @Test
  void mainShouldBeSquareAndAtLeast1000() {
    assertThat(codes(new Slot("MAIN", 0, 1000, 1000))).isEmpty();
    assertThat(codes(new Slot("MAIN", 0, 1080, 1000))).as("±10% 안").isEmpty();
    assertThat(codes(new Slot("MAIN", 0, 1500, 1000))).containsExactly("MAIN_IMAGE_NOT_SQUARE");
    assertThat(codes(new Slot("MAIN", 0, 600, 600))).containsExactly("MAIN_IMAGE_SMALL");
  }

  @Test
  void detailImagesOnlyNeedWidthAndUnknownSizeWarns() {
    assertThat(codes(new Slot("DESCRIPTION", 1, 1000, 8000))).as("세로 자유").isEmpty();
    assertThat(codes(new Slot("DETAIL", 0, 780, 1200))).containsExactly("IMAGE_NARROW");
    assertThat(codes(new Slot("MAIN", 0, null, null))).containsExactly("IMAGE_SIZE_UNKNOWN");
    assertThat(ContentImageSpec.warnings(List.of(new Slot("DETAIL", 2, 500, 500))).get(0).field())
        .isEqualTo("images[DETAIL:2]");
  }
}
