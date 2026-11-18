package com.pmart.notification.api;

import com.pmart.notification.api.generated.RiderNoticesApi;
import com.pmart.notification.api.generated.model.ListRiderNotices200Response;
import com.pmart.notification.api.generated.model.RiderNotice;
import com.pmart.notification.api.generated.model.SendRiderNoticeRequest;
import com.pmart.notification.application.RiderNoticeService;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 라이더 공지(0.5.0, 2026-11-18) — 업무 규칙은 {@link RiderNoticeService}. */
@RestController
public class RiderNoticesController implements RiderNoticesApi {

  private final RiderNoticeService notices;

  public RiderNoticesController(RiderNoticeService notices) {
    this.notices = notices;
  }

  @Override
  public ResponseEntity<RiderNotice> sendRiderNotice(
      String idempotencyKey, String xSourceSystem, SendRiderNoticeRequest req) {
    var s =
        notices.send(
            idempotencyKey,
            req.getTitle(),
            req.getBody(),
            req.getTarget().getType().getValue(),
            req.getTarget().getMfcId());
    return ResponseEntity.ok(view(s));
  }

  @Override
  public ResponseEntity<ListRiderNotices200Response> listRiderNotices(
      Integer size, @Nullable String cursor) {
    int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
    var rows = notices.list(size + 1, offset);
    boolean more = rows.size() > size;
    var page = more ? rows.subList(0, size) : rows;
    return ResponseEntity.ok(
        new ListRiderNotices200Response()
            .items(page.stream().map(RiderNoticesController::view).toList())
            .nextCursor(more ? String.valueOf(offset + size) : null));
  }

  private static RiderNotice view(RiderNoticeService.Sent s) {
    return new RiderNotice()
        .noticeId(s.noticeId())
        .title(s.title())
        .body(s.body())
        .targetType(RiderNotice.TargetTypeEnum.fromValue(s.targetType()))
        .mfcId(s.mfcId())
        .sentBy(s.sentBy())
        .sentAt(s.sentAt().atOffset(ZoneOffset.UTC))
        .recipientCount(s.recipientCount());
  }
}
