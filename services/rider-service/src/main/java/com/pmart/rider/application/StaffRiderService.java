package com.pmart.rider.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.rider.domain.Rider;
import com.pmart.rider.infrastructure.AuditClient;
import com.pmart.rider.infrastructure.RiderStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 직원용 라이더 찾기·상세·이름/전화 원문 열람(0.4.0, 2026-11-15 — 백오피스 라이더 운영·고객센터, BOD-3·6).
 *
 * <ul>
 *   <li>권한: OPERATOR_ADMIN·RIDER_OPS·CS_AGENT(상담원은 컴플레인 기록 때 라이더 확인).
 *   <li>찾기는 라이더 ID·전화번호 완전 일치만 — 이름 검색 없음(사용자 2026-11-15 "아이디로 찾는 게 낫지 않아? 이름은 동명이인이 있을 수도").
 *   <li>이름·전화는 가린 값, 원문은 사유 + audit-service 동기 기록 뒤에만(ADR-0062 적용 범위 — 라이더 상세). 기록을 못 남기면 원문도 없다.
 *       원문 응답은 저장하지 않는다. 계좌·거주지는 내보내지 않는다.
 * </ul>
 */
@Service
public class StaffRiderService {

  /** 열람 항목. */
  public enum PiiField {
    NAME,
    PHONE
  }

  public record Reveal(
      String riderId,
      String piiAccessLogId,
      Instant recordedAt,
      List<PiiField> fields,
      @Nullable String name,
      @Nullable String phone) {}

  static final int REASON_MIN = 2;
  static final int REASON_MAX = 200;

  private final RiderStore store;
  private final AuditClient audit;
  private final Clock clock;

  public StaffRiderService(RiderStore store, AuditClient audit, Clock clock) {
    this.store = store;
    this.audit = audit;
    this.clock = clock;
  }

  /** limit·offset 은 따로 받는다 — 다음 쪽 확인용으로 limit 을 하나 늘려도 건너뛸 수가 바뀌지 않게(PC 1 #17 검토). */
  public List<Rider> search(RiderStore.Search s, int limit, int offset) {
    requireStaff();
    Instant now = clock.instant();
    return store.search(s, now, limit, offset).stream().map(r -> r.asOf(now)).toList();
  }

