package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.application.Support.CommandResult;
import com.pmart.cdc.outbound.infrastructure.InventoryClient;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferLineRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 승인 TRF 인수(0.3.0) → cdc-inventory 할당 요청. 인수와 할당 결과는 각각 짧은 트랜잭션으로 남기고, 그 사이의 재고 호출은 트랜잭션 밖에서 한다. 같은
 * 본문 재전송은 ALLOCATING 이면 같은 멱등 키로 다시 할당을 부른다(재고는 한 번만 반영). 새 버전 대체는 아직 없다.
 */
@Service
public class TransferService {

  static final Set<String> RETRYABLE = Set.of("RECEIVED", "ALLOCATING");

  public record IntakeLine(String sku, int approvedQty) {}

  public record Intake(
      String trfId,
      int trfVersion,
      String cdcId,
      String mfcId,
      String approvalRef,
      String deadlineMeaning,
      Instant dueAt,
      List<IntakeLine> lines) {}

  public record TransferView(TransferRow transfer, List<TransferLineRow> lines) {}

  private final OutboundStore store;
  private final InventoryClient inventory;
  private final TransactionTemplate tx;
  private final Support support;

  public TransferService(
      OutboundStore store, InventoryClient inventory, TransactionTemplate tx, Support support) {
    this.store = store;
    this.inventory = inventory;
    this.tx = tx;
    this.support = support;
  }

  public CommandResult intake(String requestId, Intake in) {
    validate(in);
    String hash = hash(in);
    TransferRow t = tx.execute(s -> receive(in, hash));
    if (!RETRYABLE.contains(t.state())) {
      return new CommandResult("ACCEPTED", requestId, in.trfId(), t.version(), support.now());
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cdcId", in.cdcId());
    body.put("trfId", in.trfId());
    body.put("trfVersion", in.trfVersion());
    body.put(
        "lines",
        in.lines().stream()
            .map(l -> Map.<String, Object>of("sku", l.sku(), "requestedQty", l.approvedQty()))
            .toList());
    String key = "outbound-alloc-" + in.trfId() + "-v" + in.trfVersion();
    JsonNode res;
    try {
      res = inventory.allocate(key, body);
    } catch (PmartException e) {
      if (unknown(e)) {
        return new CommandResult("ACCEPTED", requestId, in.trfId(), t.version(), support.now());
      }
      Instant at = support.now();
      tx.executeWithoutResult(
          s ->
              store.setAllocation(
                  in.trfId(), "ALLOCATION_FAILED", null, e.code() + ": " + e.getMessage(), at));
      return new CommandResult("ACCEPTED", requestId, in.trfId(), t.version() + 1, at);
    }
    Instant at = support.now();
    String state =
        switch (res.path("state").asString("")) {
          case "ALLOCATED" -> "ALLOCATED";
          case "PARTIAL" -> "PARTIALLY_ALLOCATED";
          default -> "ALLOCATION_FAILED";
        };
    String allocationId = res.path("allocationId").asString(null);
    tx.executeWithoutResult(
        s -> {
          store.setAllocation(in.trfId(), state, allocationId, null, at);
          for (JsonNode l : res.path("lines")) {
            store.setLineAllocated(
                in.trfId(), l.path("sku").asString(), l.path("allocatedQty").asInt(0));
          }
        });
    return new CommandResult("ACCEPTED", requestId, in.trfId(), t.version() + 1, at);
  }

  /** 인수 기록(같은 버전·같은 본문이면 지금 행, 다르면 거절). 다시 할당할 수 있으면 ALLOCATING 으로. */
  private TransferRow receive(Intake in, String hash) {
    Instant at = support.now();
    Optional<TransferRow> cur = store.transfer(in.trfId(), true);
    if (cur.isEmpty()) {
      TransferRow row =
          new TransferRow(
              in.trfId(),
              in.trfVersion(),
              in.cdcId(),
              in.mfcId(),
              in.approvalRef(),
              in.deadlineMeaning(),
              in.dueAt(),
              hash,
              "ALLOCATING",
              null,
              null,
              null,
              1,
              at,
              at);
      List<TransferLineRow> lines = new ArrayList<>();
      for (int i = 0; i < in.lines().size(); i++) {
        IntakeLine l = in.lines().get(i);
        lines.add(new TransferLineRow(l.sku(), i, l.approvedQty(), 0, 0, 0));
      }
      store.insertTransfer(row, lines);
      return row;
    }
    TransferRow t = cur.get();
    if (in.trfVersion() < t.trfVersion()) {
      throw PmartException.invalidState(
          "TRF " + in.trfId() + " 는 이미 v" + t.trfVersion() + " 입니다(받은 v" + in.trfVersion() + ").");
    }
    if (in.trfVersion() > t.trfVersion()) {
      throw new PmartException(
          "TRANSFER_CHANGE_NOT_SUPPORTED",
          HttpStatus.CONFLICT,
          "TRF 새 버전으로 대체(할당 해제·재할당)는 아직 없습니다(0.3.0).",
          false,
          List.of());
    }
    if (!t.bodyHash().equals(hash)) {
      throw new PmartException(
          "TRANSFER_INTAKE_CONFLICT",
          HttpStatus.CONFLICT,
          "같은 TRF·버전에 다른 본문입니다.",
          false,
          List.of());
    }
    return t;
  }

  public TransferView get(String trfId) {
    TransferRow t =
        store
            .transfer(trfId, false)
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "TRF " + trfId + " 가 없습니다."));
    return new TransferView(t, store.transferLines(trfId));
  }

  static boolean unknown(PmartException e) {
    return e.status().is5xxServerError()
        || "UNKNOWN_RESULT".equals(e.code())
        || ErrorCode.DEPENDENCY_UNAVAILABLE.name().equals(e.code());
  }

  static void validate(Intake in) {
    List<PmartException.Detail> bad = new ArrayList<>();
    Set<String> skus = new HashSet<>();
    for (int i = 0; i < in.lines().size(); i++) {
      if (!skus.add(in.lines().get(i).sku())) {
        bad.add(new PmartException.Detail("lines[" + i + "].sku", "DUPLICATE_SKU"));
      }
    }
    if (in.lines().isEmpty() || !bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "TRF 줄이 비었거나 같은 SKU 가 두 번 있습니다.", bad);
    }
  }

  /** 본문 비교용(줄 순서 포함). */
  static String hash(Intake in) {
    StringBuilder sb = new StringBuilder();
    sb.append(in.trfId())
        .append('|')
        .append(in.trfVersion())
        .append('|')
        .append(in.cdcId())
        .append('|')
        .append(in.mfcId())
        .append('|')
        .append(in.approvalRef())
        .append('|')
        .append(in.deadlineMeaning())
        .append('|')
        .append(in.dueAt());
    for (IntakeLine l : in.lines()) {
      sb.append('|').append(l.sku()).append(':').append(l.approvedQty());
    }
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
