package com.pmart.cases.application;

import com.pmart.cases.domain.CaseRules;
import com.pmart.cases.infrastructure.CaseMetricsStore;
import com.pmart.cases.infrastructure.CaseMetricsStore.Snapshot;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 접수 건 현황(BO-28 1단계 — 2026-11-17 사용자 "우리 데이터 건수부터", 계약 0.18.0). 본사 관리자만. 사건함 종류별 열린·안 집은·기한 지난 건,
 * 열린 후속 처리, 수락 전 교대 인계, 답변 기다리는 문의 — 지금 시각 스냅샷. 비율·목표·SLA 달성률은 승인된 정의가 없어 내지 않는다. 상담원·직원 개인별로 나누지
 * 않는다.
 */
@Service
public class CaseMetricsService {

  public record View(Instant asOf, Snapshot snapshot) {}

  private final CaseMetricsStore store;
  private final Clock clock;

  public CaseMetricsService(CaseMetricsStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public View snapshot() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.hasRole(CaseRules.ADMIN)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "접수 건 현황은 본사 관리자만 봅니다.");
    }
    Instant now = clock.instant();
    return new View(now, store.snapshot(List.copyOf(CaseRules.ALL_INBOX_KINDS), now));
  }
}
