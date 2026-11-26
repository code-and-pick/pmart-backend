package com.pmart.cdc.inventory.application;

import com.pmart.cdc.inventory.infrastructure.LedgerStore;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.CommitRow;
import com.pmart.cdc.inventory.infrastructure.LedgerStore.Delta;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** 전기 명령 공통: 원결과 기록, 멱등 키, 판정일(Asia/Seoul), 발행 토픽. */
@Component
public class Ledger {

  /** cdc-inventory 발행 토픽(TransferDeparted·AllocationResult). */
  public static final String TOPIC = "cdc.inventory.v1";

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /** 명령 결과(멱등 재생용 — JSON 왕복). */
  public record CommandResult(
      String requestId, String resultId, int appliedQty, int version, Instant occurredAt) {}

  private final LedgerStore store;
  private final Clock clock;

  public Ledger(LedgerStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  Instant now() {
    return Instant.now(clock);
  }

  LocalDate today() {
    return LocalDate.now(clock.withZone(SEOUL));
  }

  static String newId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  /** 서비스 간 호출의 멱등 키. 행위자는 주체(토큰 없는 내부 호출은 X-Source-System). */
  static RequestKey key(String sourceSystem, String requestId, String operation) {
    String actor =
        PmartPrincipal.current().map(PmartPrincipal::subject).orElse(RequestIdempotency.ANONYMOUS);
    return new RequestKey(sourceSystem, requestId, operation, actor);
  }

  String record(
      String requestId,
      String kind,
      String sourceSystem,
      @Nullable String sourceRef,
      String cdcId,
      Instant at,
      List<Delta> deltas) {
    String commitId = newId("CMT");
    store.insertCommit(
        new CommitRow(
            commitId,
            requestId,
            kind,
            "APPLIED",
            sourceSystem,
            sourceRef,
            cdcId,
            at,
            PmartPrincipal.current().map(PmartPrincipal::subject).orElse(null)),
        deltas);
    return commitId;
  }
}
