package com.pmart.order.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 주문서 금액·배송 규칙(Commerce 8장 [사용자 재확인]·[확정 정책]). 1차는 할인·쿠폰 없음 — 상품액 = 기본단가 × 수량 합.
 *
 * <ul>
 *   <li>배달팁: 한집 4,000 · 알뜰/예약 3,000. 상품액 30,000 이상 또는 첫 구매면 0(두 사유는 중복 차감 아님 — 30,000 이 먼저).
 *   <li>봉투값: 주문당 100 한 번(미사용 선택·분할 배송과 무관).
 *   <li>최소 주문: 상품액 15,000(배달팁·봉투값 제외). 부족액 = 15,000 − 상품액.
 *   <li>가격 동결 10분. 총 상품중량 10,000g 초과는 여러 배송으로 나눈다(추가 배달팁 없음). 단일 상품 10kg 초과는 나눌 수 없다.
 *   <li>[확정] 00:00~08:00 실제 배송 없음 — 한집·알뜰은 그 시간에 불가, 예약은 08시 이후 슬롯으로.
 * </ul>
 */
public final class OrderRules {

  public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  public static final long MIN_ORDER_AMOUNT = 15_000;
  public static final long FREE_DELIVERY_AMOUNT = 30_000;
  public static final long BAG_FEE = 100;
  public static final int MAX_WEIGHT_PER_DELIVERY_G = 10_000;
  public static final Duration QUOTE_TTL = Duration.ofMinutes(10);
  public static final int NIGHT_END_MINUTE = 8 * 60;

  public static final String SINGLE = "SINGLE";
  public static final String BUNDLED = "BUNDLED";
  public static final String SCHEDULED = "SCHEDULED";
  public static final List<String> MODES = List.of(SINGLE, BUNDLED, SCHEDULED);

  /** 차단 사유 표시 순서(주버튼은 첫 사유) — Commerce 10장: 최소금액 → 방식 → 예약시간 → 그 밖. */
  public static final List<String> BLOCKER_ORDER =
      List.of("MIN_ORDER_AMOUNT", "DELIVERY_MODE_UNAVAILABLE", "SLOT_REQUIRED", "SLOT_UNAVAILABLE");

  public record Tip(long base, long applied, String reason) {}

  public record Amounts(
      long itemAmount,
      Tip tip,
      long bagFee,
      long total,
      boolean minOrderSatisfied,
      long minOrderShortfall) {}

  public record Blocker(String code, @Nullable String detail) {}

  private OrderRules() {}

  public static long tipBase(String mode) {
    return SINGLE.equals(mode) ? 4_000 : 3_000;
  }

  /**
   * @param firstPurchaseExpected 첫 구매 무료 예상(EXPECTED) — 비회원·판정 불가면 false
   */
  public static Tip tip(String mode, long itemAmount, boolean firstPurchaseExpected) {
    long base = tipBase(mode);
    if (itemAmount >= FREE_DELIVERY_AMOUNT) {
      return new Tip(base, 0, "OVER_30000");
    }
    if (firstPurchaseExpected) {
      return new Tip(base, 0, "FIRST_PURCHASE");
    }
    return new Tip(base, base, "STANDARD");
  }

  public static Amounts amounts(String mode, long itemAmount, boolean firstPurchaseExpected) {
    Tip tip = tip(mode, itemAmount, firstPurchaseExpected);
    long shortfall = Math.max(0, MIN_ORDER_AMOUNT - itemAmount);
    return new Amounts(
        itemAmount, tip, BAG_FEE, itemAmount + tip.applied() + BAG_FEE, shortfall == 0, shortfall);
  }

