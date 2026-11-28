package com.pmart.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * config-server Git + Vault 복합 백엔드(ADR-0101) — LAN 과 같은 프로필(git,vault)·AppRole(config-server, 정책
 * config/data/* 읽기만)·TLS(사설 CA 흉내 — Vault dev-tls 의 CA 를 PEM 으로, {@link
 * VaultTrustStorePostProcessor}).
 *
 * <ul>
 *   <li>응답에 Vault 출처가 Git 보다 앞(우선)에 오고, 같은 속성은 Vault 값이 이긴다. Git 의 다른 값은 그대로
 *   <li>Vault 에 경로가 없는 앱은 Git 만
 *   <li>서비스(config client)가 받으면: 비밀은 Vault 값, 목록은 Vault 의 것으로 통째로
 *   <li>Vault 로그인이 안 되면 응답이 실패하고, fail-fast 서비스는 기동하지 않는다(비밀 없이 뜨지 않음)
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"git", "vault"})
class ConfigServerVaultIT {

  static final String PASSWORD = "it-vault-config-password";
  static final String CA_IN_CONTAINER = "/tmp/vault-ca.pem";
  static final Path ROOT;
  static final GenericContainer<?> VAULT;
  static final String ROLE_ID;
  static final String SECRET_ID;

  static {
    try {
      ROOT = Files.createTempDirectory("config-server-vault-it");
      Path repo = Files.createDirectories(ROOT.resolve("repo"));
      Files.writeString(repo.resolve("application.yml"), "spring.jpa.open-in-view: false\n");
      Files.writeString(repo.resolve("payment-service.yml"), "server.port: 8205\n");
      Files.writeString(
          repo.resolve("payment-service-lan.yml"),
          """
          spring.datasource.url: jdbc:postgresql://postgres-pc2:15432/payment_service
          spring.datasource.password: from-git
          pmart.auth.signing.keys:
            - kid: k-git
              private-key: pk-git
            - kid: k-git-2
              private-key: pk-git-2
          """);
      Files.writeString(
          repo.resolve("cart-service-lan.yml"), "spring.datasource.password: cart-git\n");
      try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
        git.add().addFilepattern(".").call();
        git.commit().setMessage("init").setAuthor("it", "it@example.invalid").setSign(false).call();
      }

      VAULT =
          new GenericContainer<>("hashicorp/vault:1.21.4")
              .withCommand(
                  "server",
                  "-dev",
                  "-dev-tls",
                  "-dev-tls-cert-dir=/tmp",
                  "-dev-tls-san=localhost",
                  "-dev-root-token-id=root",
                  "-dev-listen-address=0.0.0.0:8200")
              .withExposedPorts(8200)
              .waitingFor(Wait.forLogMessage(".*Vault server started.*", 1));
      VAULT.start();
      Path ca = ROOT.resolve("vault-ca.pem");
      VAULT.copyFileFromContainer(CA_IN_CONTAINER, ca.toString());
      // VaultTrustStorePostProcessor 는 환경 준비 단계에 돈다 — @DynamicPropertySource 보다 이르므로 시스템 속성으로
      System.setProperty("PMART_VAULT_CA_PEM", ca.toString());

      sh("vault secrets enable -path=config -version=2 kv");
      sh(
          "vault kv put 'config/payment-service,lan' spring.datasource.password=from-vault"
              + " 'pmart.auth.signing.keys[0].kid=k-vault'"
              + " 'pmart.auth.signing.keys[0].private-key=pk-vault'");
      // LAN 과 같은 정책: config/data/* 읽기만
      sh(
          "printf 'path \"config/data/*\" { capabilities = [\"read\"] }\\n'"
              + " | vault policy write config-server-read -");
      sh("vault auth enable approle");
      sh(
          "vault write auth/approle/role/config-server token_policies=config-server-read"
              + " token_ttl=1h token_max_ttl=24h");
      ROLE_ID = sh("vault read -field=role_id auth/approle/role/config-server/role-id").strip();
      SECRET_ID =
          sh("vault write -f -field=secret_id auth/approle/role/config-server/secret-id").strip();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String sh(String cmd) throws Exception {
    var r =
        VAULT.execInContainer(
            "sh",
            "-c",
            "export VAULT_ADDR=https://127.0.0.1:8200 VAULT_CACERT="
                + CA_IN_CONTAINER
                + " VAULT_TOKEN=root; "
                + cmd);
    assertThat(r.getExitCode()).as(cmd + " → " + r.getStderr()).isZero();
    return r.getStdout();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("PMART_CONFIG_GIT_URI", () -> ROOT.resolve("repo").toUri().toString());
    r.add("spring.cloud.config.server.git.basedir", () -> ROOT.resolve("clone").toString());
    r.add("spring.cloud.config.server.git.ignore-local-ssh-settings", () -> "false");
    r.add("spring.security.user.password", () -> PASSWORD);
    r.add("PMART_VAULT_HOST", () -> "localhost");
    r.add("PMART_VAULT_PORT", () -> VAULT.getMappedPort(8200));
    // LAN 은 configtree 파일(spring.cloud.config.server.vault.app-role.*)
    r.add("spring.cloud.config.server.vault.app-role.role-id", () -> ROLE_ID);
    r.add("spring.cloud.config.server.vault.app-role.secret-id", () -> SECRET_ID);
  }

  @AfterAll
  static void cleanup() throws IOException {
    System.clearProperty("PMART_VAULT_CA_PEM");
    VAULT.stop();
    try (var paths = Files.walk(ROOT)) {
      paths.forEach(p -> p.toFile().setWritable(true));
    }
    FileSystemUtils.deleteRecursively(ROOT);
  }

  @LocalServerPort int port;
  final ObjectMapper mapper = new ObjectMapper();

  @Test
  void Vault_출처가_Git_보다_앞이고_같은_속성은_Vault_가_이긴다() {
    JsonNode sources = env(port, "/payment-service/lan").path("propertySources");
    List<String> names = new ArrayList<>();
    sources.forEach(s -> names.add(s.path("name").asString()));
    assertThat(names.get(0)).startsWith("vault:").contains("payment-service,lan");
    assertThat(names.subList(1, names.size())).allMatch(n -> !n.startsWith("vault:"));
    Map<String, String> merged = merge(sources);
    assertThat(merged)
        .containsEntry("spring.datasource.password", "from-vault")
        .containsEntry(
            "spring.datasource.url", "jdbc:postgresql://postgres-pc2:15432/payment_service")
        .containsEntry("server.port", "8205")
        .containsEntry("spring.jpa.open-in-view", "false");
  }

  @Test
  void Vault_에_경로가_없는_앱은_Git_만() {
    JsonNode sources = env(port, "/cart-service/lan").path("propertySources");
    sources.forEach(s -> assertThat(s.path("name").asString()).doesNotStartWith("vault:"));
    assertThat(merge(sources)).containsEntry("spring.datasource.password", "cart-git");
  }

  record Key(String kid, String privateKey) {}

  @Test
  void 서비스는_비밀을_Vault_값으로_목록은_Vault_것으로_통째로_받는다() {
    try (ConfigurableApplicationContext ctx = client(port)) {
      assertThat(ctx.getEnvironment().getProperty("spring.datasource.password"))
          .isEqualTo("from-vault");
      assertThat(ctx.getEnvironment().getProperty("server.port")).isEqualTo("8205");
      List<Key> keys =
          Binder.get(ctx.getEnvironment())
              .bind("pmart.auth.signing.keys", Bindable.listOf(Key.class))
              .get();
      assertThat(keys).containsExactly(new Key("k-vault", "pk-vault"));
    }
  }

  @Test
  void Vault_로그인이_안_되면_응답이_실패하고_서비스는_기동하지_않는다() {
    try (ConfigurableApplicationContext bad =
        new SpringApplicationBuilder(ConfigServerApplication.class)
            .profiles("git", "vault")
            .run(
                "--server.port=0",
                "--spring.cloud.config.server.git.uri=" + ROOT.resolve("repo").toUri(),
                "--spring.cloud.config.server.git.basedir=" + ROOT.resolve("clone-bad"),
                "--spring.cloud.config.server.git.ignore-local-ssh-settings=false",
                "--spring.security.user.password=" + PASSWORD,
                "--PMART_VAULT_HOST=localhost",
                "--PMART_VAULT_PORT=" + VAULT.getMappedPort(8200),
                "--spring.cloud.config.server.vault.app-role.role-id=" + ROLE_ID,
                "--spring.cloud.config.server.vault.app-role.secret-id=wrong-secret-id")) {
      int badPort = Integer.parseInt(bad.getEnvironment().getRequiredProperty("local.server.port"));
      int status =
          http(badPort)
              .get()
              .uri("/payment-service/lan")
              .headers(h -> h.setBasicAuth("pmart", PASSWORD))
              .exchange((req, resp) -> resp.getStatusCode().value());
      assertThat(status).isGreaterThanOrEqualTo(500);
      assertThatThrownBy(() -> client(badPort).close()).isInstanceOf(Exception.class);
    }
  }

  static Map<String, String> merge(JsonNode sources) {
    Map<String, String> merged = new HashMap<>();
    for (int i = sources.size() - 1; i >= 0; i--) {
      sources
          .get(i)
          .path("source")
          .properties()
          .forEach(e -> merged.put(e.getKey(), e.getValue().asString()));
    }
    return merged;
  }

  JsonNode env(int p, String path) {
    String body =
        http(p)
            .get()
            .uri(path)
            .headers(h -> h.setBasicAuth("pmart", PASSWORD))
            .exchange(
                (req, resp) -> {
                  assertThat(resp.getStatusCode().value()).isEqualTo(200);
                  return new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                });
    return mapper.readTree(body);
  }

  static RestClient http(int p) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + p)
        .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {})
        .build();
  }

  /** 서비스와 같은 import 설정(LAN compose 처럼 enabled·uri·fail-fast·인증). */
  static ConfigurableApplicationContext client(int p) {
    return new SpringApplicationBuilder(ClientApp.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.config.name=client-vault-it",
            "--spring.application.name=payment-service",
            "--spring.config.import=optional:configserver:",
            "--spring.cloud.config.enabled=true",
            "--spring.cloud.config.uri=http://localhost:" + p,
            "--spring.cloud.config.fail-fast=true",
            "--spring.cloud.config.username=pmart",
            "--spring.cloud.config.password=" + PASSWORD,
            "--spring.profiles.active=lan");
  }

  static class ClientApp {}
}
