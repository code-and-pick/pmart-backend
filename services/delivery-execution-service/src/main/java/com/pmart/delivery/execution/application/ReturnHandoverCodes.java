package com.pmart.delivery.execution.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MFC 반납 인증번호(0.27.0, 2026-11-18 — 사용자 "직원이나 어딘가에 뜨는 인증코드(번호)를 입력해야 전달완료가 되도록"). 회수·반납 배송마다 숫자 4자리를
 * 센터 직원 화면에만 보여 주고, 라이더가 반납할 때 그 번호를 넣어야 반납된다. 반납까지 유효, {@value #MAX_FAILS}번 틀리면 잠김(직원이 새로 내면 풀림).
 * 번호 줄 만들기·틀린 횟수는 라이더 반납 트랜잭션이 되돌려져도 남도록 따로 커밋한다. 번호는 로그·사건에 넣지 않는다.
 */
@Service
public class ReturnHandoverCodes {

  static final int MAX_FAILS = 5;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** 직원 화면 한 줄. */
  public record Handover(
      DeliveryRow delivery, String code, int failedAttempts, boolean locked, Instant issuedAt) {}

  private record CodeRow(String code, int failedAttempts, boolean locked, Instant issuedAt) {}

  private final JdbcTemplate jdbc;
  private final ExecutionStore store;
  private final TransactionTemplate separate;
  private final Clock clock;
  private final boolean required;

  public ReturnHandoverCodes(
      JdbcTemplate jdbc,
      ExecutionStore store,
      PlatformTransactionManager tm,
      Clock clock,
      @Value("${pmart.execution.return-handover.required:false}") boolean required) {
    this.jdbc = jdbc;
    this.store = store;
    this.separate = new TransactionTemplate(tm);
    this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
    this.required = required;
  }

  /** 반납을 기다리는 배송인가 — 회수품을 받아 오는 중, 또는 전달 실패 반납(받음 없이 배정에서 바로). */
  static boolean awaitingReturn(DeliveryRow d) {
    return "RETURN".equals(d.type())
        && ("RETURN_PICKED_UP".equals(d.state())
            || (d.failedReturn() && "ASSIGNED".equals(d.state())));
  }

  public List<Handover> list(@Nullable String mfcId) {
    PmartPrincipal p = Access.requireStaffScope(mfcId);
    List<String> ids =
        jdbc.queryForList(
            "SELECT delivery_id FROM execution_delivery WHERE type = 'RETURN' AND (state ="
                + " 'RETURN_PICKED_UP' OR (purpose = 'FAILED_DELIVERY_RETURN' AND state = 'ASSIGNED'))"
                + (mfcId == null || mfcId.isBlank() ? "" : " AND mfc_id = ?")
                + " ORDER BY delivery_id LIMIT 500",
            String.class,
            mfcId == null || mfcId.isBlank() ? new Object[0] : new Object[] {mfcId});
    return ids.stream()
        .map(store::delivery)
        .flatMap(Optional::stream)
        .filter(d -> Access.operatorCanSee(p, d.mfcId()))
        .map(d -> handover(d, ensure(d.deliveryId())))
        .toList();
  }

  public Handover reissue(String deliveryId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> ExecutionService.forbidden("직원만 할 수 있습니다."));
    DeliveryRow d =
        store
            .delivery(deliveryId)
            .filter(x -> Access.operatorCanSee(p, x.mfcId()))
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "배송을 찾을 수 없습니다."));
    if (!awaitingReturn(d)) {
      throw PmartException.invalidState("반납을 기다리는 배송이 아닙니다(지금 " + d.state() + ").");
    }
    Instant now = clock.instant();
    separate.executeWithoutResult(
        s ->
            jdbc.update(
                "INSERT INTO return_handover_code (delivery_id, code, issued_at) VALUES (?, ?, ?)"
                    + " ON CONFLICT (delivery_id) DO UPDATE SET code = EXCLUDED.code, issued_at ="
                    + " EXCLUDED.issued_at, failed_attempts = 0, locked_at = NULL",
                deliveryId,
                newCode(),
                Timestamp.from(now)));
    return handover(d, read(deliveryId).orElseThrow());
  }

  /** 라이더 반납 때(배송 행 잠금 안) — 번호가 맞으면 쓴 것으로 표시, 틀리면 횟수를 따로 커밋하고 422. 번호를 주지 않으면 설정에 따라 422 또는 통과. */
  void verify(String deliveryId, @Nullable String given, Instant now) {
    if (given == null || given.isBlank()) {
      if (required) {
        throw unprocessable(
            "RETURN_HANDOVER_CODE_REQUIRED", "MFC 직원에게 반납 인증번호를 받아 넣어 주세요.", List.of());
      }
      return;
    }
    CodeRow c = ensure(deliveryId);
    if (c.locked()) {
      throw locked();
    }
    if (c.code().equals(given.strip())) {
      jdbc.update(
          "UPDATE return_handover_code SET used_at = ? WHERE delivery_id = ?",
          Timestamp.from(now),
          deliveryId);
      return;
    }
    int fails =
        separate.execute(
            s ->
                jdbc.queryForObject(
                    "UPDATE return_handover_code SET failed_attempts = failed_attempts + 1,"
                        + " locked_at = CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE locked_at END"
                        + " WHERE delivery_id = ? RETURNING failed_attempts",
                    Integer.class,
                    MAX_FAILS,
                    Timestamp.from(now),
                    deliveryId));
    if (fails >= MAX_FAILS) {
      throw locked();
    }
    throw unprocessable(
        "RETURN_HANDOVER_CODE_MISMATCH",
        "반납 인증번호가 맞지 않습니다.",
        List.of(new PmartException.Detail("attemptsLeft", String.valueOf(MAX_FAILS - fails))));
  }

  private CodeRow ensure(String deliveryId) {
    return read(deliveryId)
        .orElseGet(
            () -> {
              separate.executeWithoutResult(
                  s ->
                      jdbc.update(
                          "INSERT INTO return_handover_code (delivery_id, code, issued_at) VALUES (?,"
                              + " ?, ?) ON CONFLICT (delivery_id) DO NOTHING",
                          deliveryId,
                          newCode(),
                          Timestamp.from(clock.instant())));
              return read(deliveryId).orElseThrow();
            });
  }

  private Optional<CodeRow> read(String deliveryId) {
    return jdbc
        .query(
            "SELECT code, failed_attempts, locked_at, issued_at FROM return_handover_code WHERE"
                + " delivery_id = ?",
            (rs, i) ->
                new CodeRow(
                    rs.getString("code"),
                    rs.getInt("failed_attempts"),
                    rs.getTimestamp("locked_at") != null,
                    rs.getTimestamp("issued_at").toInstant()),
            deliveryId)
        .stream()
        .findFirst();
  }

  private static Handover handover(DeliveryRow d, CodeRow c) {
    return new Handover(d, c.code(), c.failedAttempts(), c.locked(), c.issuedAt());
  }

  private static String newCode() {
    return String.format("%04d", RANDOM.nextInt(10_000));
  }

  private static PmartException locked() {
    return unprocessable(
        "RETURN_HANDOVER_LOCKED", "인증번호를 여러 번 틀려 잠겼습니다. MFC 직원에게 새 번호를 받아 주세요.", List.of());
  }

  private static PmartException unprocessable(
      String code, String message, List<PmartException.Detail> details) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, details);
  }
}