  /**
   * 배송 수(10,000g 단위로 담기). 단위 중량이 10,000g 을 넘는 품목이 있으면 -1(나눌 수 없음).
   *
   * @param unitWeights 품목별 [단위 중량, 수량]
   */
  public static int plannedShipments(List<int[]> unitWeights) {
    List<Integer> units = new ArrayList<>();
    for (int[] w : unitWeights) {
      if (w[0] > MAX_WEIGHT_PER_DELIVERY_G) {
        return -1;
      }
      for (int i = 0; i < w[1]; i++) {
        units.add(w[0]);
      }
    }
    if (units.isEmpty()) {
      return 0;
    }
    // 큰 것부터 첫 맞는 배송에 담기(first-fit decreasing)
    units.sort(Comparator.reverseOrder());
    List<Integer> loads = new ArrayList<>();
    for (int u : units) {
      boolean placed = false;
      for (int i = 0; i < loads.size(); i++) {
        if (loads.get(i) + u <= MAX_WEIGHT_PER_DELIVERY_G) {
          loads.set(i, loads.get(i) + u);
          placed = true;
          break;
        }
      }
      if (!placed) {
        loads.add(u);
      }
    }
    return loads.size();
  }

  /** 00:00~08:00(Asia/Seoul)인가 — 한집·알뜰 불가 시간. */
  public static boolean nightTime(Instant now) {
    ZonedDateTime local = now.atZone(SEOUL);
    return local.getHour() * 60 + local.getMinute() < NIGHT_END_MINUTE;
  }

  /** 지금(분, Asia/Seoul)이 운영 구간 안인가. ranges = [시작분, 끝분). */
  public static boolean within(Instant now, List<int[]> ranges) {
    ZonedDateTime local = now.atZone(SEOUL);
    int minute = local.getHour() * 60 + local.getMinute();
    return ranges.stream().anyMatch(r -> r[0] <= minute && minute < r[1]);
  }

  public static List<Blocker> ordered(List<Blocker> blockers) {
    List<Blocker> out = new ArrayList<>(blockers);
    out.sort(Comparator.comparingInt(b -> BLOCKER_ORDER.indexOf(b.code())));
    return out;
  }

  public static Instant quoteExpiresAt(Instant createdAt) {
    return createdAt.plus(QUOTE_TTL);
  }

  /** 주문행 ID — 초안 품목 순서(1부터). 결제 진입 때 사가에 보낸 값과 주문 확정 때 만드는 행이 같아야 한다. */
  public static String orderLineId(String orderId, int lineNo) {
    return orderId + "-L" + lineNo;
  }

  /** 첫 구매 무료배달을 실제로 적용한 주문인가(30,000원 이상 무료는 첫 구매 혜택을 쓰지 않는다). */
  public static boolean firstPurchaseApplied(String deliveryTipReason) {
    return "FIRST_PURCHASE".equals(deliveryTipReason);
  }

  /** 주문 확정 가능 금액인가 — PG 승인 금액이 초안의 동결 총액과 정확히 같아야 한다(부분·초과 승인 모두 차단). */
  public static boolean amountMatches(long approvedAmount, long frozenTotalAmount) {
    return approvedAmount == frozenTotalAmount;
  }

  /** 도착 구간(Asia/Seoul 날짜 + "HH:mm") → UTC 시각 [시작, 끝). 24:00 은 다음 날 00:00. */
  public static Instant[] windowInstants(java.time.LocalDate date, String start, String end) {
    return new Instant[] {at(date, start), at(date, end)};
  }

  private static Instant at(java.time.LocalDate date, String hhmm) {
    return date.atStartOfDay(SEOUL).plusMinutes(minutes(hhmm)).toInstant();
  }

  // ---------- 주문행 수량 (출고 전 취소) ----------

  /** 주문행 수량 누계. 불변식 원수량 = 미이행 + 인계 + 출고 전 취소. */
  public record LineQty(
      String orderLineId,
      int originalQty,
      int unfulfilledQty,
      int handedOverQty,
      int cancelledBeforeShipQty) {}

