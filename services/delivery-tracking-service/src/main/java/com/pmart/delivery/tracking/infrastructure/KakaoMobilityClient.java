package com.pmart.delivery.tracking.infrastructure;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.tracking.application.RoutePlanner;
import com.pmart.delivery.tracking.application.TrackingProperties;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 카카오모빌리티 길찾기 — 다중 목적지({@code POST /v1/destinations/directions})·다중 출발지({@code POST
 * /v1/origins/directions}). 요약(summary)의 도로 거리(m)·소요시간(초)만 쓴다.
 *
 * <ul>
 *   <li>키 없음·401·403 → CONFIG_REQUIRED, 429 → QUOTA_EXCEEDED, 5xx·시간 초과·연결 오류 →
 *       DEPENDENCY_UNAVAILABLE. 직선거리로 대신하지 않는다(ADR-0058).
 *   <li>구간 결과 코드 ≠ 0(반경 밖·길 없음) → 그 칸만 NO_ROUTE.
 *   <li>키·좌표는 로그에 남기지 않는다(라이더·고객 위치).
 * </ul>
 */
@Component
public class KakaoMobilityClient implements RoutePlanner {

  private static final Logger log = LoggerFactory.getLogger(KakaoMobilityClient.class);
  static final String KEY_PROPERTY = "pmart.tracking.kakao-mobility.rest-api-key";

  private final RestClient http;
  private final boolean configured;
  private final int radiusM;

  public KakaoMobilityClient(TrackingProperties props) {
    TrackingProperties.KakaoMobility k = props.kakaoMobility();
    this.configured = k.restApiKey() != null;
    this.radiusM = k.radiusM();
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(k.connectTimeout())
                .build());
    factory.setReadTimeout(k.readTimeout());
    this.http =
        RestClient.builder()
            .baseUrl(k.baseUrl())
            .requestFactory(factory)
            .defaultHeader("Authorization", configured ? "KakaoAK " + k.restApiKey() : "")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  @Override
  public List<Leg> fromOne(Point origin, List<Point> destinations) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("origin", xy(origin, null));
    List<Map<String, Object>> ds = new ArrayList<>();
    for (int i = 0; i < destinations.size(); i++) {
      ds.add(xy(destinations.get(i), String.valueOf(i)));
    }
    body.put("destinations", ds);
    body.put("radius", radiusM);
    body.put("priority", "TIME");
    return legs(post("/v1/destinations/directions", body), destinations.size());
  }

  @Override
  public List<Leg> toOne(List<Point> origins, Point destination) {
    Map<String, Object> body = new LinkedHashMap<>();
    List<Map<String, Object>> os = new ArrayList<>();
    for (int i = 0; i < origins.size(); i++) {
      os.add(xy(origins.get(i), String.valueOf(i)));
    }
    body.put("origins", os);
    body.put("destination", xy(destination, null));
    body.put("radius", radiusM);
    body.put("priority", "TIME");
    return legs(post("/v1/origins/directions", body), origins.size());
  }

  private static List<Leg> legs(JsonNode body, int n) {
    List<Leg> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(Leg.NO_ROUTE);
    }
    for (JsonNode r : body.path("routes")) {
      int idx;
      try {
        idx = Integer.parseInt(r.path("key").asString(""));
      } catch (NumberFormatException e) {
        continue;
      }
      if (idx < 0 || idx >= n || r.path("result_code").asInt(-1) != 0) {
        continue;
      }
      JsonNode s = r.path("summary");
      out.set(idx, new Leg(true, s.path("distance").asInt(0), s.path("duration").asInt(0)));
    }
    return out;
  }

  private JsonNode post(String path, Map<String, Object> body) {
    if (!configured) {
      throw PmartException.configRequired(KEY_PROPERTY);
    }
    ResponseEntity<JsonNode> e;
    try {
      e =
          http.post()
              .uri(path)
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .toEntity(JsonNode.class);
    } catch (ResourceAccessException ex) {
      log.warn("카카오모빌리티 연결·시간 초과 — 거리 조회 실패");
      throw new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "도로 거리를 지금 조회할 수 없습니다.");
    }
    int status = e.getStatusCode().value();
    if (status == 429) {
      log.warn("카카오모빌리티 사용량 소진(429)");
      throw new PmartException(
          "QUOTA_EXCEEDED",
          HttpStatus.TOO_MANY_REQUESTS,
          "도로 거리 조회 사용량이 소진되었습니다.",
          false,
          List.of());
    }
    if (status == 401 || status == 403) {
      log.error(
          "카카오모빌리티 키 거절({} {}) — 앱의 카카오모빌리티(카카오내비) 사용 설정·REST API 키를 확인한다",
          status,
          e.getBody() == null ? "" : e.getBody().path("msg").asString(""));
      throw PmartException.configRequired(KEY_PROPERTY);
    }
    if (status >= 400 || e.getBody() == null) {
      log.warn("카카오모빌리티 응답 {} — 거리 조회 실패", status);
      throw new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "도로 거리를 지금 조회할 수 없습니다.");
    }
    return e.getBody();
  }

  private static Map<String, Object> xy(Point p, String key) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("x", String.valueOf(p.lng()));
    m.put("y", String.valueOf(p.lat()));
    if (key != null) {
      m.put("key", key);
    }
    return m;
  }
}
