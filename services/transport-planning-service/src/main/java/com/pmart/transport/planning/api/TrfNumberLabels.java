package com.pmart.transport.planning.api;

import com.pmart.transport.planning.api.generated.model.GenerateTransportOrders200Response;
import com.pmart.transport.planning.api.generated.model.ListTransportOrders200Response;
import com.pmart.transport.planning.api.generated.model.TransportOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 배차 지시서에 TRF 화면 번호 붙이기(0.4.0 — DC 백오피스 DN-4). 번호는 운송 요청을 가져올 때 받은 cdc-outbound 사본({@code
 * transport_request.trf_number}). 붙이다 실패하면 로그만 남기고 원래 응답 그대로(화면 보조값).
 */
@ControllerAdvice(basePackageClasses = TrfNumberLabels.class)
class TrfNumberLabels implements ResponseBodyAdvice<Object> {

  private static final Logger log = LoggerFactory.getLogger(TrfNumberLabels.class);

  private final JdbcTemplate jdbc;

  TrfNumberLabels(JdbcTemplate jdbc) {
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
        case TransportOrder o -> orders(List.of(o));
        case ListTransportOrders200Response l -> orders(l.getItems());
        case GenerateTransportOrders200Response g -> orders(g.getOrders());
        case null, default -> {}
      }
    } catch (RuntimeException e) {
      log.warn("TRF 화면 번호 붙이기 실패(원래 응답 그대로): {}", e.toString());
    }
    return body;
  }

  void orders(@Nullable List<TransportOrder> os) {
    if (os == null || os.isEmpty()) {
      return;
    }
    Set<String> ids = new HashSet<>();
    for (TransportOrder o : os) {
      if (o.getTrfIds() != null) {
        ids.addAll(o.getTrfIds());
      }
      if (o.getStops() != null) {
        o.getStops()
            .forEach(
                s -> {
                  if (s.getTrfIds() != null) {
                    ids.addAll(s.getTrfIds());
                  }
                });
      }
    }
    Map<String, String> n = numbers(ids);
    for (TransportOrder o : os) {
      if (o.getTrfIds() != null) {
        o.setTrfNumbers(list(o.getTrfIds(), n));
      }
      if (o.getStops() != null) {
        o.getStops()
            .forEach(
                s -> {
                  if (s.getTrfIds() != null) {
                    s.setTrfNumbers(list(s.getTrfIds(), n));
                  }
                });
      }
    }
  }

  private Map<String, String> numbers(Set<String> ids) {
    Map<String, String> out = new HashMap<>();
    if (ids.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT trf_id, trf_number FROM transport_request WHERE trf_id = ANY (?) AND trf_number IS NOT NULL",
        rs -> {
          out.put(rs.getString(1), rs.getString(2));
        },
        (Object) ids.toArray(String[]::new));
    return out;
  }

  private static List<String> list(List<String> ids, Map<String, String> n) {
    return new ArrayList<>(ids.stream().map(id -> n.getOrDefault(id, "")).toList());
  }
}
