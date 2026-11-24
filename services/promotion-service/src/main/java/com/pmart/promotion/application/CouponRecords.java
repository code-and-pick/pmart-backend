package com.pmart.promotion.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.promotion.infrastructure.CouponRecordStore;
import com.pmart.promotion.infrastructure.CouponRecordStore.RecordRow;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 쿠폰 발급 기록 조회(0.3.1 — PRM-1 고객 상세 "쿠폰" + 행사 "발급 내역", 사용자 "쿠폰 발급 기록 조회"·"만들어"). 보기 역할은 행사 보기와 같다
 * (MARKETING·OPERATOR_ADMIN·CS_AGENT). 고객 이름·연락처는 다루지 않는다.
 */
@Service
public class CouponRecords {

  public record Page(List<RecordRow> items, @Nullable String nextCursor, Instant asOf) {}

  private final CouponRecordStore store;
  private final Clock clock;

  public CouponRecords(CouponRecordStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public Page list(
      @Nullable String customerId,
      @Nullable String promotionId,
      @Nullable String status,
      @Nullable String source,
      int size,
      @Nullable String cursor) {
    PromotionService.require(PromotionService.READERS, "쿠폰 발급 기록 보기");
    if (blank(customerId) && blank(promotionId)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "customerId 또는 promotionId 가 필요합니다.");
    }
    Instant afterAt = null;
    String afterId = null;
    if (cursor != null && !cursor.isBlank()) {
      try {
        String s = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int bar = s.indexOf('|');
        afterAt = Instant.parse(s.substring(0, bar));
        afterId = s.substring(bar + 1);
      } catch (RuntimeException e) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다.");
      }
    }
    int limit = Math.max(1, Math.min(size, 100));
    Instant now = clock.instant();
    List<RecordRow> rows =
        store.page(
            blank(customerId) ? null : customerId,
            blank(promotionId) ? null : promotionId,
            status,
            source,
            afterAt,
            afterId,
            now,
            limit);
    String next = null;
    if (rows.size() > limit) {
      rows = rows.subList(0, limit);
      RecordRow last = rows.get(limit - 1);
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (last.issuedAt() + "|" + last.couponId()).getBytes(StandardCharsets.UTF_8));
    }
    return new Page(rows, next, now);
  }

  private static boolean blank(@Nullable String s) {
    return s == null || s.isBlank();
  }
}
