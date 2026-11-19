package com.pmart.delivery.execution.application;

import com.pmart.common.security.PmartPrincipal;
import org.jspecify.annotations.Nullable;

/**
 * 운영자 범위 — 그 센터 운영자(MFC 백오피스), OPERATOR_ADMIN, 고객센터 CS_AGENT, 라이더 운영 RIDER_OPS(BOD-1, 2026-11-15
 * 사용자 "라이더 운영 전용 역할을 만든다").
 */
final class Access {

  private Access() {}

  /** 센터와 상관없이 모든 배송을 보는 직원 역할(백오피스 고객센터·라이더 운영 메뉴, BOD-1). */
  static boolean allCenters(PmartPrincipal p) {
    return p.type() == PmartPrincipal.PrincipalType.OPERATOR
        && (p.hasRole("OPERATOR_ADMIN") || p.hasRole("CS_AGENT") || p.hasRole("RIDER_OPS"));
  }

  static boolean operatorCanSee(PmartPrincipal p, @Nullable String mfcId) {
    return p.type() == PmartPrincipal.PrincipalType.OPERATOR
        && (allCenters(p) || (mfcId != null && p.canAccessCenter(mfcId)));
  }

  /**
   * 전달 실패 확정·반려, 도움요청 처리함 닫기 — 관리자만(사용자 2026-11-14 "관리자로 좁히고"): 그 센터의 {@code MFC_SUPERVISOR},
   * OPERATOR_ADMIN, CS_AGENT, RIDER_OPS(BOD-1). 센터 소속이어도 작업자(MFC_WORKER 등)는 안 된다.
   */
  static boolean operatorCanDecide(PmartPrincipal p, @Nullable String mfcId) {
    // 2026-11-19 사용자 "라이더 백오피스만": 라이더·배송 일(전달 실패 결정·도움요청 닫기)은 라이더 운영·관리자만.
    // 상담원(통합 백오피스)·센터 관리자(MFC 백오피스)는 보기만 — 한 일에 처리 주인 하나(두 화면에서 엇갈린 결정을 막는다)
    return p.type() == PmartPrincipal.PrincipalType.OPERATOR
        && (p.hasRole("OPERATOR_ADMIN") || p.hasRole("RIDER_OPS"));
  }

  /**
   * 직원 목록(0.4.0, BOD-4·5)의 범위 — OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 는 모든 센터, 그 밖 운영자는 mfcId 를 주어야 하고
   * 그 센터 권한이 있어야 한다. 라이더·고객·서비스는 403.
   */
  static PmartPrincipal requireStaffScope(@Nullable String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> ExecutionService.forbidden("직원만 볼 수 있습니다."));
    if (allCenters(p)) {
      return p;
    }
    if (mfcId == null || mfcId.isBlank()) {
      throw ExecutionService.forbidden("센터 직원은 mfcId 를 주어야 합니다.");
    }
    if (!p.canAccessCenter(mfcId)) {
      throw ExecutionService.forbidden("이 센터 배송을 볼 수 없습니다.");
    }
    return p;
  }
}
