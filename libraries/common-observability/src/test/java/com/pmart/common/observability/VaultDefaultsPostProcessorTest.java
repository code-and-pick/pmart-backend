package com.pmart.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class VaultDefaultsPostProcessorTest {

  private final VaultDefaultsPostProcessor processor = new VaultDefaultsPostProcessor();

  @Test
  void vaultIsOffByDefault() {
    MockEnvironment env = new MockEnvironment();

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("spring.cloud.vault.enabled")).isEqualTo("false");
  }

  @Test
  void lanContainerEnvironmentVariableWins() {
    MockEnvironment env = new MockEnvironment();
    // LAN 컨테이너만 SPRING_CLOUD_VAULT_ENABLED=true 를 준다
    env.getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("SPRING_CLOUD_VAULT_ENABLED", "true")));

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("spring.cloud.vault.enabled")).isEqualTo("true");
  }

  @Test
  void addedOnceEvenIfEnvironmentIsRebuilt() {
    MockEnvironment env = new MockEnvironment();

    processor.postProcessEnvironment(env, new SpringApplication());
    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(
            env.getPropertySources().stream()
                .filter(s -> s.getName().equals(VaultDefaultsPostProcessor.SOURCE_NAME))
                .count())
        .isEqualTo(1);
  }

  @Test
  void otherSourcesAreNotTouched() {
    MockEnvironment env = new MockEnvironment();
    env.getPropertySources()
        .addFirst(new MapPropertySource("configserver", Map.of("pmart.x", "y")));

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("pmart.x")).isEqualTo("y");
  }
}
