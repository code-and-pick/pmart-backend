package com.pmart.delivery.execution.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * rider-service 내부 호출 — 운행 구간(rider 0.11.0 {@code GET /internal/riders/{id}/duty-segments},
 * 2026-11-18 직원 접속·대기 시간). 주소 LAN 기본 {@code http://rider-service:8403}.
 */
@Component
public class RiderClient {

  private static final Logger log = LoggerFactory.getLogger(RiderClient.class);

  /** 운행 구간 — endedAt null 은 지금 열려 있음, available = 신규배차 켬. */
  public record DutySegment(Instant startedAt, @Nullable Instant endedAt, boolean available) {}

  private final RestClient rider;
  private final ObjectMapper mapper;

  public RiderClient(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${pmart.execution.urls.rider:http://rider-service:8403}") String baseUrl) {
    this.rider = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  /** 못 부르면 비어 있다(직원 화면은 접속·대기를 null 로) — 운행시간 조회를 막지 않는다. */
  public Optional<List<DutySegment>> dutySegments(String riderId, Instant from, Instant to) {
    try {
      String body =
          Downstream.call(
              "rider-service duty-segments",
              () ->
                  rider
                      .get()
                      .uri(
                          "/api/v1/internal/riders/{id}/duty-segments?from={f}&to={t}",
                          riderId,
                          from.toString(),
                          to.toString())
                      .retrieve()
                      .body(String.class));
      List<DutySegment> out = new ArrayList<>();
      for (JsonNode n : mapper.readTree(body == null ? "{}" : body).path("items")) {
        out.add(
            new DutySegment(
                Instant.parse(n.path("startedAt").asString()),
                n.path("endedAt").isString() ? Instant.parse(n.path("endedAt").asString()) : null,
                n.path("available").asBoolean(false)));
      }
      return Optional.of(out);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.of(List.of());
    } catch (HttpClientErrorException | PmartException e) {
      log.warn("라이더 {} 운행 구간 조회 실패 — 접속·대기 없이: {}", riderId, e.getMessage());
      return Optional.empty();
    }
  }
}
