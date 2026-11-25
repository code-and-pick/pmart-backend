package com.pmart.cdc.quality.application;

import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;

/** 호출 운영자 — 운영자 토큰이고 {@code centers} 클레임에 대상 센터가 있어야 한다(cdc-inventory 와 같다). 결정은 CDC_MANAGER 만. */
public record Operator(String id, String sourceSystem, boolean manager) {

  static final String MANAGER = "CDC_MANAGER";

  public static Operator forCenter(String centerId, String sourceSystem) {
    PmartPrincipal p = principal(centerId);
    return new Operator(p.subject(), sourceSystem, p.hasRole(MANAGER));
  }

  /** 조회 — 이 센터의 운영자인지. */
  public static String requireCenter(String centerId) {
    return principal(centerId).subject();
  }

  private static PmartPrincipal principal(String centerId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(centerId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return p;
  }

  void requireManager() {
    if (!manager) {
      throw new PmartException(ErrorCode.FORBIDDEN, "결정은 센터 관리자(" + MANAGER + ")만 할 수 있습니다.");
    }
  }

  RequestKey key(String requestId, String operation) {
    return new RequestKey(sourceSystem, requestId, operation, id);
  }
}
