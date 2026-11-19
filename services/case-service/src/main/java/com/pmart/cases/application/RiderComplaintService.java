package com.pmart.cases.application;

import com.pmart.cases.domain.RiderComplaintRules;
import com.pmart.cases.infrastructure.DispatchClient;
import com.pmart.cases.infrastructure.RiderComplaintStore;
import com.pmart.cases.infrastructure.RiderComplaintStore.ComplaintRow;
import com.pmart.cases.infrastructure.RiderComplaintStore.Filter;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 라이더 컴플레인 기록(0.3.0, service-requests case R-5 — RS-3, 사용자 결정 2026-11-15).
 * 상담원(CS_AGENT·OPERATOR_ADMIN)이 백오피스에서 신고·오배송·파손을 기록하면 라이더를 dispatch 배정 조회로 채우고, 기록과 같은 트랜잭션에서
 * {@code RiderComplaintRecorded}(platform.case.v1)를 Outbox 에 쓴다. 세고 제한 검토 대상을 여는 것은 rider-service —
 * 자동 제재 없음. 메모(note)는 개인정보가 들어갈 수 있어 로그·사건에 싣지 않는다.
 */
@Service
public class RiderComplaintService {

  private static final Logger log = LoggerFactory.getLogger(RiderComplaintService.class);

  public static final String EVENT_TYPE = "RiderComplaintRecorded";
  public static final String AGGREGATE = "Case";

  public record RecordCommand(
      String deliveryId,
      String complaintType,
      @Nullable String orderId,
      @Nullable String note,
      @Nullable String inquiryId) {}

  public record Page(List<ComplaintRow> items, @Nullable String nextCursor) {}

  private final RiderComplaintStore store;
  private final DispatchClient dispatch;
  private final CaseProperties props;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final Clock clock;

  public RiderComplaintService(
      RiderComplaintStore store,
      DispatchClient dispatch,
      CaseProperties props,
      RequestIdempotency idempotency,
      EventPublisher events,
      Clock clock) {
    this.store = store;
    this.dispatch = dispatch;
    this.props = props;
    this.idempotency = idempotency;
    this.events = events;
    this.clock = clock;
  }

  /** 기록. 배정 라이더 조회는 트랜잭션 밖(외부 호출), 행·사건은 한 트랜잭션. */
  public ComplaintRow record(String requestId, String sourceSystem, RecordCommand cmd) {
    PmartPrincipal p = requireRole(RiderComplaintRules.RECORD_ROLES, "라이더 컴플레인 기록");
    if (!RiderComplaintRules.TYPES.contains(cmd.complaintType())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "컴플레인 종류가 올바르지 않습니다.");
    }
    if (props.deliveryDispatchBaseUrl() == null) {
      throw PmartException.configRequired("pmart.cases.delivery-dispatch-base-url");
    }
    String deliveryId = cmd.deliveryId().strip();
    DispatchClient.Assignment a = dispatch.assignment(deliveryId);
    String riderId = a.riderId();
    if (riderId == null) {
      boolean noDelivery = "DELIVERY_NOT_FOUND".equals(a.notFoundCode());
      throw unprocessable(
          noDelivery ? "DELIVERY_NOT_FOUND" : "RIDER_NOT_ASSIGNED",
          noDelivery ? "배송을 찾을 수 없습니다." : "이 배송에 배정된 라이더가 없습니다.");
    }
    String complaintId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "recordRiderComplaint", p.subject()),
                cmd,
                String.class,
                () -> {
                  String inquiryId = blankToNull(cmd.inquiryId());
                  String caseId = null;
                  if (inquiryId != null) {
                    caseId =
                        store
                            .inquiryCaseId(inquiryId)
                            .orElseThrow(
                                () -> unprocessable("INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다."));
                  }
                  Instant now = clock.instant();
                  ComplaintRow row =
                      new ComplaintRow(
                          "RCP-" + UUID.randomUUID(),
                          caseId,
                          riderId,
                          deliveryId,
                          blankToNull(cmd.orderId()),
                          inquiryId,
                          cmd.complaintType(),
                          RiderComplaintRules.SOURCE_CS_AGENT,
                          blankToNull(cmd.note()),
                          p.subject(),
                          now);
                  store.insert(row);
                  events.publish(
                      EVENT_TYPE,
                      1,
                      AGGREGATE,
                      row.complaintId(),
                      1,
                      new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
                      payload(row));
                  log.info(
                      "rider complaint recorded id={} type={} delivery={} rider={}",
                      row.complaintId(),
                      row.complaintType(),
                      deliveryId,
                      riderId);
                  return row.complaintId();
                })
            .value();
    return store.find(complaintId).orElseThrow(() -> notFound(complaintId));
  }

  public Page list(Filter filter, @Nullable String cursor, int size) {
    requireRole(RiderComplaintRules.READ_ROLES, "라이더 컴플레인 조회");
    List<ComplaintRow> rows = store.page(filter, cursor, size);
    boolean more = rows.size() > size;
    List<ComplaintRow> items = more ? rows.subList(0, size) : rows;
    return new Page(List.copyOf(items), more ? items.get(items.size() - 1).complaintId() : null);
  }

  /** 종류별 수(0.26.0, 웹 숫자 띠) — 권한은 목록과 같다. 모든 종류를 0 까지(정해진 순서), 정확한 수. */
  public Map<String, Long> countsByType(Filter filter) {
    requireRole(RiderComplaintRules.READ_ROLES, "라이더 컴플레인 조회");
    Map<String, Long> found = store.countsByType(filter);
    Map<String, Long> out = new LinkedHashMap<>();
    for (String type : RiderComplaintRules.TYPE_ORDER) {
      out.put(type, found.getOrDefault(type, 0L));
    }
    return out;
  }

  public ComplaintRow get(String complaintId) {
    requireRole(RiderComplaintRules.READ_ROLES, "라이더 컴플레인 조회");
    return store.find(complaintId).orElseThrow(() -> notFound(complaintId));
  }

  /** 계약 RiderComplaintRecorded.v1 — 내용(note)·고객 정보는 싣지 않는다. 값이 없는 선택 칸은 뺀다. */
  static Map<String, Object> payload(ComplaintRow r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("complaintId", r.complaintId());
    if (r.caseId() != null) {
      m.put("caseId", r.caseId());
    }
    m.put("riderId", r.riderId());
    m.put("deliveryId", r.deliveryId());
    if (r.orderId() != null) {
      m.put("orderId", r.orderId());
    }
    m.put("complaintType", r.complaintType());
    m.put("source", r.source());
    m.put("recordedAt", r.recordedAt().toString());
    return m;
  }

  private static PmartPrincipal requireRole(List<String> roles, String what) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || roles.stream().noneMatch(p::hasRole)) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, what + " 권한이 없습니다(" + String.join("·", roles) + ").");
    }
    return p;
  }

  private static PmartException unprocessable(String code, String message) {
    return new PmartException(code, HttpStatus.UNPROCESSABLE_CONTENT, message, false, List.of());
  }

  private static PmartException notFound(String complaintId) {
    return PmartException.notFound("RIDER_COMPLAINT_NOT_FOUND", "컴플레인을 찾을 수 없습니다: " + complaintId);
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