  /**
   * 출고 전 취소 반영. 행마다 미이행 −q, 출고 전 취소 +q. 없는 행·미이행보다 큰 수량·같은 행 중복이면 {@link IllegalArgumentException}
   * (아무것도 바꾸지 않는다 — 호출자가 409 로).
   */
  public static List<LineQty> cancelBeforeShip(List<LineQty> lines, List<Change> changes) {
    java.util.Map<String, Integer> req = new java.util.LinkedHashMap<>();
    for (Change c : changes) {
      if (req.put(c.orderLineId(), c.qty()) != null) {
        throw new IllegalArgumentException("같은 주문행이 두 번 왔습니다: " + c.orderLineId());
      }
    }
    List<LineQty> out = new ArrayList<>();
    for (LineQty l : lines) {
      Integer q = req.remove(l.orderLineId());
      if (q == null) {
        out.add(l);
        continue;
      }
      if (q < 1 || q > l.unfulfilledQty()) {
        throw new IllegalArgumentException(
            "주문행 " + l.orderLineId() + " 미이행 " + l.unfulfilledQty() + " 보다 많이 취소할 수 없습니다: " + q);
      }
      out.add(
          new LineQty(
              l.orderLineId(),
              l.originalQty(),
              l.unfulfilledQty() - q,
              l.handedOverQty(),
              l.cancelledBeforeShipQty() + q));
    }
    if (!req.isEmpty()) {
      throw new IllegalArgumentException("주문에 없는 주문행: " + req.keySet());
    }
    return out;
  }

  /** 취소 요청 한 줄(주문행·수량). */
  public record Change(String orderLineId, int qty) {}

  /** 모든 행이 미이행·인계 0 — 출고 전에 전부 취소됐다(주문 CANCELLED). */
  public static boolean fullyCancelled(List<LineQty> lines) {
    return !lines.isEmpty()
        && lines.stream().allMatch(l -> l.unfulfilledQty() == 0 && l.handedOverQty() == 0);
  }

  // ---------- 주문 초안 재고 확인 (2026-11-16) ----------

  /** 담당 센터의 한 SKU 가용(조회 사본). available 이 null 이면 불명. */
  public record Stock(@Nullable Integer available, boolean handlingEnabled) {}

  /**
   * 부족한 상품 하나 — reason 은 NOT_HANDLED 또는 requestedQty=<q>;availableQty=<a>(mfc-inventory 예약 거절과 같은
   * 모양).
   */
  public record Shortage(String sku, String reason) {}

  /**
   * 담당 센터 가용으로 본 부족 상품(주문 순서). 센터가 취급하지 않거나 가용이 주문 수량보다 적으면 부족. 가용 조회가 불명(null)이거나 그 SKU 값이 불명이면
   * 부족으로 보지 않는다 — 불명을 품절로 단정하지 않는다(결제 단계 재고 예약이 최종).
   */
  public static List<Shortage> stockShortages(
      java.util.Map<String, Integer> qty, java.util.@Nullable Map<String, Stock> stock) {
    List<Shortage> out = new ArrayList<>();
    if (stock == null) {
      return out;
    }
    qty.forEach(
        (sku, q) -> {
          Stock a = stock.get(sku);
          if (a == null) {
            return;
          }
          if (!a.handlingEnabled()) {
            out.add(new Shortage(sku, "NOT_HANDLED"));
          } else if (a.available() != null && a.available() < q) {
            out.add(
                new Shortage(
                    sku, "requestedQty=" + q + ";availableQty=" + Math.max(a.available(), 0)));
          }
        });
    return out;
  }

  // ---------- 이행 진행(인계·전달, O-1 2026-11-16) ----------

  /**
   * 주문행 진행 누계. 불변식 원수량 + 재발송 = 미이행 + 인계 + 출고 전 취소, 전달 ≤ 원수량 − 출고 전 취소 + 재발송(오배송 재발송 — 2026-11-16).
   */
  public record Progress(
      String orderLineId,
      int originalQty,
      int unfulfilledQty,
      int handedOverQty,
      int cancelledBeforeShipQty,
      int deliveredQty,
      int reshippedQty) {

    /** 보낼(전달할) 수량 = 원수량 − 출고 전 취소 + 재발송. */
    public int deliverable() {
      return originalQty - cancelledBeforeShipQty + reshippedQty;
    }
  }

  /** 진행 순서(뒤로 가지 않는다). 그 밖 상태(CANCELLED·EXPIRED 등)는 진행으로 바꾸지 않는다. */
  public static final List<String> PROGRESS_ORDER =
      List.of(
          "CONFIRMED",
          "PREPARING",
          "PARTIALLY_HANDED_OVER",
          "IN_DELIVERY",
          "PARTIALLY_DELIVERED",
          "COMPLETED");

