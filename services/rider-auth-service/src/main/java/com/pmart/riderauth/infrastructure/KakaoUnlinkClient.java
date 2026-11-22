package com.pmart.riderauth.infrastructure;

import java.net.http.HttpClient;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * 카카오 앱 연결 끊기(2026-11-22 RA-1 ① — 고객 auth-service {@code KakaoUnlinkClient} 와 같은 호출, 사용자 "동의는 처음
 * 가입할 때만"). 카카오는 앱과 연결된 동안 동의를 기억해 다시 묻지 않으므로, 탈퇴가 끝나면 끊어 다시 가입할 때 동의 화면이 뜨게 한다. {@code POST
 * {api-base-url}/v1/user/unlink}, <b>라이더 앱</b>의 Admin 키로 회원번호(ID 토큰 sub)를 지정한다.
 *
 * <p>Admin 키({@code pmart.rider-auth.kakao.admin-key}, LAN {@code {cipher}})가 없으면 부르지 않는다. 어떤 실패도
 * 예외 없이 false — 로그는 짧은 까닭 코드만(키·회원번호 금지). 공용 RestClient.Builder 를 쓰지 않는다(내부 헤더가 밖으로 새지 않게).
 */
@Component
public class KakaoUnlinkClient {

  private static final Logger log = LoggerFactory.getLogger(KakaoUnlinkClient.class);

  private final @Nullable String adminKey;
  private final String apiBaseUrl;
  private final RestClient http;

  public KakaoUnlinkClient(
      @Value("${pmart.rider-auth.kakao.admin-key:}") String adminKey,
      @Value("${pmart.rider-auth.kakao.api-base-url:https://kapi.kakao.com}") String apiBaseUrl) {
    this.adminKey = adminKey == null || adminKey.isBlank() ? null : adminKey.strip();
    this.apiBaseUrl = apiBaseUrl.strip().replaceAll("/+$", "");
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(Duration.ofSeconds(3));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  public boolean configured() {
    return adminKey != null;
  }

  /** 연결 끊기. 끊었으면 true. */
  public boolean unlink(String kakaoUserId) {
    if (adminKey == null) {
      log.info("카카오 연결 끊기 생략 reason=no_admin_key");
      return false;
    }
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("target_id_type", "user_id");
    form.add("target_id", kakaoUserId);
    try {
      http.post()
          .uri(apiBaseUrl + "/v1/user/unlink")
          .header("Authorization", "KakaoAK " + adminKey)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (HttpStatusCodeException e) {
      log.info("카카오 연결 끊기 실패 reason=http_{}", e.getStatusCode().value());
      return false;
    } catch (RuntimeException e) {
      log.info("카카오 연결 끊기 실패 reason={}", e.getClass().getSimpleName());
      return false;
    }
  }
}
