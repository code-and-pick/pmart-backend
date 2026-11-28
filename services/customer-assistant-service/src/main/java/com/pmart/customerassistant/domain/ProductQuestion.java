package com.pmart.customerassistant.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상품을 묻는 질문인지 가려 검색어(상품 이름)만 뽑는다 — 규칙 기반, 모델 없음(2026-11-20 사용자 "상품검색 ES 를 RAG 에 연결", "상품 질문만 정해진
 * 문장").
 *
 * <ul>
 *   <li>가격: "두부 얼마예요?", "생수 가격 알려줘", "우유 값이 얼마야"
 *   <li>판매 여부: "두부 있어요?", "콜라 팔아요?", "계란 살 수 있나요?", "라면 품절이에요?"
 * </ul>
 *
 * 업무 규칙·요금을 묻는 말(봉투값·배달팁·회수비·취소·환불·반품·주문·배송 …)이 들어 있으면 상품 질문으로 보지 않는다 — 그런 질문은 문서 검색(RAG)이 답한다.
 * <b>주류</b>(주류·술·소주·맥주·와인·막걸리 …)도 상품 질문으로 보지 않는다 — 주류 상품 데이터는 있지만 답은 "구매할 수 없음"(고객 안내
 * customer_app/06 제8조)이다(사용자 2026-11-28 "일단 주류 팔긴하는데, 그런 정책을 당장 세울순 없어서 구매할수없다고 대답하는거야"). 검색어는
 * search-service 가 형태소·n-gram 으로 찾으므로 조사·꾸밈말만 떼고 그대로 넘긴다.
 *
 * <p>운영자용(ai-assistant)·고객용(customer-assistant)이 같은 규칙을 쓴다 — 한쪽을 고치면 다른 쪽도 고친다.
 */
public final class ProductQuestion {

  /** 업무 규칙·요금·처리를 묻는 말 — 들어 있으면 상품 질문이 아니다. */
  private static final Pattern POLICY_TERMS =
      Pattern.compile(
          "봉투|배달팁|배송비|회수비|수수료|운임|수행료|요금|최소 ?주문|첫 ?주문|쿠폰|할인|포인트|적립|"
              + "취소|환불|반품|교환|결제|주문|배송|배달|라이더|리뷰|알림|회원|탈퇴|가입|로그인|계정|"
              + "정책|기준|절차|방법|규칙|조건|권한|승인|직원|센터|재고|입고|출고|피킹|포장|인계|실사|"
              + "시간|언제|어떻게|왜|누가|누구|몇 ?개");

  /** 주류 — 상품 검색 답으로 "판매 중"을 보이지 않고 문서(구매 불가)로 답한다. "술"은 낱말로 있을 때만(기술·수술·술빵 같은 말은 아님). */
  private static final Pattern ALCOHOL =
      Pattern.compile(
          "주류|소주|맥주|와인|막걸리|위스키|하이볼|사케|보드카|양주|전통주|칵테일|샴페인|고량주|청주|약주|리큐어|"
              + "(?:^|\\s)술(?:$|\\s|은|는|이|도|을|를|요|이요|\\?)");

  /** 가격 질문: 앞부분이 상품 이름. */
  private static final Pattern PRICE =
      Pattern.compile("^(.+?)\\s*(?:의\\s*)?(?:(?:은|는|이|가)\\s*)?(?:가격|값|얼마)");

  /** 판매 여부 질문: 앞부분이 상품 이름. */
  private static final Pattern AVAILABLE =
      Pattern.compile(
          "^(.+?)\\s*(?:(?:은|는|이|가|을|를|도)\\s*)?"
              + "(?:있어요|있나요|있어\\?|있어$|있니|있을까요|있습니까|있는지|"
              + "팔아요|파나요|팔아\\?|팔아$|파니|팔고 있|판매하나요|판매해요|판매 ?중|판매하고 있|"
              + "살 수 있|구매할 수 있|구매 가능|품절)");

  /** 앞에 붙는 꾸밈말. */
  private static final Pattern LEADING =
      Pattern.compile(
          "^(?:혹시|지금|요즘|오늘|여기서|여기|pmart\\s?에서|pmart|피마트에서|피마트)\\s*", Pattern.CASE_INSENSITIVE);

  /** 뒤에 붙는 꾸밈말("햇반 지금 팔아요?" — 2026-11-28 평가 P03). */
  private static final Pattern TRAILING =
      Pattern.compile("\\s+(?:혹시|지금|요즘|오늘|여기서|여기|현재|아직|아직도|좀)$");

  /** 상품 이름으로 볼 수 없는 말. */
  private static final Pattern NOT_A_NAME =
      Pattern.compile("^(?:그거|이거|저거|그것|이것|상품|물건|제품|뭐|무엇|어떤 ?것|아무거나)$");

  private ProductQuestion() {}

  /** 상품 질문이면 검색어, 아니면 비어 있다. */
  public static Optional<String> keyword(String question) {
    String q = RequestHashing.normalize(question);
    if (q.isEmpty() || POLICY_TERMS.matcher(q).find() || ALCOHOL.matcher(q).find()) {
      return Optional.empty();
    }
    for (Pattern p : new Pattern[] {PRICE, AVAILABLE}) {
      Matcher m = p.matcher(q);
      if (m.find()) {
        String kw = clean(m.group(1));
        if (!kw.isEmpty() && kw.length() <= 30 && !NOT_A_NAME.matcher(kw).matches()) {
          return Optional.of(kw);
        }
      }
    }
    return Optional.empty();
  }

  private static String clean(String s) {
    String kw = s.strip();
    String prev;
    do {
      prev = kw;
      kw = LEADING.matcher(kw).replaceFirst("").strip();
      kw = kw.replaceAll("[\\s,.!?~·]+$", "").strip();
      kw = TRAILING.matcher(kw).replaceFirst("").strip();
    } while (!kw.equals(prev));
    return kw;
  }
}
