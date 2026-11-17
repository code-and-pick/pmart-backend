package com.pmart.rider.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 정산 은행 — 금융결제원 은행(금융기관) 코드 3자리(2026-11-17 사용자 결정, 라이더 앱 요청 §16-1). 공개된 표의 값이라 저장·사용에 계약이 필요
 * 없다(실명·1원 확인 API 는 §9 "나중에"). 은행이 더 필요하면 여기에 더한다.
 */
public final class BankCodes {

  private BankCodes() {}

  public static final Map<String, String> ALL;

  static {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("002", "KDB산업은행");
    m.put("003", "IBK기업은행");
    m.put("004", "KB국민은행");
    m.put("007", "수협은행");
    m.put("011", "NH농협은행");
    m.put("012", "지역농축협");
    m.put("020", "우리은행");
    m.put("023", "SC제일은행");
    m.put("027", "한국씨티은행");
    m.put("031", "iM뱅크(대구)");
    m.put("032", "부산은행");
    m.put("034", "광주은행");
    m.put("035", "제주은행");
    m.put("037", "전북은행");
    m.put("039", "경남은행");
    m.put("045", "새마을금고");
    m.put("048", "신협");
    m.put("050", "저축은행");
    m.put("064", "산림조합");
    m.put("071", "우체국");
    m.put("081", "하나은행");
    m.put("088", "신한은행");
    m.put("089", "케이뱅크");
    m.put("090", "카카오뱅크");
    m.put("092", "토스뱅크");
    ALL = java.util.Collections.unmodifiableMap(m);
  }

  public static boolean known(String code) {
    return ALL.containsKey(code);
  }
}
