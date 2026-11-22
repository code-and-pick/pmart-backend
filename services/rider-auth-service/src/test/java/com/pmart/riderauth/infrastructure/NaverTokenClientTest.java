package com.pmart.riderauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.riderauth.application.NaverExchangeProperties;
import com.pmart.riderauth.infrastructure.NaverTokenClient.Response;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 네이버 토큰 응답 해석(ADR-0078): id_token 만, 200 + error 도 거절, 5xx·해석 불가는 장애. */
class NaverTokenClientTest {

  final NaverTokenClient client =
      new NaverTokenClient(
          new NaverExchangeProperties(null, null, null, null, null, null, null),
          JsonMapper.builder().build());

  @Test
  void returnsIdTokenAndAccessTokenNotRefresh() {
    NaverTokenClient.Tokens t =
        client.interpret(
            new Response(
                200,
                "{\"access_token\":\"AT-SECRET\",\"refresh_token\":\"r\",\"id_token\":\"ID-SECRET\"}"));
    assertThat(t.idToken()).isEqualTo("ID-SECRET");
    assertThat(t.accessToken()).isEqualTo("AT-SECRET");
    assertThat(t.toString()).doesNotContain("AT-SECRET", "ID-SECRET");
  }

  @Test
  void errorInOkBodyIsRejectedWithCodeOnly() {
    assertThatThrownBy(
            () ->
                client.interpret(
                    new Response(200, "{\"error\":\"invalid_grant\",\"error_description\":\"x\"}")))
        .isInstanceOf(NaverTokenClient.Rejected.class)
        .satisfies(
            e ->
                assertThat(((NaverTokenClient.Rejected) e).errorCode()).isEqualTo("invalid_grant"));
  }

  @Test
  void clientErrorWithoutJsonIsRejected() {
    assertThatThrownBy(() -> client.interpret(new Response(401, "nope")))
        .isInstanceOf(NaverTokenClient.Rejected.class)
        .satisfies(
            e -> assertThat(((NaverTokenClient.Rejected) e).errorCode()).isEqualTo("http_401"));
  }

  @Test
  void oddErrorCodeIsNotEchoed() {
    assertThatThrownBy(
            () ->
                client.interpret(
                    new Response(400, "{\"error\":\"bad code with spaces <script>\"}")))
        .satisfies(
            e ->
                assertThat(((NaverTokenClient.Rejected) e).errorCode()).isEqualTo("unknown_error"));
  }

  @Test
  void missingIdTokenIsRejected() {
    assertThatThrownBy(() -> client.interpret(new Response(200, "{\"access_token\":\"a\"}")))
        .isInstanceOf(NaverTokenClient.Rejected.class);
  }

  @Test
  void serverErrorOrUnparsableIsUnavailable() {
    assertThatThrownBy(() -> client.interpret(new Response(503, "{\"error\":\"x\"}")))
        .isInstanceOf(NaverTokenClient.Unavailable.class);
    assertThatThrownBy(() -> client.interpret(new Response(200, "<html>")))
        .isInstanceOf(NaverTokenClient.Unavailable.class);
  }

  @Test
  void exchangeToStringHidesSecretAndCode() {
    String s =
        new NaverTokenClient.Exchange("cid", "SECRET", "CODE", "VERIFIER", "http://cb", "st")
            .toString();
    assertThat(s).doesNotContain("SECRET", "CODE", "VERIFIER");
  }
}
