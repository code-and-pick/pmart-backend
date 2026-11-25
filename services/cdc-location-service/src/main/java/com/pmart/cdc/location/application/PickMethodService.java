package com.pmart.cdc.location.application;

import com.pmart.cdc.location.infrastructure.AislePickMethodStore;
import com.pmart.cdc.location.infrastructure.AislePickMethodStore.Aisle;
import com.pmart.cdc.location.infrastructure.AislePickMethodStore.Row;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 피킹 선반 통로별 피킹 방식 설정값(0.5.0) — 묶음 피킹 설계 §7 결정 3(사용자 "선반 통로 설정값"). 상품 분류 글자로 정하지 않는다(WMS CDC-F-017).
 * 미설정은 미설정으로 둔다 — 기본값으로 채우지 않는다. 사건 없음(cdc-outbound 가 Wave 릴리스 때 일괄 조회로 읽는다).
 */
@Service
public class PickMethodService {

  public static final int MAX_ITEMS = 500;
  static final String OP_SET = "cdcLocation:pickMethods";
  static final Set<String> METHODS = Set.of("DPS", "BOX_PICK", "FC", "DISTRIBUTION", "LINE");

  /** pickMethod null = 해제. */
  public record Item(String area, String floor, String aisle, @Nullable String pickMethod) {}

  public record Change(@Nullable String reason, List<Item> items) {}

  public record SetResult(String requestId, int set, int cleared, Instant at) {}

  private final AislePickMethodStore store;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public PickMethodService(
      AislePickMethodStore store, RequestIdempotency idempotency, Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<Row> list(String cdcId, @Nullable String area, @Nullable String floor) {
    Operators.requireCenter(cdcId);
    return store.list(cdcId, area, floor);
  }

  /** 일괄 조회용 — 위치 코드 → 피킹 방식(설정 있는 PICK_FACE 만). */
  @Transactional(readOnly = true)
  public Map<String, String> ofLocations(String centerId, List<String> codes) {
    return store.ofLocations(centerId, new java.util.LinkedHashSet<>(codes));
  }

  public Outcome<SetResult> set(String requestId, String sourceSystem, String cdcId, Change c) {
    String by = Operators.requireCenter(cdcId);
    if (c.items().isEmpty() || c.items().size() > MAX_ITEMS) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "items 는 1~" + MAX_ITEMS + "행이어야 합니다.");
    }
    Set<Aisle> seen = new HashSet<>();
    for (Item i : c.items()) {
      if (i.pickMethod() != null && !METHODS.contains(i.pickMethod())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED, "알 수 없는 pickMethod " + i.pickMethod());
      }
      if (!seen.add(aisle(i))) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "같은 통로가 두 번 있습니다: " + name(i),
            List.of(new PmartException.Detail("items", "DUPLICATE_AISLE")));
      }
    }
    return idempotency.execute(
        new RequestKey(sourceSystem, requestId, OP_SET, by),
        new Object[] {cdcId, c},
        SetResult.class,
        () -> {
          Set<Aisle> shelves = new HashSet<>(store.pickFaceAisles(cdcId));
          List<PmartException.Detail> bad = new ArrayList<>();
          for (Item i : c.items()) {
            if (i.pickMethod() != null && !shelves.contains(aisle(i))) {
              bad.add(new PmartException.Detail(name(i), "NOT_PICK_FACE_AISLE"));
            }
          }
          if (!bad.isEmpty()) {
            throw new PmartException(
                ErrorCode.VALIDATION_FAILED, "피킹 선반(PICK_FACE)이 없는 통로입니다 — 선반을 먼저 등록하세요.", bad);
          }
          Instant at = Instant.now(clock);
          int set = 0;
          int cleared = 0;
          for (Item i : c.items()) {
            if (i.pickMethod() == null) {
              cleared += store.delete(cdcId, aisle(i));
            } else {
              store.upsert(cdcId, aisle(i), i.pickMethod(), c.reason(), by, requestId, at);
              set++;
            }
          }
          return new SetResult(requestId, set, cleared, at);
        });
  }

  private static Aisle aisle(Item i) {
    return new Aisle(i.area(), i.floor(), i.aisle());
  }

  private static String name(Item i) {
    return i.area() + "-" + i.floor() + "-" + i.aisle();
  }
}
