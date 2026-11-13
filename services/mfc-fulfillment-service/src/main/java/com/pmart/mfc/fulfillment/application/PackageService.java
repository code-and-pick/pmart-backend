package com.pmart.mfc.fulfillment.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.fulfillment.domain.TaskRules;
import com.pmart.mfc.fulfillment.domain.TaskRules.Content;
import com.pmart.mfc.fulfillment.domain.TaskRules.ContentRequest;
import com.pmart.mfc.fulfillment.domain.TaskRules.PickedPart;
import com.pmart.mfc.fulfillment.infrastructure.InventoryClient;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.LineView;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.PackageRow;
import com.pmart.mfc.fulfillment.infrastructure.TaskStore.TaskInfo;
import com.pmart.mfc.fulfillment.infrastructure.WorkOrderStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 포장 밀봉(WK-07, 슬라이스 6b). PACK 작업을 시작한 작업자가 출고의 집품분을 포장 단위로 밀봉한다.
 *
 * <ol>
 *   <li>LOT 은 요청에 없으면 집품했지만 아직 포장하지 않은 예약 조각에서 소비기한 빠른 순으로(inventory 피킹 지시, 트랜잭션 밖).
 *   <li>접수(OPEN) → mfc-inventory PACK(PICKED → PACKED, 실행 ID = 포장 ID) → SEALED + QR. 재고 거절이면
 *       VOIDED, 결과 모름이면 OPEN 그대로 503(같은 키로 다시 → 같은 포장).
 *   <li>출고의 모든 집품분이 포장되면 PACK COMPLETED, 출고 READY, PackingFinished·ShipmentReady(배차 요청 정보).
 * </ol>
 */
@Service
public class PackageService {

  private static final Logger log = LoggerFactory.getLogger(PackageService.class);
  private static final TypeReference<List<Content>> CONTENTS = new TypeReference<>() {};

  public record SealCommand(
      List<ContentRequest> contents,
      int bagCount,
      boolean containsAlcohol,
      @Nullable String temperatureZone,
      @Nullable Integer weightG,
      @Nullable Integer volumeCm3,
      @Nullable String packingLocation) {}

  /** 출고 조회. */
  public record ShipmentView(
      WorkOrderStore.ShipmentRow shipment,
      String orderId,
      @Nullable Instant readyAt,
      List<PackageRow> packages) {}

  private final TaskStore store;
  private final WorkOrderStore works;
  private final InventoryClient inventory;
  private final FulfillmentEvents events;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PackageService(
      TaskStore store,
      WorkOrderStore works,
      InventoryClient inventory,
      FulfillmentEvents events,
      ObjectMapper mapper,
      PlatformTransactionManager txManager,
      Clock clock) {
    this.store = store;
    this.works = works;
    this.inventory = inventory;
    this.events = events;
    this.mapper = mapper;
    this.tx = new TransactionTemplate(txManager);
    this.clock = clock;
  }

  public PackageRow seal(String shipmentId, String requestId, SealCommand cmd) {
    TaskInfo pack0 = packTask(shipmentId);
    String me = TaskService.requireWorker(pack0.mfcId());
    var existing = store.findPackage(shipmentId, requestId);
    if (existing.isPresent() && !"OPEN".equals(existing.get().state())) {
      return finished(existing.get());
    }
    PackageRow open = existing.orElse(null);
    if (open == null) {
      // LOT 배정(트랜잭션 밖 조회): 집품 − 포장 > 0 인 예약 조각
      List<PickedPart> parts = new ArrayList<>();
      for (InventoryClient.PlanPart p : inventory.pickPlan(pack0.reservationId())) {
        int room = p.pickedQty() - p.packedQty();
        if (p.orderLineId() != null && room > 0) {
          parts.add(new PickedPart(p.orderLineId(), p.sku(), p.lotId(), p.expiryDate(), room));
        }
      }
      TaskRules.Packing packing = TaskRules.allocatePacking(cmd.contents(), parts);
      if (packing.error() != null) {
        throw TaskService.conflict("CONTENT_MISMATCH", packing.error());
      }
      open =
          Objects.requireNonNull(
              tx.execute(s -> record(shipmentId, requestId, me, cmd, packing.contents())));
      if (!"OPEN".equals(open.state())) {
        return finished(open);
      }
    }
    return post(open, pack0);
  }

