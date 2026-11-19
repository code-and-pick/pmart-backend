package com.pmart.delivery.dispatch.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.delivery.dispatch.infrastructure.DispatchStore.DeliveryRow;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 배차 지연 배송 목록(2026-11-19 — 원문 §3.6, 사용자 "1,2,3 전부"). DispatchDelayed 를 낸 배송 중 픽업 전인 것 — MFC 백오피스가
 * 지연을 보고 "배차 불가 취소"(claim)로 간다.
 */
@Service
public class DispatchDelays {

  private final JdbcTemplate jdbc;
  private final com.pmart.delivery.dispatch.infrastructure.DispatchStore store;

  public DispatchDelays(
      JdbcTemplate jdbc, com.pmart.delivery.dispatch.infrastructure.DispatchStore store) {
    this.jdbc = jdbc;
    this.store = store;
  }

  public List<DeliveryRow> list(@Nullable String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> OfferService.forbidden("직원만 볼 수 있습니다."));
    boolean all = p.hasRole("OPERATOR_ADMIN") || p.hasRole("CS_AGENT") || p.hasRole("RIDER_OPS");
    if (!all && (mfcId == null || mfcId.isBlank() || !p.canAccessCenter(mfcId))) {
      throw OfferService.forbidden("그 센터 직원만 볼 수 있습니다(mfcId 필요).");
    }
    List<String> ids =
        jdbc.queryForList(
            "SELECT delivery_id FROM delivery WHERE delay_alerted_at IS NOT NULL AND state IN"
                + " ('CREATED', 'DISPATCHING', 'ASSIGNED')"
                + (mfcId == null || mfcId.isBlank() ? "" : " AND mfc_id = ?")
                + " ORDER BY promised_at NULLS LAST, delivery_id LIMIT 200",
            String.class,
            mfcId == null || mfcId.isBlank() ? new Object[0] : new Object[] {mfcId});
    return ids.stream().map(store::delivery).flatMap(java.util.Optional::stream).toList();
  }
}
