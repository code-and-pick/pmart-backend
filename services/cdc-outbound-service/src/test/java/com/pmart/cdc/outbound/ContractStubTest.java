package com.pmart.cdc.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/** 컨트롤러가 생성된 계약 인터페이스를 구현한다. */
class ContractStubTest {

  @Test
  void controllersImplementGeneratedApiInterfaces() throws Exception {
    var scanner =
        new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(
            false);
    scanner.addIncludeFilter(
        new org.springframework.core.type.filter.AnnotationTypeFilter(RestController.class));
    var beans = scanner.findCandidateComponents("com.pmart.cdc.outbound.api");
    assertThat(beans).hasSize(5);
    for (var bd : beans) {
      Class<?> c = Class.forName(bd.getBeanClassName());
      assertThat(Arrays.stream(c.getInterfaces()).anyMatch(i -> i.getSimpleName().endsWith("Api")))
          .as(c.getName() + " implements *Api")
          .isTrue();
    }
  }
}
