package com.pmart.notification.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pmart.common.test.TestJwt;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.notification.infrastructure.PushTokenStore;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** 설정 max-devices 가 없으면 등록은 CONFIG_REQUIRED(기본값을 지어내지 않는다) — 저장소를 건드리지 않는다. */
class PushTokenServiceTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private void loginAsCustomer() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(TestJwt.decoder().decode(TestJwt.customer("ACC-unit"))));
  }

  @Test
  void registerWithoutMaxDevicesIsConfigRequired() {
    loginAsCustomer();
    PushTokenStore store = mock(PushTokenStore.class);
    var service =
        new PushTokenService(
            store, new NotificationProperties(null, null, null), Clock.systemUTC());
    assertThatThrownBy(() -> service.register("dev-1", "fcm-x", "ANDROID", null))
        .isInstanceOfSatisfying(
            PmartException.class,
            e ->
                org.assertj.core.api.Assertions.assertThat(e.code())
                    .isEqualTo(ErrorCode.CONFIG_REQUIRED.name()));
    verifyNoInteractions(store);
  }

  @Test
  void zeroMaxDevicesIsConfigRequired() {
    loginAsCustomer();
    PushTokenStore store = mock(PushTokenStore.class);
    var service =
        new PushTokenService(
            store,
            new NotificationProperties(null, new NotificationProperties.PushToken(0), null),
            Clock.systemUTC());
    assertThatThrownBy(() -> service.register("dev-1", "fcm-x", "ANDROID", null))
        .isInstanceOf(PmartException.class)
        .hasMessageContaining("push-token.max-devices");
    verifyNoInteractions(store);
  }
}
