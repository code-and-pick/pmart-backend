package com.pmart.common.observability;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Vault 클라이언트(Spring Cloud Vault — 서비스가 자기 비밀을 Vault {@code config/<앱>,lan} 에서 직접 읽는다, {cipher} 대체)
 * 기본값을 <b>가장 낮은 우선순위</b>로 넣는다. 모든 앱에 starter 가 들어 있지만(convention) 기본은 꺼짐이다.
 *
 * <ul>
 *   <li>{@code spring.cloud.vault.enabled=false}: 로컬·시험에서 Vault 빈(세션·토큰 갱신)을 만들지 않는다. 켜지 않으면 인증 정보가
 *       없어 기동이 실패한다.
 * </ul>
 *
 * <p>Vault 에서 값을 가져오는 일({@code spring.config.import=vault://})은 이 후처리보다 <b>먼저</b>(설정 데이터 단계) 일어나므로
 * 여기서 켜고 끌 수 없다. 그래서 import 는 {@code application.yml} 에 두지 않고 LAN 컨테이너만 환경변수({@code
 * SPRING_CONFIG_IMPORT=optional:configserver:,vault://}, {@code SPRING_CLOUD_VAULT_ENABLED=true},
 * 주소·CA·AppRole)로 준다. 환경변수는 이 기본값보다 우선한다.
 */
public class VaultDefaultsPostProcessor implements EnvironmentPostProcessor, Ordered {

  public static final String SOURCE_NAME = "pmart-vault-defaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    if (environment.getPropertySources().contains(SOURCE_NAME)) {
      return; // 설정 갱신(ContextRefresher)이 환경을 다시 만들 때도 한 번만
    }
    environment
        .getPropertySources()
        .addLast(new MapPropertySource(SOURCE_NAME, Map.of("spring.cloud.vault.enabled", "false")));
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE - 10;
  }
}