  /**
   * 화면 표기 묶음(2026-11-20 RS-MFC, ADR-0070): 라이더 운영·상담원·관리자에 더해 MFC 센터 관리자·작업자도. 응답에서 쓰는 것은 번호·가린
   * 이름뿐이다 (컨트롤러가 그 두 칸만 내보낸다).
   */
  public List<Rider> displayNames(List<String> riderIds) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> forbidden("직원만 라이더 표기를 볼 수 있습니다."));
    if (!(p.hasRole("OPERATOR_ADMIN")
        || p.hasRole("RIDER_OPS")
        || p.hasRole("CS_AGENT")
        || p.hasRole("MFC_SUPERVISOR")
        || p.hasRole("MFC_WORKER"))) {
      throw forbidden("라이더 표기 조회 권한이 없습니다.");
    }
    return store.search(
        // 탈퇴한 라이더도 번호는 보인다(이름은 지워져 null — 2026-11-21 탈퇴 라이더 기록 보기)
        new RiderStore.Search(
            null, null, null, null, null, null, false, null, riderIds, RiderStore.Search.Scope.ALL),
        clock.instant(),
        riderIds.size(),
        0);
  }

  /**
   * 직원 상세. {@code includeWithdrawn} 이면 탈퇴한 라이더도(2026-11-21 — 라이더 백오피스 "탈퇴한 라이더의 수행료·상담 기록 보기",
   * 개인정보는 탈퇴 때 이미 비었다). 제한·원문 열람 같은 조치는 계속 탈퇴 라이더를 찾지 못한다.
   */
  public Rider detail(String riderId, boolean includeWithdrawn) {
    requireStaff();
    if (!includeWithdrawn) {
      return get(riderId);
    }
    return store
        .findIncludingWithdrawn(riderId)
        .map(r -> r.asOf(clock.instant()))
        .orElseThrow(() -> PmartException.notFound("RIDER_NOT_FOUND", "가입한 라이더가 아닙니다."));
  }

  /** 상세의 동의 목록(직원 — 상세와 같은 권한, 2026-11-18). */
  public List<RiderStore.Consent> consents(String riderId) {
    requireStaff();
    return store.consents(riderId);
  }

  public Reveal reveal(
      String riderId,
      String idempotencyKey,
      String clientSource,
      @Nullable String reason,
      @Nullable Set<PiiField> fields) {
    PmartPrincipal p = requireStaff();
    int n = reason == null ? 0 : reason.strip().length();
    if (n < REASON_MIN || n > REASON_MAX) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "reason(열람 사유)은 " + REASON_MIN + "~" + REASON_MAX + "자여야 합니다.");
    }
    Set<PiiField> wanted =
        fields == null || fields.isEmpty() ? EnumSet.allOf(PiiField.class) : EnumSet.copyOf(fields);
    Rider r = get(riderId);
    List<PiiField> ordered = new ArrayList<>(wanted);

    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("actorId", p.subject());
    actor.put("actorType", "OPERATOR");
    actor.put("roles", p.roles());
    actor.put("centerScope", p.centerScope());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("occurredAt", clock.instant().toString());
    body.put("actor", actor);
    body.put("subjectType", "RIDER");
    body.put("subjectRef", riderId);
    body.put("accessType", "VIEW");
    body.put("fields", ordered.stream().map(Enum::name).toList());
    body.put("purpose", reason.strip());
    body.put("screen", truncate(clientSource, 80) + ":rider-detail");
    body.put("caseRef", "RIDER:" + riderId);
    body.put("recordCount", 1);
    AuditClient.Recorded rec =
        audit.recordPiiAccess(auditRequestId(p.subject(), riderId, idempotencyKey), body);
    return new Reveal(
        riderId,
        rec.piiAccessLogId(),
        rec.recordedAt(),
        ordered,
        wanted.contains(PiiField.NAME) ? r.name() : null,
        wanted.contains(PiiField.PHONE) ? r.phone() : null);
  }

  /** audit 기록 멱등 키: 직원·라이더·클라이언트 키의 SHA-256(다른 직원과 섞이지 않게 — order 와 같은 방식). */
  static String auditRequestId(String actor, String riderId, String idempotencyKey) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (actor + "\n" + riderId + "\n" + idempotencyKey)
                      .getBytes(StandardCharsets.UTF_8));
      return "rider-pii-reveal:" + HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }

  /** 전화번호 검색어 → 숫자만(하이픈·공백 무시). 휴대전화 형식(01로 시작 10~11자리)이 아니면 400. */
  public static String normalizePhone(String phone) {
    String digits = phone.replaceAll("[\\s-]", "");
    if (!digits.matches("01[0-9]{8,9}")) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "phone 은 휴대전화 번호(01로 시작하는 숫자 10~11자리)여야 합니다.");
    }
    return digits;
  }

  private Rider get(String riderId) {
    return store
        .find(riderId)
        .map(r -> r.asOf(clock.instant()))
        .orElseThrow(() -> PmartException.notFound("RIDER_NOT_FOUND", "가입한 라이더가 아닙니다."));
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  /** 탈퇴 신청 목록(2026-11-20 사용자 "라이더 쪽에만 넣어" — 라이더 백오피스). OPERATOR_ADMIN·RIDER_OPS·CS_AGENT. */
  public List<RiderStore.StaffWithdrawal> withdrawals(
      @org.jspecify.annotations.Nullable String state,
      @org.jspecify.annotations.Nullable String riderNumber,
      int limit,
      int offset) {
    requireStaff();
    return store.staffWithdrawals(state, riderNumber, limit, offset);
  }

  /** OPERATOR_ADMIN·RIDER_OPS·CS_AGENT 만. */
  private static PmartPrincipal requireStaff() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> forbidden("직원만 라이더 정보를 볼 수 있습니다."));
    if (!p.hasRole("OPERATOR_ADMIN") && !p.hasRole("RIDER_OPS") && !p.hasRole("CS_AGENT")) {
      throw forbidden("라이더 조회 권한(OPERATOR_ADMIN·RIDER_OPS·CS_AGENT)이 없습니다.");
    }
    return p;
  }

  private static PmartException forbidden(String message) {
    return new PmartException(
        ErrorCode.FORBIDDEN.name(), HttpStatus.FORBIDDEN, message, false, List.of());
  }

  /** 대시보드 라이더 수(2026-11-17 — 사용자 "대시보드에 필요한 것만"). RIDER_OPS·OPERATOR_ADMIN·CS_AGENT. */
  public record Stats(
      int total,
      int onDuty,
      int onDutyAcceptingNew,
      int normal,
      int dispatchRestricted,
      int suspended,
      int terminated,
      int joinedToday,
      java.time.Instant asOf) {}

  public Stats stats(@org.jspecify.annotations.Nullable String mfcId) {
    com.pmart.common.security.PmartPrincipal p =
        com.pmart.common.security.PmartPrincipal.current()
            .filter(
                x -> x.type() == com.pmart.common.security.PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(
                () ->
                    new com.pmart.common.web.PmartException(
                        com.pmart.common.web.ErrorCode.FORBIDDEN, "직원만 볼 수 있습니다."));
    if (!(p.hasRole("RIDER_OPS") || p.hasRole("OPERATOR_ADMIN") || p.hasRole("CS_AGENT"))) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.FORBIDDEN, "라이더 현황은 라이더 운영·관리자·상담원만 볼 수 있습니다.");
    }
    java.time.Instant now = clock.instant();
    java.time.Instant dayStart =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
            .atStartOfDay(java.time.ZoneId.of("Asia/Seoul"))
            .toInstant();
    return store.stats(mfcId, now, dayStart);
  }
}
