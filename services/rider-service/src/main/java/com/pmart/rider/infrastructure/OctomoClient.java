package com.pmart.rider.infrastructure;

import com.pmart.common.web.PmartException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * OCTOMO 문자 조회(라이더 — 고객 customer-service OctomoClient 와 같은 코드, 2026-11-19)({@code POST
 * /octomo/v1/public/message/exists}, 2026-11-19). 공용 RestClient.Builder 를 쓰지 않는다 — 사용자 토큰이 밖으로 나가지
 * 않게. 번호·본문·키는 로그에 남기지 않는다. 실패(연결·시간 초과·4xx·5xx)는 모두 503 DEPENDENCY_UNAVAILABLE(고객 시도로 세지 않음), 키·앱
 * 상태 문제(401·403·404)는 ERROR 로그로 알린다.
 */
@Component
public class OctomoClient {

  private static final Logger log = LoggerFactory.getLogger(OctomoClient.class);

  private final RestClient http;

  public OctomoClient() {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(
                    HttpClient.Version
                        .HTTP_1_1) // 평문 http 에서 h2c 업그레이드 시도 없이(시험 WireMock) — OCTOMO 는 https
                .connectTimeout(Duration.ofSeconds(2))
                .build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.http = RestClient.builder().requestFactory(factory).build();
  }

  /** 그 번호에서 그 본문이 최근 withinMinutes 안에 왔는가. */
  public boolean exists(
      String baseUrl, String apiKey, String mobileNum, String text, int withinMinutes) {
    try {
      JsonNode r =
          http.post()
              .uri(baseUrl.replaceAll("/+$", "") + "/octomo/v1/public/message/exists")
              .header("Authorization", "Octomo " + apiKey)
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .body(Map.of("mobileNum", mobileNum, "text", text, "withinMinutes", withinMinutes))
              .retrieve()
              .body(JsonNode.class);
      return r != null && r.path("exists").asBoolean(false);
    } catch (HttpStatusCodeException e) {
      int status = e.getStatusCode().value();
      if (status == 401 || status == 403 || status == 404) {
        log.error("OCTOMO {} — API 키·애플리케이션 상태를 확인하세요(마이페이지)", status);
      } else {
        log.warn("OCTOMO 조회 실패 {}", status);
      }
      throw PmartException.dependencyUnavailable(
          status == 429 ? "문자 확인이 잠시 몰렸습니다. 잠시 뒤 다시 시도하세요." : "문자 확인을 지금 할 수 없습니다.", e);
    } catch (RestClientException e) {
      log.warn(
          "OCTOMO 연결 실패: {} ({})",
          e.getClass().getSimpleName(),
          e.getMostSpecificCause().getClass().getSimpleName());
      throw PmartException.dependencyUnavailable("문자 확인을 지금 할 수 없습니다.", e);
    }
  }
}
