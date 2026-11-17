package com.pmart.cases.application;

import com.pmart.cases.infrastructure.CaseViewStore;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 접수 건 "마지막으로 본 시각"(0.20.0 — 2026-11-17 사용자 결정 ① (가)). 도움요청 새 메시지 점 등 화면의 "새로 생김" 판단을 직원별로 서버에
 * 둔다(탭을 닫아도·기기를 바꿔도 유지). 그 건을 볼 수 있는 직원만 남기고, 목록·상세에는 부른 직원 자신의 것만 붙는다. 사건 상태·이력·이벤트와 무관.
 */
@Service
public class CaseViewService {

  private final CaseViewStore store;
  private final CaseInboxService inbox;
  private final Clock clock;

  public CaseViewService(CaseViewStore store, CaseInboxService inbox, Clock clock) {
    this.store = store;
    this.inbox = inbox;
    this.clock = clock;
  }

  /** 지금 봤음 — 볼 수 없는 건은 404(사건함 규칙 그대로). 같은 요청을 여러 번 보내도 결과는 같은 꼴(멱등 키 불필요). */
  public Instant markSeen(String caseId) {
    String me = operator();
    inbox.visibleCase(caseId);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    store.record(caseId, me, now);
    return now;
  }

  /** 부른 직원이 이 건들을 마지막으로 본 시각. 직원이 아니면 빈 값. */
  public Map<String, Instant> mine(List<String> caseIds) {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .map(p -> store.seen(p.subject(), caseIds))
        .orElse(Map.of());
  }

  private static String operator() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .map(PmartPrincipal::subject)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "직원만 남깁니다."));
  }
}