  private PackageRow record(
      String shipmentId, String requestId, String me, SealCommand cmd, List<Content> contents) {
    var again = store.findPackage(shipmentId, requestId);
    if (again.isPresent()) {
      return again.get();
    }
    TaskInfo pack = store.lock(packTask(shipmentId).taskId()).orElseThrow();
    if (!"IN_PROGRESS".equals(pack.state())) {
      throw TaskService.conflict(
          TaskRules.INVALID_STATE, "포장 작업은 " + pack.state() + " 상태입니다(시작한 포장 작업만 밀봉).");
    }
    if (!me.equals(pack.startedBy())) {
      throw TaskService.conflict(TaskRules.NOT_ASSIGNEE, "이 포장 작업을 시작한 작업자가 아닙니다.");
    }
    // 남은 포장량 = 집품 완료 − 포장 완료 − 열린 포장
    Map<String, Integer> picked = new HashMap<>();
    store
        .sibling(shipmentId, "PICK")
        .ifPresent(
            p ->
                store
                    .lines(p.taskId())
                    .forEach(l -> picked.put(l.orderLineId(), l.completedQty())));
    Map<String, Integer> open = store.openPackageQty(shipmentId);
    Map<String, Integer> room = new HashMap<>();
    for (LineView l : store.lines(pack.taskId())) {
      room.put(
          l.orderLineId(),
          picked.getOrDefault(l.orderLineId(), 0)
              - l.completedQty()
              - open.getOrDefault(l.orderLineId(), 0));
    }
    Map<String, Integer> want = new LinkedHashMap<>();
    contents.forEach(c -> want.merge(c.orderLineId(), c.qty(), Integer::sum));
    for (var e : want.entrySet()) {
      int left = room.getOrDefault(e.getKey(), 0);
      if (e.getValue() > left) {
        throw TaskService.conflict(
            "CONTENT_MISMATCH",
            "주문 줄 " + e.getKey() + ": 포장 " + e.getValue() + " > 포장할 수 있는 " + left);
      }
    }
    String location = cmd.packingLocation();
    if (location == null || location.isBlank()) {
      List<String> locs = store.packingLocations(shipmentId);
      if (locs.size() != 1) {
        throw TaskService.invalid("packingLocation 이 필요합니다(집품분 포장 위치 " + locs + ").");
      }
      location = locs.get(0);
    }
    Instant now = clock.instant();
    PackageRow r =
        new PackageRow(
            "PKG-" + UUID.randomUUID(),
            shipmentId,
            requestId,
            1,
            "OPEN",
            null,
            mapper.writeValueAsString(contents),
            cmd.bagCount(),
            cmd.containsAlcohol(),
            cmd.temperatureZone(),
            cmd.weightG(),
            cmd.volumeCm3(),
            me,
            location,
            null,
            null,
            1,
            now,
            null);
    store.insertPackage(r);
    return r;
  }

  private PackageRow post(PackageRow open, TaskInfo pack0) {
    List<Content> contents = mapper.readValue(open.contents(), CONTENTS);
    List<Map<String, Object>> lines = new ArrayList<>();
    for (Content c : contents) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("sku", c.sku());
      m.put("lotId", c.lotId());
      m.put("orderLineId", c.orderLineId());
      m.put("qty", c.qty());
      lines.add(m);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("executionType", "PACK");
    body.put("mfcId", pack0.mfcId());
    body.put("taskId", pack0.taskId());
    body.put("executionId", open.packageId());
    body.put("reservationId", pack0.reservationId());
    body.put("fromLocation", open.packingLocation());
    body.put("lines", lines);
    InventoryClient.Commit c = inventory.commit(open.packageId(), body);
    PackageRow after = Objects.requireNonNull(tx.execute(s -> apply(open, contents, c)));
    return finished(after);
  }

