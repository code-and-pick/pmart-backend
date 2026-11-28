package com.pmart.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * LAN 컨테이너와 같은 <b>환경변수</b>만으로 Spring Cloud Vault 가 서비스 비밀을 읽는지 확인한다({cipher} 대체).
 *
 * <ul>
 *   <li>Vault 는 TLS(사설 CA 흉내 — dev-tls 의 CA 를 PEM 으로 신뢰), AppRole 로그인, 서비스 정책은 자기 경로만
 *   <li>경로 {@code config/<앱>,lan}(profile-separator {@code ,}), default-context 없음
 *   <li>같은 속성이 Git(설정 저장소)과 Vault 에 있으면 Vault 가 이긴다(import 순서)
 *   <li>Spring 은 목록을 우선순위 높은 출처 하나에서 통째로 받는다 → signing.keys[0] 는 kid 도 Vault 에 둬야 한다
 *   <li>환경변수가 없으면(로컬·시험) Vault 없이 뜬다 — {@link VaultDefaultsPostProcessor}
 * </ul>
 */
class VaultClientLanIT {

  static final String CA_IN_CONTAINER = "/tmp/vault-ca.pem"; // -dev-tls-cert-dir 는 이미 있는 폴더여야 한다

  static GenericContainer<?> vault;
  static Path caPem;
  static Map<String, String> roles = new HashMap<>(); // 앱 → "roleId secretId"

  @TempDir static Path tmp;

  @BeforeAll
  static void startVault() throws Exception {
    vault =
        new GenericContainer<>("hashicorp/vault:1.21.4")
            .withCommand(
                "server",
                "-dev",
                "-dev-tls",
                "-dev-tls-cert-dir=/tmp",
                "-dev-tls-san=localhost",
                "-dev-tls-san=127.0.0.1",
                "-dev-root-token-id=root",
                "-dev-listen-address=0.0.0.0:8200")
            .withExposedPorts(8200)
            .waitingFor(Wait.forLogMessage(".*Vault server started.*", 1));
    vault.start();
    caPem = tmp.resolve("vault-ca.pem");
    vault.copyFileFromContainer(CA_IN_CONTAINER, caPem.toString());

    sh("vault secrets enable -path=config -version=2 kv");
    // demo-service: Git 과 같은 속성(비밀번호·서명키 목록 kid+private-key)을 Vault 에도 둔다
    sh(
        "vault kv put 'config/demo-service,lan' spring.datasource.password=from-vault"
            + " 'pmart.auth.signing.keys[0].kid=k-vault' 'pmart.auth.signing.keys[0].private-key=pk-vault'");
    // onlykey-service: Vault 에 private-key 만 — 목록이 통째로 바뀌어 Git 의 kid 가 사라지는 것을 보인다
    sh(
        "vault kv put 'config/onlykey-service,lan' 'pmart.auth.signing.keys[0].private-key=pk-vault'");
    // 다른 서비스 값 — demo-service 정책으로는 못 읽어야 한다
    sh("vault kv put 'config/other-service,lan' secret=other");
    sh("vault auth enable approle");
    for (String app : List.of("demo-service", "onlykey-service")) {
      sh(
          "printf 'path \"config/data/"
              + app
              + "\" { capabilities = [\"read\"] }\\npath \"config/data/"
              + app
              + ",*\" { capabilities = [\"read\"] }\\n' | vault policy write svc-"
              + app
              + " -");
      sh("vault write auth/approle/role/" + app + " token_policies=svc-" + app + " token_ttl=1h");
      String roleId = sh("vault read -field=role_id auth/approle/role/" + app + "/role-id").strip();
      String secretId =
          sh("vault write -f -field=secret_id auth/approle/role/" + app + "/secret-id").strip();
      roles.put(app, roleId + " " + secretId);
    }
  }

  @AfterAll
  static void stopVault() {
    if (vault != null) {
      vault.stop();
    }
  }

  static String sh(String cmd) throws Exception {
    var r =
        vault.execInContainer(
            "sh",
            "-c",
            "export VAULT_ADDR=https://127.0.0.1:8200 VAULT_CACERT="
                + CA_IN_CONTAINER
                + " VAULT_TOKEN=root; "
                + cmd);
    assertThat(r.getExitCode()).as(cmd + " → " + r.getStderr()).isZero();
    return r.getStdout();
  }

