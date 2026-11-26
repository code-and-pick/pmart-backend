package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.infrastructure.EventLogStore;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 사건 기록(0.25.0 — DC 백오피스 M-5, PC 5 요청 2026-11-25-backend-monitoring-gaps). 이미 남은 사실을 최신 순으로 — 새
 * 표·이상 알림 등급 없음(기준 결정 전). 자기 센터 운영자만.
 */
@Service
public class EventLog {

  static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
  static final int MAX = 500;

  public record Page(
      Instant since, Instant until, boolean truncated, List<EventLogStore.Row> items) {}

  private final EventLogStore store;
  private final Support support;

  public EventLog(EventLogStore store, Support support) {
    this.store = store;
    this.support = support;
  }

  public Page events(
      String cdcId,
      @Nullable Instant since,
      @Nullable Instant until,
      @Nullable Collection<String> types,
      @Nullable Integer limit) {
    Support.requireCenter(cdcId);
    Instant u = until == null ? support.now() : until;
    Instant s = since == null ? u.minus(DEFAULT_WINDOW) : since;
    if (!s.isBefore(u)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "since 는 until 보다 앞이어야 합니다.");
    }
    int n = limit == null ? 200 : Math.min(MAX, Math.max(1, limit));
    List<EventLogStore.Row> rows = store.events(cdcId, s, u, types, n);
    boolean cut = rows.size() > n;
    return new Page(s, u, cut, cut ? rows.subList(0, n) : rows);
  }
}
