package com.pmart.riderauth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.PmartException;
import com.pmart.riderauth.infrastructure.NaverTokenClient;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 네이버 코드 교환 규칙(ADR-0078): 설정 없음 422, redirectUri 불일치 400, 네이버 거절 401·장애 503. */
class NaverCodeExchangeServiceTest {

  static final String CALLBACK = "http://192.168.0.40:8080/api/v1/auth/oauth-callbacks/naver/rider";
  static final String VERIFIER = "v".repeat(43);

  final NaverTokenClient naver = mock(NaverTokenClient.class);
  final RequestIdempotency idempotency = mock(RequestIdempotency.class);

  NaverCodeExchangeService service(String secret, String callback) {
    return new NaverCodeExchangeService(
        new NaverExchangeProperties(
            List.of(" cid ", "other"), secret, callback, null, null, null, null),
        naver,
        idempotency);
  }

  @SuppressWarnings("unchecked")
  void runIdempotentActionDirectly() {
    when(idempotency.execute(any(), any(), any(), any()))
        .thenAnswer(
            inv ->
                new RequestIdempotency.Outcome<>(
                    ((Supplier<Object>) inv.getArgument(3)).get(), false));
  }

  @Test
  void missingSecretIsConfigRequiredWithoutCallingNaver() {
    assertThatThrownBy(
            () -> service(" ", CALLBACK).exchange("k", "rider-app", "c", VERIFIER, CALLBACK, null))
        .isInstanceOfSatisfying(
            PmartException.class, e -> assertThat(e.code()).isEqualTo("CONFIG_REQUIRED"))
        .hasMessageContaining("client-secret");
    verifyNoInteractions(naver, idempotency);
  }

  @Test
  void missingClientIdOrCallbackIsConfigRequired() {
    NaverCodeExchangeService noId =
        new NaverCodeExchangeService(
            new NaverExchangeProperties(null, "s", CALLBACK, null, null, null, null),
            naver,
            idempotency);
    assertThatThrownBy(() -> noId.exchange("k", "rider-app", "c", VERIFIER, CALLBACK, null))
        .hasMessageContaining("client-ids");
    assertThatThrownBy(
            () -> service("s", null).exchange("k", "rider-app", "c", VERIFIER, CALLBACK, null))
        .hasMessageContaining("callback-url");
    verifyNoInteractions(naver, idempotency);
  }

  @Test
  void otherRedirectUriIsValidationFailed() {
    assertThatThrownBy(
            () ->
                service("s", CALLBACK)
                    .exchange("k", "rider-app", "c", VERIFIER, CALLBACK + "/x", null))
        .isInstanceOfSatisfying(
            PmartException.class, e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    verifyNoInteractions(naver, idempotency);
  }

  @Test
  void usesFirstClientIdAndReturnsIdToken() {
    runIdempotentActionDirectly();
    when(naver.exchange(any()))
        .thenAnswer(
            inv -> {
              NaverTokenClient.Exchange e = inv.getArgument(0);
              assertThat(e.clientId()).isEqualTo("cid");
              assertThat(e.clientSecret()).isEqualTo("s");
              assertThat(e.state()).isEqualTo("st");
              return new NaverTokenClient.Tokens("id.token", null);
            });
    assertThat(service("s", CALLBACK).exchange("k", "rider-app", "c", VERIFIER, CALLBACK, "st"))
        .isEqualTo("id.token");
  }

  @Test
  void withdrawalUnlinksWithAccessTokenAfterProfile() {
    runIdempotentActionDirectly();
    when(naver.exchange(any())).thenReturn(new NaverTokenClient.Tokens("id.token", "at"));
    when(naver.profile("at")).thenReturn(NaverTokenClient.Profile.NONE);
    service("s", CALLBACK).exchange("k", "rider-app", "c", VERIFIER, CALLBACK, null, true);
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(naver);
    order.verify(naver).profile("at");
    order.verify(naver).unlink("cid", "s", "at");
  }

  @Test
  void noAccessTokenMeansNoProfileNoUnlink() {
    runIdempotentActionDirectly();
    when(naver.exchange(any())).thenReturn(new NaverTokenClient.Tokens("id.token", null));
    service("s", CALLBACK).exchange("k", "rider-app", "c", VERIFIER, CALLBACK, null, true);
    org.mockito.Mockito.verify(naver, org.mockito.Mockito.never()).profile(any());
    org.mockito.Mockito.verify(naver, org.mockito.Mockito.never()).unlink(any(), any(), any());
  }

  @Test
  void loginWithoutStoreNeverUnlinks() {
    runIdempotentActionDirectly();
    when(naver.exchange(any())).thenReturn(new NaverTokenClient.Tokens("id.token", "at"));
    when(naver.profile("at")).thenReturn(NaverTokenClient.Profile.NONE);
    service("s", CALLBACK).exchange("k", "rider-app", "c", VERIFIER, CALLBACK, null);
    org.mockito.Mockito.verify(naver, org.mockito.Mockito.never()).unlink(any(), any(), any());
  }

  @Test
  void subjectHashIsStableHexNotTheSubject() {
    String h = NaverCodeExchangeService.subjectHash("naver-sub-1");
    assertThat(h).hasSize(64).doesNotContain("naver-sub-1");
    assertThat(NaverCodeExchangeService.subjectHash("naver-sub-1")).isEqualTo(h);
  }

  @Test
  void naverRejectionAndOutageMapToContractCodes() {
    runIdempotentActionDirectly();
    when(naver.exchange(any())).thenThrow(new NaverTokenClient.Rejected("invalid_grant"));
    assertThatThrownBy(
            () -> service("s", CALLBACK).exchange("k", "rider-app", "c", VERIFIER, CALLBACK, null))
        .isInstanceOfSatisfying(
            PmartException.class,
            e -> {
              assertThat(e.code()).isEqualTo("PROVIDER_REJECTED");
              assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            });

    org.mockito.Mockito.doThrow(new NaverTokenClient.Unavailable("timeout", null))
        .when(naver)
        .exchange(any());
    assertThatThrownBy(
            () -> service("s", CALLBACK).exchange("k", "rider-app", "c", VERIFIER, CALLBACK, null))
        .isInstanceOfSatisfying(
            PmartException.class,
            e -> {
              assertThat(e.code()).isEqualTo("PROVIDER_UNAVAILABLE");
              assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(e.retryable()).isTrue();
            });
  }

  @Test
  void propertiesToStringHidesSecret() {
    assertThat(
            new NaverExchangeProperties(
                List.of("cid"), "TOPSECRET", CALLBACK, null, null, null, null))
        .asString()
        .doesNotContain("TOPSECRET");
  }
}
