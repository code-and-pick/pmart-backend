package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.domain.CapacityHold;
import com.pmart.mfc.capacity.domain.CapacityHold.State;
import com.pmart.mfc.capacity.domain.CapacityRules;
import com.pmart.mfc.capacity.domain.CapacityRules.HourWindow;
import com.pmart.mfc.capacity.domain.RestrictionRules;
import com.pmart.mfc.capacity.infrastructure.HoldStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * CapacityHold 명령(S1 c, 확정 i, 보상 해제). 모든 명령은 {@link RequestIdempotency} 안에서 한 트랜잭션으로 처리한다.
 *
 * <ul>
 *   <li>Hold: 창 행을 FOR UPDATE 로 잡고 활성 점유를 센 뒤 들어갈 때만 HELD 로 만든다 — 마지막 1단위에 동시 요청 둘이면 하나만 성공.
 *   <li>업무 거절(CAPACITY_EXCEEDED·CONFIG_REQUIRED·HOLD_ALREADY_ACTIVE)은 예외로 돌려주므로 멱등 기록에 남지 않는다. 같은
 *       키로 다시 보내면 그때의 여력으로 다시 판정한다(사가는 거절이면 앞 단계를 보상한다).
 *   <li>운영 제한(INTAKE_STOP)은 아직 구현 전이라 Hold 를 막는 활성 제한은 없다.
 * </ul>
 */
@Service
public class HoldService {

  /** 주문당 활성 Hold 가 이미 있음. */
  public static final String HOLD_ALREADY_ACTIVE = "HOLD_ALREADY_ACTIVE";

  public static final String HOLD_NOT_FOUND = "HOLD_NOT_FOUND";

  private static final Logger log = LoggerFactory.getLogger(HoldService.class);

  /** Hold 입력. demand 는 원문 그대로 저장한다(환산은 시험 정책상 1주문 = 1단위). */
  public record HoldCommand(
      String orderId,
      String mfcId,
      String window,
      int ttlMinutes,
      Object demand,
      @Nullable String replacesHoldId) {}

  /** S4 교체로 해제된 기존 Hold 사유(계약 해제 사유 값). */
  public static final String REASON_RESCHEDULED = "RESCHEDULED";

  private final HoldStore store;
  private final RequestIdempotency idempotency;
  private final CapacityProperties props;
  private final ObjectMapper mapper;
  private final Clock clock;

  private final com.pmart.mfc.capacity.infrastructure.RestrictionStore restrictions;

  public HoldService(
      com.pmart.mfc.capacity.infrastructure.RestrictionStore restrictions,
      HoldStore store,
      RequestIdempotency idempotency,
      CapacityProperties props,
      ObjectMapper mapper,
      Clock clock) {
    this.restrictions = restrictions;
    this.store = store;
    this.idempotency = idempotency;
    this.props = props;
    this.mapper = mapper;
    this.clock = clock;
  }