  /** render.sh 가 LAN 컨테이너에 줄 환경변수(이름 그대로 — relaxed binding 확인). */
  static Map<String, Object> lanEnv(String app) {
    String[] ids = roles.get(app).split(" ");
    Map<String, Object> env = new HashMap<>();
    env.put("SPRING_APPLICATION_NAME", app);
    env.put("SPRING_PROFILES_ACTIVE", "lan");
    // 실제는 optional:configserver:,vault:// — 시험은 설정 저장소 대신 classpath 의 Git 흉내 파일
    env.put("SPRING_CONFIG_IMPORT", "optional:classpath:git-like-" + app + ".yml,vault://");
    env.put("SPRING_CLOUD_VAULT_ENABLED", "true");
    env.put("SPRING_CLOUD_VAULT_URI", "https://localhost:" + vault.getMappedPort(8200));
    env.put("SPRING_CLOUD_VAULT_FAILFAST", "true");
    env.put("SPRING_CLOUD_VAULT_AUTHENTICATION", "APPROLE");
    env.put("SPRING_CLOUD_VAULT_APPROLE_ROLEID", ids[0]);
    env.put("SPRING_CLOUD_VAULT_APPROLE_SECRETID", ids[1]);
    env.put("SPRING_CLOUD_VAULT_KV_BACKEND", "config");
    env.put("SPRING_CLOUD_VAULT_KV_PROFILESEPARATOR", ",");
    env.put("SPRING_CLOUD_VAULT_KV_DEFAULTCONTEXT", "");
    env.put("SPRING_CLOUD_VAULT_SSL_TRUSTSTORE", "file:" + caPem.toAbsolutePath());
    env.put("SPRING_CLOUD_VAULT_SSL_TRUSTSTORETYPE", "PEM");
    return env;
  }

  static ConfigurableApplicationContext run(Map<String, Object> env) {
    StandardEnvironment environment = new StandardEnvironment();
    // 실제 OS 환경변수 대신 위 값만(시험 PC 의 환경에 흔들리지 않게)
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
    return new SpringApplicationBuilder(TestApp.class)
        .web(WebApplicationType.NONE)
        .environment(environment)
        .run();
  }

  @Test
  void lanEnvironmentReadsOwnSecretsFromVaultOverTlsAndVaultWins() {
    try (var ctx = run(lanEnv("demo-service"))) {
      assertThat(ctx.getEnvironment().getProperty("spring.datasource.password"))
          .isEqualTo("from-vault");
      Signing signing = ctx.getBean(Signing.class);
      assertThat(signing.keys()).hasSize(1);
      assertThat(signing.keys().get(0).kid()).isEqualTo("k-vault");
      assertThat(signing.keys().get(0).privateKey()).isEqualTo("pk-vault");
      // 다른 서비스 경로는 정책으로 막혀 들어오지 않는다
      assertThat(ctx.getEnvironment().getProperty("secret")).isNull();
    }
  }

  @Test
  void listIsReplacedWholeSoKidMustAlsoBeInVault() {
    try (var ctx = run(lanEnv("onlykey-service"))) {
      Signing signing = ctx.getBean(Signing.class);
      assertThat(signing.keys()).hasSize(1);
      assertThat(signing.keys().get(0).privateKey()).isEqualTo("pk-vault");
      // Git 흉내 파일에는 kid 가 있지만 Vault 목록이 통째로 이겨 kid 가 없다
      assertThat(signing.keys().get(0).kid()).isNull();
    }
  }

  @Test
  void withoutLanEnvironmentStartsWithoutVault() {
    try (var ctx = run(Map.of("SPRING_APPLICATION_NAME", "demo-service"))) {
      assertThat(ctx.getEnvironment().getProperty("spring.cloud.vault.enabled")).isEqualTo("false");
      assertThat(ctx.getEnvironment().getProperty("spring.datasource.password")).isNull();
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EnableConfigurationProperties(Signing.class)
  static class TestApp {}

  @ConfigurationProperties("pmart.auth.signing")
  record Signing(List<Key> keys) {
    Signing {
      keys = keys == null ? List.of() : keys;
    }
  }

  record Key(String kid, String privateKey) {}
}
