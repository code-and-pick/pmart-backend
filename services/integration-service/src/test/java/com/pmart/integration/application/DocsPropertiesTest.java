package com.pmart.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocsPropertiesTest {

  @Test
  void defaultsAndIncludedFiles() {
    var p =
        new DocsProperties(
            "code-and-pick/pmart-all-docs", null, null, null, null, null, null, 0, null, null, -1);
    assertThat(p.branch()).isEqualTo("main");
    assertThat(p.githubApiUrl()).isEqualTo("https://api.github.com");
    assertThat(p.retries()).isZero();
    assertThat(p.included("operator/mfc/01-a.md")).isTrue();
    assertThat(p.included("operator/mfc/README.md")).isFalse();
    assertThat(p.included("README.md")).isFalse();
    assertThat(p.included(".gitattributes")).isFalse();
    assertThat(p.chunkingConfigured()).isFalse();
  }
}
