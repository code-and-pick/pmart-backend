package com.pmart.delivery.execution.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.api.generated.MeApi;
import com.pmart.delivery.execution.api.generated.model.ExecutionState;
import com.pmart.delivery.execution.api.generated.model.ListMyDeliveries200Response;
import com.pmart.delivery.execution.api.generated.model.ListMyDeliveries200ResponsePage;
import com.pmart.delivery.execution.api.generated.model.ListMyEarnings200Response;
import com.pmart.delivery.execution.api.generated.model.ListMyEarnings200ResponseAsOf;
import com.pmart.delivery.execution.api.generated.model.ListMyEarnings200ResponseTotals;
import com.pmart.delivery.execution.api.generated.model.SettlementState;
import com.pmart.delivery.execution.application.EarningService;
import com.pmart.delivery.execution.application.ExecutionService;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MeApi — 내 배송(0.2.0), 수행료·정산(0.3.0). */
@RestController
public class MeController implements MeApi {

  private final ExecutionService service;
  private final EarningService earnings;
  private final ExecutionViews views;

  private final com.pmart.delivery.execution.application.OperatingTimeService operating;

  public MeController(
      ExecutionService service,
      EarningService earnings,
      ExecutionViews views,
      com.pmart.delivery.execution.application.OperatingTimeService operating) {
    this.operating = operating;
    this.service = service;
    this.earnings = earnings;
    this.views = views;
  }

  /** 수행료·정산(0.3.0) — 수행일(Asia/Seoul) 기준 기간, 합계는 산정 가능한 것만. 지급(PAID)은 외부 회계 연결 뒤. */
  @Override
  public ResponseEntity<ListMyEarnings200Response> listMyEarnings(
      @Nullable LocalDate from,
      @Nullable LocalDate to,
      @Nullable SettlementState settlementState,
      Integer size,
      @Nullable String cursor) {
    int offset = 0;
    if (cursor != null && !cursor.isBlank()) {
      try {
        offset = Math.max(0, Integer.parseInt(cursor));
      } catch (NumberFormatException e) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
      }
    }
    var rows =
        earnings.mine(
            from,
            to,
            settlementState == null ? null : settlementState.getValue(),
            size + 1,
            offset);
    boolean more = rows.size() > size;
    var page = more ? rows.subList(0, size) : rows;
    var t = earnings.totals(from, to);
    return ResponseEntity.ok(
        new ListMyEarnings200Response()
            .items(page.stream().map(ExecutionViews::earning).toList())
            .totals(
                new ListMyEarnings200ResponseTotals()
                    .estimated((int) t.estimated())
                    .confirmed((int) t.confirmed())
                    .paid((int) t.paid()))
            .page(
                new ListMyDeliveries200ResponsePage()
                    .nextCursor(more ? String.valueOf(offset + size) : null)
                    .size(page.size()))
            .asOf(
                new ListMyEarnings200ResponseAsOf()
                    .asOf(ExecutionViews.time(earnings.now()))
                    .freshness(ListMyEarnings200ResponseAsOf.FreshnessEnum.CURRENT)));
  }

  @Override
  public ResponseEntity<ListMyDeliveries200Response> listMyDeliveries(
      @Nullable LocalDate date,
      @Nullable ExecutionState state,
      Integer size,
      @Nullable String cursor) {
    var items = service.mine(date, state == null ? null : state.getValue(), size + 1, cursor);
    boolean more = items.size() > size;
    var page = more ? items.subList(0, size) : items;
    int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
    return ResponseEntity.ok(
        new ListMyDeliveries200Response()
            .items(page.stream().map(views::delivery).toList())
            .page(
                new ListMyDeliveries200ResponsePage()
                    .nextCursor(more ? String.valueOf(offset + size) : null)
                    .size(page.size())));
  }

  /** 내 운행시간(2026-11-17 — 배차 수락 ~ 전달 완료, 겹치면 한 번, 날짜별). */
  @Override
  public ResponseEntity<com.pmart.delivery.execution.api.generated.model.OperatingTime>
      getMyOperatingTime(@Nullable LocalDate from, @Nullable LocalDate to) {
    var r = operating.mine(from, to);
    return ResponseEntity.ok(
        new com.pmart.delivery.execution.api.generated.model.OperatingTime()
            .from(r.from())
            .to(r.to())
            .totalSeconds(r.totalSeconds())
            .days(
                r.days().entrySet().stream()
                    .map(
                        e ->
                            new com.pmart.delivery.execution.api.generated.model.OperatingTimeDay()
                                .date(e.getKey())
                                .seconds(e.getValue()))
                    .toList()));
  }
}