  private PackageRow apply(PackageRow open, List<Content> contents, InventoryClient.Commit c) {
    TaskInfo pack = store.lock(packTask(open.shipmentId()).taskId()).orElseThrow();
    Instant now = clock.instant();
    switch (c.outcome()) {
      case "APPLIED" -> {
        String qr = "PKG:" + open.packageId() + ":" + open.packageVersion();
        if (store.finishPackage(open.packageId(), "SEALED", qr, c.commitId(), null, now) == 1) {
          contents.forEach(x -> store.addCompleted(pack.taskId(), x.orderLineId(), x.qty()));
          log.info("포장 {} 밀봉 ({} 줄)", open.packageId(), contents.size());
          if (store.lines(pack.taskId()).stream().allMatch(l -> l.remaining() == 0)) {
            store.setState(pack.taskId(), "COMPLETED", null, false, now);
            events.taskProgressed(
                pack, "COMPLETED", pack.state(), pack.version() + 1, open.packedBy(), null, now);
            store.shipmentReady(open.shipmentId(), now);
            int shipmentVersion =
                works.shipments(pack.fulfillmentId()).stream()
                    .filter(s -> s.shipmentId().equals(open.shipmentId()))
                    .findFirst()
                    .map(WorkOrderStore.ShipmentRow::version)
                    .orElse(1);
            List<PackageRow> sealed =
                store.packages(open.shipmentId()).stream()
                    .filter(p -> "SEALED".equals(p.state()))
                    .toList();
            events.packingFinishedAndReady(pack, sealed, shipmentVersion, now);
            log.info("출고 {} 포장 완료 — READY", open.shipmentId());
          }
        }
      }
      case "REJECTED" ->
          store.finishPackage(
              open.packageId(),
              "VOIDED",
              null,
              null,
              c.code() + (c.detail() == null ? "" : ": " + c.detail()),
              now);
      default -> {
        // UNKNOWN·NOT_DELIVERED: OPEN 그대로 — 같은 키로 다시 보내면 같은 포장 ID 로 재고에 다시
      }
    }
    return store.pkg(open.packageId()).orElseThrow();
  }

  /** 끝난 포장: SEALED 면 그대로, VOIDED 면 재고 거절을 오류로, OPEN 이면 결과 모름(503). */
  private PackageRow finished(PackageRow p) {
    if ("VOIDED".equals(p.state())) {
      throw new PmartException(
          "PACK_REJECTED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "재고가 포장 반영을 거절했습니다: " + p.rejectReason(),
          false,
          List.of());
    }
    if ("OPEN".equals(p.state())) {
      throw new PmartException(
          ErrorCode.UNKNOWN_RESULT.name(),
          ErrorCode.UNKNOWN_RESULT.status(),
          "포장 반영 결과를 아직 모릅니다. 같은 Idempotency-Key 로 다시 보내 주세요.",
          true,
          List.of());
    }
    return p;
  }

  // ---------- 조회 ----------

  public ShipmentView shipment(String shipmentId) {
    TaskInfo pack = packTask(shipmentId);
    TaskService.requireReader(pack.mfcId());
    WorkOrderStore.ShipmentRow s =
        works.shipments(pack.fulfillmentId()).stream()
            .filter(x -> x.shipmentId().equals(shipmentId))
            .findFirst()
            .orElseThrow();
    return new ShipmentView(
        s, pack.orderId(), store.shipmentReadyAt(shipmentId), store.packages(shipmentId));
  }

  public PackageRow pkg(String packageId) {
    PackageRow p =
        store
            .pkg(packageId)
            .orElseThrow(
                () -> PmartException.notFound("PACKAGE_NOT_FOUND", "포장이 없습니다: " + packageId));
    TaskService.requireReader(packTask(p.shipmentId()).mfcId());
    return p;
  }

  public String orderIdOf(String shipmentId) {
    return packTask(shipmentId).orderId();
  }

  private TaskInfo packTask(String shipmentId) {
    return store
        .sibling(shipmentId, "PACK")
        .orElseThrow(
            () -> PmartException.notFound("SHIPMENT_NOT_FOUND", "출고가 없습니다: " + shipmentId));
  }
}