  public CommandResponse hold(String requestId, String sourceSystem, HoldCommand cmd, Object body) {
    HourWindow window = CapacityRules.window(cmd.window());
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "holdCapacity", actor()),
            body,
            CommandResponse.class,
            () -> {
              int configured = CapacityRules.requireCapacity(props.unitsPerHour());
              Instant now = clock.instant();
              // 잠금 순서: Hold 행 → 창 행
              for (CapacityHold stale : store.expireStaleForOrder(cmd.orderId(), now)) {
                store.bumpWindow(stale.mfcId(), stale.window(), now);
              }
              if (cmd.replacesHoldId() == null) {
                if (store.hasActiveForOrder(cmd.orderId())) {
                  throw alreadyActive(cmd.orderId());
                }
              } else {
                replaceable(cmd, window, now);
              }
              store.lockWindow(cmd.mfcId(), window, now);
              // 운영 제한(BO-08): 접수 중지면 거절, 축소면 그 창 수용 단위를 줄인다
              var active = restrictions.active(cmd.mfcId());
              String stop = RestrictionRules.intakeStop(active, window);
              if (stop != null) {
                throw new PmartException(
                    RestrictionRules.OPERATION_RESTRICTED,
                    org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
                    "신규 접수가 중지된 창입니다(" + cmd.mfcId() + " " + window.text() + ", 제한 " + stop + ").",
                    false,
                    List.of());
              }
              int capacity = RestrictionRules.capacity(configured, active, window);
              HoldStore.Occupancy occ = store.occupancy(cmd.mfcId(), window, now);
              int units = CapacityRules.unitsPerOrder();
              if (!CapacityRules.fits(capacity, occ.held(), occ.confirmed(), units)) {
                throw new PmartException(
                    ErrorCode.CAPACITY_EXCEEDED,
                    "작업 여력이 없습니다("
                        + cmd.mfcId()
                        + " "
                        + window.text()
                        + ", 남은 단위 "
                        + CapacityRules.available(capacity, occ.held(), occ.confirmed())
                        + ").");
              }
              CapacityHold hold =
                  new CapacityHold(
                      "CH-" + UUID.randomUUID(),
                      cmd.orderId(),
                      cmd.mfcId(),
                      window,
                      units,
                      State.HELD,
                      1,
                      now.plus(Duration.ofMinutes(cmd.ttlMinutes())),
                      null);
              try {
                store.insert(hold, mapper.writeValueAsString(cmd.demand()), now);
                if (cmd.replacesHoldId() != null) {
                  store.markReplacing(hold.holdId(), cmd.replacesHoldId());
                }
              } catch (DuplicateKeyException e) {
                // 같은 주문의 다른 키 요청이 동시에 먼저 들어감(활성 유일 인덱스)
                throw alreadyActive(cmd.orderId());
              }
              store.bumpWindow(cmd.mfcId(), window, now);
              log.info(
                  "capacity hold {} order {} {} {} held (expires {})",
                  hold.holdId(),
                  hold.orderId(),
                  hold.mfcId(),
                  window.text(),
                  hold.expiresAt());
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, hold.holdId(), units, 1, false, null, now);
            })
        .value();
  }

  /** S4 교체 조건: 같은 주문·센터의 확정 Hold, 다른 창, 진행 중인 교체(HELD) 없음. 기존 Hold 는 건드리지 않는다. */
  private void replaceable(HoldCommand cmd, HourWindow window, Instant now) {
    CapacityHold old =
        store.find(cmd.replacesHoldId(), false).orElseThrow(() -> notFound(cmd.replacesHoldId()));
    if (!old.orderId().equals(cmd.orderId()) || !old.mfcId().equals(cmd.mfcId())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "교체 대상은 같은 주문·같은 센터의 Hold 여야 합니다.");
    }
    if (old.effectiveState(now) != State.CONFIRMED) {
      throw PmartException.invalidState(
          "교체 대상 Hold 가 " + old.effectiveState(now) + " 상태입니다(확정된 Hold 만 바꿀 수 있습니다).");
    }
    if (old.window().equals(window)) {
      throw new PmartException(
          "SAME_WINDOW",
          org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
          "지금과 같은 창입니다.",
          false,
          List.of());
    }
    if (store.hasHeldForOrder(cmd.orderId())) {
      throw alreadyActive(cmd.orderId());
    }
  }

  public CapacityHold get(String holdId) {
    return store.find(holdId, false).orElseThrow(() -> notFound(holdId));
  }

  /** HELD → CONFIRMED. 이미 CONFIRMED 면 그대로 성공. 만료(시각 경과 포함)·해제는 INVALID_STATE. */
  public CommandResponse confirm(
      String requestId,
      String sourceSystem,
      String holdId,
      String orderId,
      @Nullable Integer expectedVersion,
      Object body) {
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "confirmCapacityHold", actor()),
            new Keyed(holdId, body),
            CommandResponse.class,
            () -> {
              Instant now = clock.instant();
              CapacityHold h = store.find(holdId, true).orElseThrow(() -> notFound(holdId));
              if (!h.orderId().equals(orderId)) {
                throw new PmartException(
                    ErrorCode.VALIDATION_FAILED, "orderId 가 Hold 의 주문과 다릅니다: " + holdId);
              }
              State state = h.effectiveState(now);
              if (state == State.CONFIRMED) {
                return applied(requestId, h.holdId(), h.version(), now);
              }
              checkVersion(expectedVersion, h.version());
              // 만료 시각이 지난 HELD 도 EXPIRED 로 보고 거절한다(상태 정리는 HoldExpirySweeper)
              if (state != State.HELD) {
                throw PmartException.invalidState("확정할 수 없는 상태입니다(" + state + "): " + holdId);
              }
              String replaces = store.replacesOf(holdId);
              if (replaces != null) {
                // S4: 기존 확정 Hold 를 먼저 해제(같은 트랜잭션 — CONFIRMED 1건 색인)
                CapacityHold old = store.find(replaces, true).orElseThrow(() -> notFound(replaces));
                if (old.state() != State.CONFIRMED) {
                  throw PmartException.invalidState(
                      "교체 대상 Hold 가 " + old.state() + " 상태라 바꿀 수 없습니다(취소 등).");
                }
                store.close(old.holdId(), State.RELEASED, REASON_RESCHEDULED, holdId, now);
                store.bumpWindow(old.mfcId(), old.window(), now);
                log.info("capacity hold {} released — rescheduled to {}", old.holdId(), holdId);
              }
              store.confirm(holdId, now);
              store.bumpWindow(h.mfcId(), h.window(), now);
              return applied(requestId, h.holdId(), h.version() + 1, now);
            })
        .value();
  }

  /** HELD/CONFIRMED → RELEASED(단위 반환). 이미 RELEASED/EXPIRED 면 그대로 성공. CONFIRMED 해제는 reason 필수. */
  public CommandResponse release(
      String requestId,
      String sourceSystem,
      String holdId,
      @Nullable String reason,
      @Nullable String sourceRef,
      @Nullable Integer expectedVersion,
      Object body) {
    return idempotency
        .execute(
            new RequestKey(sourceSystem, requestId, "releaseCapacityHold", actor()),
            new Keyed(holdId, body),
            CommandResponse.class,
            () -> {
              Instant now = clock.instant();
              CapacityHold h = store.find(holdId, true).orElseThrow(() -> notFound(holdId));
              if (!h.state().active()) {
                return applied(requestId, h.holdId(), h.version(), now);
              }
              if (h.state() == State.CONFIRMED && (reason == null || reason.isBlank())) {
                throw new PmartException(
                    ErrorCode.VALIDATION_FAILED,
                    "확정된 Hold 해제에는 reason 이 필요합니다.",
                    List.of(new PmartException.Detail("reason", "REQUIRED_FOR_CONFIRMED")));
              }
              checkVersion(expectedVersion, h.version());
              store.close(holdId, State.RELEASED, reason, sourceRef, now);
              store.bumpWindow(h.mfcId(), h.window(), now);
              log.info("capacity hold {} released from {} ({})", holdId, h.state(), reason);
              return applied(requestId, h.holdId(), h.version() + 1, now);
            })
        .value();
  }

  /** 경로 변수도 결과를 바꾸므로 본문과 함께 비교한다. */
  record Keyed(String holdId, Object body) {}

  private static CommandResponse applied(
      String requestId, String holdId, int version, Instant now) {
    return new CommandResponse(
        CommandStatus.APPLIED, requestId, holdId, null, version, false, null, now);
  }

  private static void checkVersion(@Nullable Integer expected, int current) {
    if (expected != null && expected != current) {
      throw PmartException.versionConflict(
          "Hold 가 이미 바뀌었습니다(기대 버전 " + expected + ", 현재 " + current + "). 다시 조회하세요.");
    }
  }

  private static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }

  private static PmartException alreadyActive(String orderId) {
    return new PmartException(
        HOLD_ALREADY_ACTIVE,
        HttpStatus.CONFLICT,
        "주문에 활성 작업 여력 Hold 가 이미 있습니다: " + orderId,
        false,
        List.of());
  }

  private static PmartException notFound(String holdId) {
    return PmartException.notFound(HOLD_NOT_FOUND, "Hold 가 없습니다: " + holdId);
  }
}
