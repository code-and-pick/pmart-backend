package com.pmart.rider.api;

import com.pmart.rider.api.generated.StaffActionsApi;
import com.pmart.rider.api.generated.model.ListRiderStaffActions200Response;
import com.pmart.rider.api.generated.model.ListRiderStaffActions200ResponseItemsInner;
import com.pmart.rider.application.StaffActions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 직원 조치 이력(2026-11-18) — 업무 규칙은 {@link StaffActions}. */
@RestController
public class StaffActionsController implements StaffActionsApi {

  private final StaffActions actions;

  public StaffActionsController(StaffActions actions) {
    this.actions = actions;
  }

  @Override
  public ResponseEntity<ListRiderStaffActions200Response> listRiderStaffActions(
      @Nullable String operatorId,
      @Nullable String riderId,
      @Nullable String deliveryId,
      @Nullable OffsetDateTime from,
      @Nullable OffsetDateTime to,
      Integer size,
      @Nullable String cursor) {
    int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
    var rows =
        actions.list(
            blank(operatorId),
            blank(riderId),
            blank(deliveryId),
            from == null ? null : from.toInstant(),
            to == null ? null : to.toInstant(),
            size + 1,
            offset);
    boolean more = rows.size() > size;
    var page = more ? rows.subList(0, size) : rows;
    return ResponseEntity.ok(
        new ListRiderStaffActions200Response()
            .items(
                page.stream()
                    .map(
                        r ->
                            new ListRiderStaffActions200ResponseItemsInner()
                                .at(r.at().atOffset(ZoneOffset.UTC))
                                .actorId(r.actorId())
                                .action(r.action())
                                .riderId(r.riderId())
                                .deliveryId(r.deliveryId())
                                .detail(r.detail())
                                .reason(r.reason()))
                    .toList())
            .nextCursor(more ? String.valueOf(offset + size) : null));
  }

  private static @Nullable String blank(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
