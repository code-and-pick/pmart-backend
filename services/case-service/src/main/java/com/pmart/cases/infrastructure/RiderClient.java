package com.pmart.cases.infrastructure;

import com.pmart.cases.application.CaseProperties;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
  private final RestClient client;

  public RiderClient(RestClient.Builder builder, CaseProperties props) {
    String url = props.riderServiceBaseUrl();
    this.client =
        builder
            .clone()
            .baseUrl(url == null || url.isBlank() ? "http://rider-service:8403" : url)
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
          client
              .get()
              .uri("/api/v1/riders/me")
              .header(HttpHeaders.AUTHORIZATION, auth)
              .retrieve()
              .body(Map.class);
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
