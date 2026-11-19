package com.pmart.cases.domain;

import java.util.List;
import java.util.Set;

/**
 * 라이더 컴플레인 규칙(0.3.0, service-requests case R-5 — RS-3) — 프레임워크 없음. 사용자 결정 2026-11-15: 상담원이 백오피스에서
 * 기록한다. 세고 제한 검토 대상을 여는 것은 rider-service(자동 제재 없음).
 */
public final class RiderComplaintRules {

  private RiderComplaintRules() {}

  /** 신고·오배송·파손(RiderComplaintRecorded 와 같은 값). */
  public static final Set<String> TYPES = Set.of("REPORT", "MISDELIVERY", "DAMAGE");

  /** 계약 enum 순서(0.26.0 종류별 수 — 늘 같은 순서로). */
  public static final List<String> TYPE_ORDER = List.of("REPORT", "MISDELIVERY", "DAMAGE");

  /** 상담원 접수 경로(사건 payload source). */
  public static final String SOURCE_CS_AGENT = "CS_AGENT";

  public static final String OPERATOR_ADMIN = "OPERATOR_ADMIN";

  /** 라이더 운영(2026-11-15 employee-auth 에 더하는 직원 역할) — 조회만. */
  public static final String RIDER_OPS = "RIDER_OPS";

  /** 기록할 수 있는 역할. */
  public static final List<String> RECORD_ROLES = List.of(InquiryRules.CS_AGENT, OPERATOR_ADMIN);

  /** 볼 수 있는 역할. */
  public static final List<String> READ_ROLES =
      List.of(InquiryRules.CS_AGENT, OPERATOR_ADMIN, RIDER_OPS);
}
