package com.pmart.cases.infrastructure;

import com.pmart.cases.application.CaseProperties;
import com.pmart.common.web.Downstream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * rider-service {@code GET /riders/me}(라이더 본인 토큰을 그대로) — 0.40.0 라이더 문의 센터 = 접수 때 운행
 * 센터(operating.mfcId). 못 읽으면 빈 값(문의는 막지 않는다 — 센터 없음으로 저장). 응답에서 운행 센터만 읽는다(계좌·주소 등은 읽지 않는다).
 */
@Component
public class RiderClient {

  private static final Logger log = LoggerFactory.getLogger(RiderClient.class);

  /**
   * 0.40.1(PC 1 #423 검토): 운행 센터는 거르기용 곁값이라 rider-service 가 느려도 문의 접수를 붙잡지 않게 서비스 기본(연결 1초·응답 5초)보다
   * 짧게 — 연결 0.5초·응답 2초(권한 확인 호출 기본값과 같음), 넘으면 센터 없이 저장.
   */
  static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

  static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

  private final RestClient client;

  public RiderClient(RestClient.Builder builder, CaseProperties props) {
    String url = props.riderServiceBaseUrl();
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    factory.setReadTimeout(READ_TIMEOUT);
    this.client =
        builder
            .clone()
            .baseUrl(url == null || url.isBlank() ? "http://rider-service:8403" : url)
            .requestFactory(factory)
            .build();
  }

  @SuppressWarnings("unchecked")
  public Optional<String> operatingCenter() {
    String auth = currentAuthorization();
    if (auth == null) {
      return Optional.empty();
    }
    try {
      Map<String, Object> body =
          Downstream.call(
              "rider-service riders/me",
              () ->
                  client
                      .get()
                      .uri("/api/v1/riders/me")
                      .header(HttpHeaders.AUTHORIZATION, auth)
                      .retrieve()
                      .body(Map.class));
      Object op = body == null ? null : body.get("operating");
      Object mfc = op instanceof Map<?, ?> m ? m.get("mfcId") : null;
      return mfc == null || mfc.toString().isBlank()
          ? Optional.empty()
          : Optional.of(mfc.toString());
    } catch (RuntimeException e) {
      log.warn("라이더 운행 센터를 읽지 못함 — 센터 없이 저장({})", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static @Nullable String currentAuthorization() {
    var attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes s) {
      return s.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
