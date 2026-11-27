package com.pmart.transport.execution.api;

import com.pmart.transport.execution.api.generated.model.Trip;
import com.pmart.transport.execution.api.generated.model.TripPage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 운행 화면 번호 붙이기(0.6.0 — DC 백오피스 DN-3·MFC 시뮬레이터 R-52). TRF 번호 = planning 이 준
 * 사본(trip_transfer.trf_number), 출발 번호 = cdc-outbound 응답 사본(trip.departure_number). 붙이다 실패하면 로그만 남기고
 * 원래 응답(화면 보조값).
 */
@ControllerAdvice(basePackageClasses = TripNumberLabels.class)
class TripNumberLabels implements ResponseBodyAdvice<Object> {

  private static final Logger log = LoggerFactory.getLogger(TripNumberLabels.class);

  private final JdbcTemplate jdbc;

  TripNumberLabels(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public @Nullable Object beforeBodyWrite(
      @Nullable Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    try {
      switch (body) {
        case Trip t -> trips(List.of(t));
        case TripPage p -> trips(p.getItems());
        case null, default -> {}
      }
    } catch (RuntimeException e) {
      log.warn("운행 화면 번호 붙이기 실패(원래 응답 그대로): {}", e.toString());
    }
    return body;
  }

  void trips(@Nullable List<Trip> ts) {
    if (ts == null || ts.isEmpty()) {
      return;
    }
    String[] ids = ts.stream().map(Trip::getTripId).distinct().toArray(String[]::new);
    Map<String, String> trf = new HashMap<>(); // tripId|trfId → 번호
    jdbc.query(
        "SELECT trip_id, trf_id, trf_number FROM trip_transfer WHERE trip_id = ANY (?) AND trf_number IS NOT NULL",
        rs -> {
          trf.put(rs.getString(1) + "|" + rs.getString(2), rs.getString(3));
        },
        (Object) ids);
    Map<String, String> dep = new HashMap<>();
    jdbc.query(
        "SELECT trip_id, departure_number FROM trip WHERE trip_id = ANY (?) AND departure_number IS NOT NULL",
        rs -> {
          dep.put(rs.getString(1), rs.getString(2));
        },
        (Object) ids);
    for (Trip t : ts) {
      t.setDepartureNumber(dep.get(t.getTripId()));
      if (t.getTransfers() != null) {
        t.getTransfers().forEach(x -> x.setTrfNumber(trf.get(t.getTripId() + "|" + x.getTrfId())));
      }
      if (t.getStops() != null) {
        t.getStops()
            .forEach(
                s -> {
                  if (s.getTrfIds() != null) {
                    s.setTrfNumbers(
                        new ArrayList<>(
                            s.getTrfIds().stream()
                                .map(id -> trf.getOrDefault(t.getTripId() + "|" + id, ""))
                                .toList()));
                  }
                });
      }
    }
  }
}
