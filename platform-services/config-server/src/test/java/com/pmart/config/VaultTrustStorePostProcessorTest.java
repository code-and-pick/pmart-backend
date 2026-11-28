package com.pmart.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class VaultTrustStorePostProcessorTest {

  final VaultTrustStorePostProcessor pp = new VaultTrustStorePostProcessor();

  static StandardEnvironment env(Map<String, Object> m) {
    StandardEnvironment e = new StandardEnvironment();
    e.getPropertySources().addFirst(new MapPropertySource("t", m));
    return e;
  }

  @Test
  void 값이_없으면_아무것도_하지_않는다() {
    StandardEnvironment e = env(Map.of());
    pp.postProcessEnvironment(e, null);
    assertThat(e.getProperty(VaultTrustStorePostProcessor.TRUST_STORE)).isNull();
  }

  @Test
  void 파일이_없으면_기동하지_않는다(@TempDir Path tmp) {
    StandardEnvironment e =
        env(Map.of(VaultTrustStorePostProcessor.CA_PEM, tmp.resolve("none.pem").toString()));
    assertThatThrownBy(() -> pp.postProcessEnvironment(e, null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 인증서가_없는_파일이면_기동하지_않는다(@TempDir Path tmp) throws Exception {
    Path f = Files.writeString(tmp.resolve("empty.pem"), "");
    StandardEnvironment e = env(Map.of(VaultTrustStorePostProcessor.CA_PEM, f.toString()));
    assertThatThrownBy(() -> pp.postProcessEnvironment(e, null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void PEM_을_PKCS12_신뢰_저장소로_바꿔_넣는다() throws Exception {
    Path f = Path.of(getClass().getResource("/test-ca.pem").toURI());
    StandardEnvironment e = env(Map.of(VaultTrustStorePostProcessor.CA_PEM, f.toString()));
    pp.postProcessEnvironment(e, null);
    String store = e.getProperty(VaultTrustStorePostProcessor.TRUST_STORE);
    String pw = e.getProperty(VaultTrustStorePostProcessor.TRUST_STORE_PASSWORD);
    assertThat(store).startsWith("file:");
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(Path.of(URI.create(store)))) {
      ks.load(in, pw.toCharArray());
    }
    assertThat(ks.size()).isEqualTo(1);
    assertThat(ks.isCertificateEntry("pmart-ca-0")).isTrue();
  }
}
