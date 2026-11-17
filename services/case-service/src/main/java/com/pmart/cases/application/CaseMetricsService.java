package com.pmart.cases.application;

import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.infrastructure.CaseMetricsStore;
import com.pmart.cases.infrastructure.CaseMetricsStore.Snapshot;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 접수 건 현황(BO-28 1단계 — 2026-11-17 사용자 "우리 데이터 건수부터", 계약 0.18.0). 본사 관리자만. 사건함 종류별 열린·안 집은·기한 지난 건,
 * 열린 후속 처리, 수락 전 교대 인계, 답변 기다리는 문의 — 지금 시각 스냅샷. 비율·목표·SLA 달성률은 승인된 정의가 없어 내지 않는다. 상담원·직원 개인별로 나누지
 * 않는다.
 */
@Service
public class CaseMetricsService {

  public record View(Instant asOf, Snapshot snapshot) {}

  public record SlaView(Instant asOf, boolean hour, List<CaseMetricsStore.SlaRow> rows) {}

  static final ZoneId KST = ZoneId.of("Asia/Seoul");
  static final int MAX_DAYS_DAY = 93;
  static final int MAX_DAYS_HOUR = 7;

  private final CaseMetricsStore store;
  private final Clock clock;

  public CaseMetricsService(CaseMetricsStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  /**
   * 기한 준수(0.24.0, 2026-11-18 사용자 "일단 그렇게 하고" — bff CASE_SLA 의 원천). from·to 는 한국 날짜(포함). DAY
   * 93일·HOUR 7일까지.
   */
  public SlaView sla(
      LocalDate from, LocalDate to, @Nullable String granularity, @Nullable String centerId) {
    requireAdmin();
    boolean hour = "HOUR".equals(granularity);
    if (granularity != null && !hour && !"DAY".equals(granularity)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "granularity 는 DAY·HOUR 입니다.");
    }
    if (to.isBefore(from)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "to 가 from 보다 앞입니다.");
    }
    long days = Duration.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()).toDays();
    int max = hour ? MAX_DAYS_HOUR : MAX_DAYS_DAY;
    if (days > max) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, (hour ? "HOUR" : "DAY") + " 는 " + max + "일까지 볼 수 있습니다.");
    }
    String center = centerId == null || centerId.isBlank() ? null : centerId.strip();
    Instant start = from.atStartOfDay(KST).toInstant();
    Instant end = to.plusDays(1).atStartOfDay(KST).toInstant();
    return new SlaView(
        clock.instant(),
        hour,
        store.sla(List.copyOf(CaseRules.ALL_INBOX_KINDS), start, end, hour, center));
  }

  private static void requireAdmin() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.hasRole(CaseRules.ADMIN)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "접수 건 현황은 본사 관리자만 봅니다.");
    }
  }

  public View snapshot() {
    requireAdmin();
    Instant now = clock.instant();
    return new View(now, store.snapshot(List.copyOf(CaseRules.ALL_INBOX_KINDS), now));
  }
}
