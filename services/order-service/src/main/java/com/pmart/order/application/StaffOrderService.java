package com.pmart.order.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.order.domain.ShippingAddress;
import com.pmart.order.domain.StaffOrderRules;
import com.pmart.order.domain.StaffOrderRules.PiiField;
import com.pmart.order.infrastructure.AuditClient;
import com.pmart.order.infrastructure.OrderStore;
import com.pmart.order.infrastructure.OrderStore.LineRow;
import com.pmart.order.infrastructure.OrderStore.OrderRow;
import com.pmart.order.infrastructure.OrderStore.StaffCriteria;
import com.pmart.order.infrastructure.OrderStore.StaffSummary;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원용 주문 검색·상세(2026-11-15 사용자 결정, O-6 — 백오피스 고객센터·라이더 운영). OPERATOR 토큰 + 역할
 * OPERATOR_ADMIN·CS_AGENT·RIDER_OPS({@link StaffOrderRules#ROLES}) 만. order 원장만 읽는다(다른 서비스 호출 없음,
 * 읽기 전용).
 *
 * <p>상세의 배송지는 가린다(0.4.0). 원문은 {@link #reveal} — 사유를 받아 audit-service 에 열람 기록을 동기로 남긴 뒤에만 준다
 * (2026-11-15 사용자 "기록 남긴다", BO-25). 기록을 못 남기면(미응답·결과 불명) 원문을 주지 않는다.
 *
 * <p>검색 조건(특히 연락처)·배송지·열람 사유는 개인정보라 로그에 남기지 않는다.
 */
@Service
public class StaffOrderService {

  /** 상세: 주문 + 주문행 + 배송지 사본(가린 값, {@link StaffOrderRules#mask}) + 이행 확인 필요 기록. */
  public record Detail(
      OrderRow order,
      List<LineRow> lines,
      @Nullable ShippingAddress shipping,
      List<FulfillmentIssueService.Issue> fulfillmentIssues) {}

  /** 검색 결과 한 쪽. receiverPhoneMasked 는 이미 가린 값. */
  public record Row(StaffSummary summary, @Nullable String receiverPhoneMasked) {}

  public record Page(List<Row> items, int page, int size, boolean hasNext) {}

  /** 원문 열람 결과: 요청 항목만 원문인 배송지 + 열람 기록 ID. */
  public record Reveal(
      String orderId,
      String piiAccessLogId,
      Instant recordedAt,
      List<PiiField> fields,
      ShippingAddress shipping,
      @Nullable String deliveryNote) {}

  /** 검색어. createdFrom·createdTo 는 확정 시각 기준. */
  public record Query(
      @Nullable String orderId,
      @Nullable String customerId,
      @Nullable String status,
      @Nullable String mfcId,
      @Nullable String receiverPhone,
      java.time.@Nullable Instant createdFrom,
      java.time.@Nullable Instant createdTo,
      int page,
      int size) {}

  private final OrderStore orders;
  private final FulfillmentIssueService issues;
  private final AuditClient audit;
  private final com.pmart.order.infrastructure.RedeliveryDestinationStore destinations;
  private final Clock clock;

  public StaffOrderService(
      OrderStore orders,
      FulfillmentIssueService issues,
      AuditClient audit,
      com.pmart.order.infrastructure.RedeliveryDestinationStore destinations,
      Clock clock) {
    this.orders = orders;
    this.issues = issues;
    this.audit = audit;
    this.destinations = destinations;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Page search(Query q) {
    requireStaff();
    String orderId = blankToNull(q.orderId());
    String customerId = blankToNull(q.customerId());
    String problem =
        StaffOrderRules.rangeProblem(
            orderId != null || customerId != null, q.createdFrom(), q.createdTo());
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    String phone = null;
    String rawPhone = blankToNull(q.receiverPhone());
    if (rawPhone != null) {
      phone = StaffOrderRules.normalizePhone(rawPhone);
      if (phone == null) {
        // 검색어(연락처)를 메시지에 되돌려 싣지 않는다
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "receiverPhone 은 숫자 9~11자리여야 합니다.");
      }
    }
    var criteria =
        new StaffCriteria(
            orderId,
            customerId,
            q.status(),
            blankToNull(q.mfcId()),
            phone,
            q.createdFrom(),
            q.createdTo());
    List<StaffSummary> rows = orders.staffSearch(criteria, q.page() * q.size(), q.size() + 1);
    boolean hasNext = rows.size() > q.size();
    if (hasNext) {
      rows = rows.subList(0, q.size());
    }
    return new Page(
        rows.stream().map(s -> new Row(s, StaffOrderRules.maskPhone(s.receiverPhone()))).toList(),
        q.page(),
        q.size(),
        hasNext);
  }

  @Transactional(readOnly = true)
  public Detail get(String orderId) {
    requireStaff();
    OrderRow o =
        orders
            .order(orderId)
            .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
    ShippingAddress shipping =
        orders.shipping(orderId).map(a -> StaffOrderRules.mask(a, Set.of())).orElse(null);
    return new Detail(o, orders.lines(orderId), shipping, issues.forOrder(orderId));
  }

  /**
   * 배송지 원문 열람. 권한 → 주문·사본 확인 → audit-service 열람 기록(동기) → 원문. 트랜잭션 밖(원격 호출을 DB 트랜잭션에 묶지 않는다).
   *
   * <p>audit 기록 키는 (직원, 주문, 클라이언트 Idempotency-Key)로 정한다 — 같은 직원이 같은 키로 다시 보내면 같은 기록, 다른 직원이 같은 키 값을
   * 써도 섞이지 않는다. 원문 응답은 저장하지 않는다.
   *
   * @param fields 볼 항목. 비면 전부
   */
  public Reveal reveal(
      String orderId,
      String idempotencyKey,
      String clientSource,
      @Nullable String reason,
      @Nullable Set<PiiField> fields) {
    PmartPrincipal p = requireStaff();
    String problem = StaffOrderRules.reasonProblem(reason);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    String purpose = java.util.Objects.requireNonNull(reason).strip();
    Set<PiiField> wanted =
        fields == null || fields.isEmpty() ? EnumSet.allOf(PiiField.class) : EnumSet.copyOf(fields);
    OrderRow o =
        orders
            .order(orderId)
            .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
    ShippingAddress full =
        orders
            .shipping(orderId)
            .orElseThrow(
                () ->
                    PmartException.notFound(
                        "SHIPPING_COPY_NOT_FOUND", "이 주문에는 주문 당시 배송지 사본이 없습니다."));
    List<PiiField> ordered = new ArrayList<>(wanted); // EnumSet 순서(NAME·PHONE·ADDRESS·ENTRANCE)

    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("actorId", p.subject());
    actor.put("actorType", "OPERATOR");
    actor.put("roles", p.roles());
    actor.put("centerScope", p.centerScope());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("occurredAt", Instant.now(clock).toString());
    body.put("actor", actor);
    body.put("subjectType", "CUSTOMER");
    body.put("subjectRef", o.accountId());
    body.put("accessType", "VIEW");
    body.put("fields", ordered.stream().map(Enum::name).toList());
    body.put("purpose", purpose);
    body.put("screen", truncate(clientSource, 80) + ":order-lookup-detail");
    body.put("caseRef", "ORDER:" + orderId);
    body.put("recordCount", 1);
    AuditClient.Recorded r =
        audit.recordPiiAccess(auditRequestId(p.subject(), orderId, idempotencyKey), body);
    return new Reveal(
        orderId,
        r.piiAccessLogId(),
        r.recordedAt(),
        ordered,
        StaffOrderRules.mask(full, wanted),
        wanted.contains(PiiField.ENTRANCE)
            ? o.deliveryNote()
            : null); // 배송 요청사항은 ENTRANCE 와 같이(ADR-0062)
  }

  /**
   * 오배송 다시 배송 실제 집 주소 기록(ADR-0065, 2026-11-16 — 상담원 기록). 주문 배송지 사본은 바꾸지 않는다. 같은 직원·주문·키는 같은 ID.
   * 주소·사유 로그 금지.
   */
  public com.pmart.order.infrastructure.RedeliveryDestinationStore.Destination
      recordRedeliveryDestination(
          String orderId,
          String idempotencyKey,
          String deliveryId,
          String roadAddress,
          @Nullable String detailAddress,
          @Nullable String postalCode,
          @Nullable String reason) {
    PmartPrincipal p = requireStaff();
    String problem = StaffOrderRules.reasonProblem(reason);
    if (problem != null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, problem);
    }
    if (roadAddress == null
        || roadAddress.isBlank()
        || deliveryId == null
        || deliveryId.isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "배송 ID 와 도로명주소가 필요합니다.");
    }
    orders
        .order(orderId)
        .orElseThrow(() -> PmartException.notFound("ORDER_NOT_FOUND", "주문이 없습니다."));
    String id =
        "RDD-"
            + java.util.UUID.nameUUIDFromBytes(
                (p.subject() + "\n" + orderId + "\n" + idempotencyKey)
                    .getBytes(StandardCharsets.UTF_8));
    var d =
        new com.pmart.order.infrastructure.RedeliveryDestinationStore.Destination(
            id,
            orderId,
            deliveryId.strip(),
            roadAddress.strip(),
            blankToNull(detailAddress),
            blankToNull(postalCode),
            Instant.now(clock));
    destinations.insert(d, java.util.Objects.requireNonNull(reason).strip(), p.subject());
    return destinations.find(orderId, id).orElse(d);
  }

  /** 내부 조회(dispatch·execution). */
  public com.pmart.order.infrastructure.RedeliveryDestinationStore.Destination
      redeliveryDestination(String orderId, String destinationId) {
    return destinations
        .find(orderId, destinationId)
        .orElseThrow(
            () -> PmartException.notFound("REDELIVERY_DESTINATION_NOT_FOUND", "다시 배송 주소가 없습니다."));
  }

  /** audit 기록 멱등 키: 직원·주문·클라이언트 키의 SHA-256(128자 상한 안). */
  static String auditRequestId(String actor, String orderId, String idempotencyKey) {
    try {
      byte[] d =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (actor + "\n" + orderId + "\n" + idempotencyKey)
                      .getBytes(StandardCharsets.UTF_8));
      return "order-pii-reveal:" + HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static PmartPrincipal requireStaff() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.OPERATOR)
        .filter(p -> StaffOrderRules.allowed(p.roles()))
        .orElseThrow(
            () ->
                new PmartException(
                    ErrorCode.FORBIDDEN, "주문 조회 권한이 없습니다(OPERATOR_ADMIN·CS_AGENT·RIDER_OPS)."));
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
