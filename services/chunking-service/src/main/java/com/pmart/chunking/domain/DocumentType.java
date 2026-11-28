package com.pmart.chunking.domain;

/**
 * 문서 종류(ES 청크 {@code documentType}, 색인 v2 — 2026-11-28 ES-First 2차 검증). 문서 저장소 폴더로 정한다 — 폴더가 곧 문서
 * 종류다.
 *
 * <p>권위 수준·접근 권한·승인·유효 상태는 여기서 정하지 않는다(경로만으로 추정하지 않음 — 검토 합의). 접근 범위는 {@link AccessScope}(색인 자체를 나눔
 * = 필터), 권위·승인 상태는 문서 메타데이터가 생기면 문서에서 받아 청크가 물려받게 한다.
 */
public enum DocumentType {
  /** 직원용 운영 정책·업무 기준({@code operator/<영역>/}, common 제외). */
  POLICY,
  /** 용어·사업 개요 같은 참고 문서({@code operator/common/}). */
  REFERENCE,
  /** 고객 앱 안내({@code customer/customer_app/}). */
  APP_GUIDE,
  /** 승인된 고객 FAQ({@code customer/faq/}). */
  FAQ,
  /** 그 밖(색인 대상이 아니면 쓰이지 않음). */
  OTHER;

  public static DocumentType of(String path) {
    if (path.startsWith("operator/common/")) {
      return REFERENCE;
    }
    if (path.startsWith("operator/")) {
      return POLICY;
    }
    if (path.startsWith("customer/customer_app/")) {
      return APP_GUIDE;
    }
    if (path.startsWith("customer/faq/")) {
      return FAQ;
    }
    return OTHER;
  }
}
