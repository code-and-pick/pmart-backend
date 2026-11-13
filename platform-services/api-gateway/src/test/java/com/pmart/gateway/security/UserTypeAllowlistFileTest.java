package com.pmart.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 허용 목록 파일 = 계약(ADR-0054 §6.1). 계약의 외부 작업마다 {@code x-pmart-user-types}(없으면 UNLABELED, {@code
 * security: []} 면 PUBLIC)를 모아 만든 결과가 {@code src/main/resources/access/user-type-allowlist.txt} 와
 * 같아야 한다.
 *
 * <p>계약을 고친 뒤 다시 만들기: {@code PMART_WRITE_ALLOWLIST=1 ./gradlew :platform-services:api-gateway:test
 * --tests '*UserTypeAllowlistFileTest'}.
 */
class UserTypeAllowlistFileTest {

  static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
  static final Path FILE = Path.of("src/main/resources/" + UserTypeAllowlist.RESOURCE);
  static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete");

  @Test
  void allowlistMatchesContracts() throws IOException {
    List<String> expected = generate();
    if ("1".equals(System.getenv("PMART_WRITE_ALLOWLIST"))) {
      Files.createDirectories(FILE.getParent());
      Files.write(FILE, expected, StandardCharsets.UTF_8);
    }
    assertThat(Files.readAllLines(FILE, StandardCharsets.UTF_8))
        .as("계약과 다르면 PMART_WRITE_ALLOWLIST=1 로 다시 만든다")
        .isEqualTo(expected);
    UserTypeAllowlist list = UserTypeAllowlist.parse(expected);
    assertThat(list.size()).isPositive();
  }

  @SuppressWarnings("unchecked")
  static List<String> generate() throws IOException {
    TreeSet<String> lines = new TreeSet<>();
    LoaderOptions opts = new LoaderOptions();
    opts.setCodePointLimit(20 * 1024 * 1024);
    try (Stream<Path> files = Files.list(REPO.resolve("contracts/openapi"))) {
      for (Path f : files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
        String name = f.getFileName().toString();
        if (name.startsWith("_")
            || name.equals("redocly.yaml")
            || name.equals("api-gateway.yaml")
            // AI 배차 계산 서버 — 게이트웨이에 열지 않는 내부 계산 서버(ADR-0058). /health 가 /api/v1/health 로 들어가지 않게(PC
            // 1 재검토 ④)
            || name.equals("delivery-dispatch-optimizer.yaml")) {
          continue;
        }
        Map<String, Object> doc;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
          doc = new Yaml(opts).load(r);
        }
        String base = baseUrl(doc);
        Object rootSecurity = doc.get("security");
        Map<String, Object> paths = (Map<String, Object>) doc.getOrDefault("paths", Map.of());
        for (var pe : paths.entrySet()) {
          String path = pe.getKey();
          if (path.startsWith("/internal")) {
            continue;
          }
          Map<String, Object> item = (Map<String, Object>) pe.getValue();
          for (String m : METHODS) {
            Object o = item.get(m);
            if (!(o instanceof Map<?, ?> op)) {
              continue;
            }
            Object security = op.containsKey("security") ? op.get("security") : rootSecurity;
            String types;
            if (security instanceof List<?> l && l.isEmpty()) {
              types = UserTypeAllowlist.PUBLIC;
            } else if (op.get("x-pmart-user-types") instanceof List<?> t && !t.isEmpty()) {
              types = String.join(",", new TreeSet<>(t.stream().map(String::valueOf).toList()));
            } else {
              types = UserTypeAllowlist.UNLABELED;
            }
            lines.add(m.toUpperCase() + " " + join(base, path) + " " + types);
          }
        }
      }
    }
    List<String> out = new ArrayList<>();
    out.add("# 생성 파일 — 손으로 고치지 않는다. 정본은 contracts/openapi 의 x-pmart-user-types(ADR-0054 §6.1).");
    out.add(
        "# 다시 만들기: PMART_WRITE_ALLOWLIST=1 ./gradlew :platform-services:api-gateway:test --tests '*UserTypeAllowlistFileTest'");
    out.addAll(lines);
    return out;
  }

  @SuppressWarnings("unchecked")
  static String baseUrl(Map<String, Object> doc) {
    Object servers = doc.get("servers");
    if (servers instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> s) {
      Object url = ((Map<String, Object>) s).get("url");
      if (url != null) {
        return url.toString();
      }
    }
    return "";
  }

  static String join(String base, String path) {
    String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    return b + path;
  }
}
