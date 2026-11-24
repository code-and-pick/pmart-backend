package com.pmart.promotion.application;

import com.pmart.promotion.domain.PromotionRules;
import com.pmart.promotion.infrastructure.PromotionStore;
import com.pmart.promotion.infrastructure.PromotionStore.Candidate;
import com.pmart.promotion.infrastructure.PromotionStore.PromotionRow;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * SKU 별 기간 할인(ADR-0082 결정 2) — 걸린 행사 중 할인액이 가장 큰 1개(같으면 먼저 끝나는 행사, 그다음 행사 ID). pricing 견적·화면 표시가
 * 부른다. 카테고리 행사는 상품 사본(SKU → 카테고리, 상위 포함)으로, 테마 행사(0.3.0)는 받아 둔 진열 테마 사본(상품 → 그 상품의 모든 SKU)으로 판정.
 */
@Service
public class PriceDiscounts {

  public record Item(String sku, long unitPrice) {}

  public record Result(
      String sku, PromotionRow promotion, long unitPrice, long unitDiscount, int ratePercent) {}

  private final PromotionStore store;
  private final Clock clock;

  public PriceDiscounts(PromotionStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public Instant asOf(@Nullable Instant at) {
    return at == null ? clock.instant() : at;
  }

  public List<Result> lookup(List<Item> items, Instant at) {
    Set<String> skus = new LinkedHashSet<>();
    items.forEach(i -> skus.add(i.sku()));
    List<Candidate> candidates = store.priceCandidates(skus, at);
    if (candidates.isEmpty()) {
      return List.of();
    }
    Map<String, List<String>> cats = store.categoriesOf(skus);
    Map<String, List<String>> themes = store.themesOf(skus);
    List<Result> out = new ArrayList<>();
    for (Item item : items) {
      List<String> mine = cats.getOrDefault(item.sku(), List.of());
      List<String> myThemes = themes.getOrDefault(item.sku(), List.of());
      Result best = null;
      for (Candidate c : candidates) {
        boolean hit =
            ("SKU".equals(c.targetType()) && c.targetId().equals(item.sku()))
                || ("CATEGORY".equals(c.targetType()) && mine.contains(c.targetId()))
                || ("THEME".equals(c.targetType()) && myThemes.contains(c.targetId()));
        if (!hit) {
          continue;
        }
        PromotionRow p = c.promotion();
        long d = PromotionRules.unitDiscount(PromotionInputs.discount(p), item.unitPrice());
        if (d <= 0) {
          continue;
        }
        if (best == null || better(d, p, best)) {
          best =
              new Result(
                  item.sku(),
                  p,
                  item.unitPrice(),
                  d,
                  PromotionRules.ratePercent(item.unitPrice(), d));
        }
      }
      if (best != null) {
        out.add(best);
      }
    }
    return out;
  }

  private static boolean better(long d, PromotionRow p, Result best) {
    if (d != best.unitDiscount()) {
      return d > best.unitDiscount();
    }
    int byEnd = p.endsAt().compareTo(best.promotion().endsAt());
    if (byEnd != 0) {
      return byEnd < 0;
    }
    return p.promotionId().compareTo(best.promotion().promotionId()) < 0;
  }
}