  /** 한 사건의 줄들(같은 주문행이 LOT 별로 여러 번 올 수 있다)을 주문행별 합으로. 수량 1 미만이면 {@link IllegalArgumentException}. */
  public static java.util.Map<String, Integer> sumByLine(List<Change> changes) {
    java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
    for (Change c : changes) {
      if (c.qty() < 1) {
        throw new IllegalArgumentException("수량은 1 이상이어야 합니다: " + c.orderLineId());
      }
      out.merge(c.orderLineId(), c.qty(), Integer::sum);
    }
    return out;
  }

  /**
   * 포장 인계(PackageHandedOver) 반영: 행마다 미이행 −q, 인계 +q. 없는 행·미이행보다 큰 수량이면 {@link
   * IllegalArgumentException}(아무것도 바꾸지 않는다 — 호출자가 대사 불일치로 남긴다).
   */
  public static List<Progress> handOver(List<Progress> lines, List<Change> changes) {
    java.util.Map<String, Integer> req = sumByLine(changes);
    List<Progress> out = new ArrayList<>();
    for (Progress l : lines) {
      Integer q = req.remove(l.orderLineId());
      if (q == null) {
        out.add(l);
        continue;
      }
      if (q > l.unfulfilledQty()) {
        throw new IllegalArgumentException(
            "주문행 " + l.orderLineId() + " 미이행 " + l.unfulfilledQty() + " 보다 많이 인계됐습니다: " + q);
      }
      out.add(
          new Progress(
              l.orderLineId(),
              l.originalQty(),
              l.unfulfilledQty() - q,
              l.handedOverQty() + q,
              l.cancelledBeforeShipQty(),
              l.deliveredQty(),
              l.reshippedQty()));
    }
    if (!req.isEmpty()) {
      throw new IllegalArgumentException("주문에 없는 주문행: " + req.keySet());
    }
    return out;
  }

  /**
   * 전달 완료(DeliveryCompleted) 반영: 행마다 전달 +q. 인계보다 먼저 와도 받는다(역순 수신 — 실물 사실). 없는 행·(원수량 − 출고 전 취소)를 넘는
   * 전달이면 {@link IllegalArgumentException}.
   */
  public static List<Progress> deliver(List<Progress> lines, List<Change> changes) {
    java.util.Map<String, Integer> req = sumByLine(changes);
    List<Progress> out = new ArrayList<>();
    for (Progress l : lines) {
      Integer q = req.remove(l.orderLineId());
      if (q == null) {
        out.add(l);
        continue;
      }
      int deliverable = l.deliverable();
      if (l.deliveredQty() + q > deliverable) {
        throw new IllegalArgumentException(
            "주문행 "
                + l.orderLineId()
                + " 전달 누계가 보낼 수량 "
                + deliverable
                + " 을 넘습니다: "
                + (l.deliveredQty() + q));
      }
      out.add(
          new Progress(
              l.orderLineId(),
              l.originalQty(),
              l.unfulfilledQty(),
              l.handedOverQty(),
              l.cancelledBeforeShipQty(),
              l.deliveredQty() + q,
              l.reshippedQty()));
    }
    if (!req.isEmpty()) {
      throw new IllegalArgumentException("주문에 없는 주문행: " + req.keySet());
    }
    return out;
  }

