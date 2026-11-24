package com.pmart.cdc.inventory.api;

import com.pmart.cdc.inventory.api.generated.model.ListTransits200ResponseAsOf;
import com.pmart.cdc.inventory.api.generated.model.ReleaseAllocation200Response;
import com.pmart.cdc.inventory.application.Ledger.CommandResult;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 응답 변환 공통. */
final class Dto {

  private Dto() {}

  static @Nullable OffsetDateTime time(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }

  static ListTransits200ResponseAsOf asOf(Instant at) {
    var a = new ListTransits200ResponseAsOf();
    a.setAsOf(time(at));
    a.setFreshness(ListTransits200ResponseAsOf.FreshnessEnum.CURRENT);
    return a;
  }

  static ReleaseAllocation200Response command(Outcome<CommandResult> o) {
    CommandResult r = o.value();
    var out = new ReleaseAllocation200Response();
    out.setStatus(ReleaseAllocation200Response.StatusEnum.APPLIED);
    out.setRequestId(r.requestId());
    out.setResultId(r.resultId());
    out.setAppliedQty(r.appliedQty());
    if (r.version() > 0) {
      out.setVersion(r.version());
    }
    out.setOccurredAt(time(r.occurredAt()));
    return out;
  }
}
