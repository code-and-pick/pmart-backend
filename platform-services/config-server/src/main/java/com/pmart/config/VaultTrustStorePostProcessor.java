package com.pmart.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * Vault 백엔드(ADR-0101)가 LAN 사설 CA(ADR-0057, PEM)를 믿게 한다.
 *
 * <p>config-server 의 Vault 백엔드는 {@code spring.cloud.config.server.vault.ssl.trust-store} 를 기본
 * 형식(PKCS12)으로만 읽고 형식을 고를 설정이 없다(Spring Vault 의 PEM 형식을 넘기지 않는다). 그래서 {@code
 * pmart.config.vault.ca-pem}(PEM 파일)이 있으면 기동 때 그 인증서만 든 PKCS12 신뢰 저장소를 임시 파일로 만들어 그 설정에 넣는다. 공개
 * 인증서뿐이라 저장소 비밀번호는 기동마다 새로 만든다.
 *
 * <p>값이 없으면(로컬·Vault 를 켜지 않은 LAN) 아무것도 하지 않는다. 파일이 없거나 인증서가 없으면 기동하지 않는다 — Vault 를 켰는데 TLS 신뢰가 빠진 채
 * 뜨면 요청마다 실패하기 때문이다.
 */
public class VaultTrustStorePostProcessor implements EnvironmentPostProcessor, Ordered {

  public static final String CA_PEM = "pmart.config.vault.ca-pem";
  static final String TRUST_STORE = "spring.cloud.config.server.vault.ssl.trust-store";
  static final String TRUST_STORE_PASSWORD =
      "spring.cloud.config.server.vault.ssl.trust-store-password";
  static final String SOURCE_NAME = "pmart-vault-trust-store";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    String pem = environment.getProperty(CA_PEM);
    if (!StringUtils.hasText(pem)
        || environment.getPropertySources().contains(SOURCE_NAME)
        || StringUtils.hasText(environment.getProperty(TRUST_STORE))) {
      return;
    }
    Path ca = Path.of(pem);
    try {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(null, null);
      int n = 0;
      try (InputStream in = Files.newInputStream(ca)) {
        for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
          store.setCertificateEntry("pmart-ca-" + n++, c);
        }
      }
      if (n == 0) {
        throw new IllegalStateException("Vault CA 파일에 인증서가 없다: " + ca);
      }
      Path out = Files.createTempFile("pmart-vault-ca", ".p12");
      out.toFile().deleteOnExit();
      String password = UUID.randomUUID().toString();
      try (OutputStream o = Files.newOutputStream(out)) {
        store.store(o, password.toCharArray());
      }
      environment
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  SOURCE_NAME,
                  Map.of(TRUST_STORE, out.toUri().toString(), TRUST_STORE_PASSWORD, password)));
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException("Vault CA(PEM)를 읽지 못했다: " + ca, e);
    }
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE; // 설정 파일·configtree 를 읽은 뒤
  }
}
