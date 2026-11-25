package com.pmart.cdc.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/** 스캐폴딩 자체 검증: 컨트롤러 스텁이 생성된 계약 인터페이스를 구현한다. (업무 테스트는 4단계에서 추가) */
class ContractStubTest {

  @Test
  void controllersImplementGeneratedApiInterfaces() throws Exception {
    var scanner =
        new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(
            false);
    scanner.addIncludeFilter(
        new org.springframework.core.type.filter.AnnotationTypeFilter(RestController.class));
    var beans = scanner.findCandidateComponents("com.pmart.cdc.quality.api");
    assertThat(beans).as("api 패키지에 @RestController 스텁이 있어야 한다").isNotEmpty();
    for (var bd : beans) {
      Class<?> c = Class.forName(bd.getBeanClassName());
      assertThat(Arrays.stream(c.getInterfaces()).anyMatch(i -> i.getSimpleName().endsWith("Api")))
          .as(c.getName() + " implements *Api")
          .isTrue();
    }
  }
}
