package com.pmart.riderauth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.riderauth.infrastructure.KakaoUnlinkClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 카카오 앱 연결 끊기(RA-1 ①): Admin 키·회원번호로 부르고, 키가 없으면 안 부르고, 실패는 false. */
class KakaoUnlinkClientIT {

  WireMockServer kakao;

  @BeforeEach
  void start() {
    kakao = new WireMockServer(options().dynamicPort());
    kakao.start();
  }

  @AfterEach
  void stop() {
    kakao.stop();
  }

  @Test
  void unlinksWithAdminKeyAndUserId() {
    kakao.stubFor(
        post(urlPathEqualTo("/v1/user/unlink"))
            .willReturn(aResponse().withStatus(200).withBody("{\"id\":4001}")));
    KakaoUnlinkClient client = new KakaoUnlinkClient("rider-admin-key", kakao.baseUrl() + "/");
    assertThat(client.configured()).isTrue();
    assertThat(client.unlink("4001")).isTrue();
    kakao.verify(
        postRequestedFor(urlPathEqualTo("/v1/user/unlink"))
            .withHeader("Authorization", equalTo("KakaoAK rider-admin-key"))
            .withFormParam("target_id_type", equalTo("user_id"))
            .withFormParam("target_id", equalTo("4001")));
  }

  @Test
  void noAdminKeyMeansNoCall() {
    KakaoUnlinkClient client = new KakaoUnlinkClient(" ", kakao.baseUrl());
    assertThat(client.configured()).isFalse();
    assertThat(client.unlink("4001")).isFalse();
    assertThat(kakao.getAllServeEvents()).isEmpty();
  }

  @Test
  void kakaoErrorIsFalseNotException() {
    kakao.stubFor(post(urlPathEqualTo("/v1/user/unlink")).willReturn(aResponse().withStatus(401)));
    assertThat(new KakaoUnlinkClient("k", kakao.baseUrl()).unlink("4001")).isFalse();
  }
}