  /**
   * 오배송 재발송(RESHIPMENT, 2026-11-16 — ADR-0052 결정 4·5): 행마다 미이행 +q·재발송 +q. 한도 = 원수량 − 출고 전 취소 − 이미
   * 재발송(배송된 만큼만 다시 보낸다). 넘거나 없는 행이면 {@link IllegalArgumentException}. 상태는 바꾸지 않는다(뒤로 가지 않음).
   */
  public static List<Progress> reship(List<Progress> lines, List<Change> changes) {
    java.util.Map<String, Integer> req = sumByLine(changes);
    List<Progress> out = new ArrayList<>();
    for (Progress l : lines) {
      Integer q = req.remove(l.orderLineId());
      if (q == null) {
        out.add(l);
        continue;
      }
      int limit = l.originalQty() - l.cancelledBeforeShipQty() - l.reshippedQty();
      if (q > limit) {
        throw new IllegalArgumentException(
            "주문행 " + l.orderLineId() + " 재발송 한도 " + limit + " 를 넘습니다: " + q);
      }
      out.add(
          new Progress(
              l.orderLineId(),
              l.originalQty(),
              l.unfulfilledQty() + q,
              l.handedOverQty(),
              l.cancelledBeforeShipQty(),
              l.deliveredQty(),
              l.reshippedQty() + q));
    }
    if (!req.isEmpty()) {
      throw new IllegalArgumentException("주문에 없는 주문행: " + req.keySet());
    }
    return out;
  }

  /**
   * 수량으로 본 진행 상태. 모든 행이 (전달 + 출고 전 취소 = 원수량)이고 전달이 하나라도 있으면 COMPLETED(OrderCompleted — "모든 유효 배송 전달
   * 완료 + 미전달분 해소"), 전달이 있으면 PARTIALLY_DELIVERED, 미이행이 모두 0 이고 인계가 있으면 IN_DELIVERY, 인계가 있으면
   * PARTIALLY_HANDED_OVER. 지금 상태보다 뒤로는 가지 않고, 진행 순서 밖 상태(CANCELLED 등)는 그대로 둔다.
   */
  public static String progressStatus(String current, List<Progress> lines) {
    int now = PROGRESS_ORDER.indexOf(current);
    if (now < 0 || lines.isEmpty()) {
      return current;
    }
    boolean anyDelivered = lines.stream().anyMatch(l -> l.deliveredQty() > 0);
    boolean anyHanded = lines.stream().anyMatch(l -> l.handedOverQty() > 0);
    boolean allDelivered = lines.stream().allMatch(l -> l.deliveredQty() == l.deliverable());
    boolean noneUnfulfilled = lines.stream().allMatch(l -> l.unfulfilledQty() == 0);
    String derived;
    if (anyDelivered && allDelivered) {
      derived = "COMPLETED";
    } else if (anyDelivered) {
      derived = "PARTIALLY_DELIVERED";
    } else if (anyHanded && noneUnfulfilled) {
      derived = "IN_DELIVERY";
    } else if (anyHanded) {
      derived = "PARTIALLY_HANDED_OVER";
    } else {
      return current;
    }
    return PROGRESS_ORDER.indexOf(derived) > now ? derived : current;
  }

  /** "HH:mm" → 분(24:00 = 1440). */
  public static int minutes(String hhmm) {
    if ("24:00".equals(hhmm)) {
      return 1440;
    }
    String[] p = hhmm.split(":");
    return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
  }

  /** 토스 orderName 최대 길이(결제 명세 — 100자). */
  public static final int ORDER_NAME_MAX = 100;

  /**
   * 결제창 주문명(BR-35). 품목 순서대로 상품명 사본을 보고, 첫 이름 있는 품목 + "외 N건"(N = 나머지 품목 수). 한 품목이면 이름만. 이름이 하나도
   * 없으면(사본 조회 실패·옛 초안) "PMart 장보기 N개 상품". 100자를 넘으면 상품명을 줄여 "…" 를 붙인다.
   */
  public static String orderName(
      java.util.List<? extends @org.jspecify.annotations.Nullable String> names) {
    int n = names.size();
    String first =
        names.stream()
            .filter(x -> x != null && !x.isBlank())
            .map(String::strip)
            .findFirst()
            .orElse(null);
    if (first == null) {
      return n == 1 ? "PMart 장보기 1개 상품" : "PMart 장보기 " + n + "개 상품";
    }
    String suffix = n > 1 ? " 외 " + (n - 1) + "건" : "";
    int room = ORDER_NAME_MAX - suffix.length();
    if (first.length() > room) {
      first = first.substring(0, room - 1) + "…";
    }
    return first + suffix;
  }
}
