package com.pmart.cases.application;

import com.pmart.cases.domain.CsIntakeRules;
import com.pmart.cases.domain.InquiryRules;
import com.pmart.cases.infrastructure.InquiryPhotoStore;
import com.pmart.cases.infrastructure.InquiryPhotoStore.PhotoRow;
import com.pmart.cases.infrastructure.InquiryStore;
import com.pmart.cases.infrastructure.InquiryStore.InquiryRow;
import com.pmart.cases.infrastructure.InquiryStore.ReplyRow;
import com.pmart.cases.infrastructure.OrderClient;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 고객센터 문의(0.2.0, 사용자 결정 2026-11-09 C2·C4): 회원 고객만 남기고, 상담원(CS_AGENT)이 답한다. 접수는 명령이 아니다 — 취소·환불을
 * 실행하지 않는다. 문의 내용은 로그에 남기지 않는다(ID·상태만).
 */
@Service
public class InquiryService {

  private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

  public record CreateCommand(
      String category,
      @Nullable String orderId,
      @Nullable String title,
      String body,
      @Nullable String origin,
      @Nullable List<String> photoIds,
      @Nullable String deliveryId,
      @Nullable String deliveryIssue) {}

  public record View(InquiryRow inquiry, List<ReplyRow> replies, List<PhotoRow> photos) {}

  public record Page(List<InquiryRow> items, @Nullable String nextCursor) {}

  private final InquiryStore store;
  private final InquiryPhotoStore photos;
  private final OrderClient orders;
  private final CaseProperties props;
  private final RequestIdempotency idempotency;
  private final TransactionTemplate tx;
  private final EventPublisher events;
  private final ProfanityGuard profanity;
  private final Clock clock;

  private final CustomerRestrictions restrictions; // 2026-11-23 CU-7
  private final MisdeliveryInquiryService misdelivery; // R-16 ADR-0086

  public InquiryService(
      InquiryStore store,
      InquiryPhotoStore photos,
      OrderClient orders,
      CaseProperties props,
      RequestIdempotency idempotency,
      TransactionTemplate tx,
      EventPublisher events,
      Clock clock,
      ProfanityGuard profanity,
      CustomerRestrictions restrictions,
      MisdeliveryInquiryService misdelivery) {
    this.misdelivery = misdelivery;
    this.restrictions = restrictions;
    this.profanity = profanity;
    this.store = store;
    this.photos = photos;
    this.orders = orders;
    this.props = props;
    this.idempotency = idempotency;
    this.tx = tx;
    this.events = events;
    this.clock = clock;
  }

  /**
   * 고객·라이더가 문의를 남긴다. 본인 주문 확인은 트랜잭션 밖(외부 호출). 라이더(0.13.0)는 배송과 상관없는 문의만 — 분류 SETTLEMENT·ACCOUNT·
   * APP_ERROR·OTHER, 주문 없음.
   */
  public View create(String requestId, String sourceSystem, CreateCommand cmd) {
    PmartPrincipal who = requireRequester();
    String customerId = who.subject();
    String requesterType =
        who.type() == PmartPrincipal.PrincipalType.RIDER
            ? InquiryRules.RIDER
            : InquiryRules.CUSTOMER;
    if (InquiryRules.CUSTOMER.equals(requesterType)) {
      restrictions.checkCanWrite(customerId); // 2026-11-23 CU-7 이용제한·정지 고객은 새 문의 403
    }
    if (!InquiryRules.categoryAllowed(requesterType, cmd.category())) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          InquiryRules.RIDER.equals(requesterType)
              ? "라이더 문의 분류는 SETTLEMENT·ACCOUNT·APP_ERROR·OTHER 입니다."
              : "고객 문의 분류가 아닙니다.");
    }
    InquiryRules.photoIdsProblem(cmd.photoIds(), 0)
        .ifPresent(
            m -> {
              throw new PmartException(ErrorCode.VALIDATION_FAILED, m);
            });
    // 배송 신고(2026-11-17): 고객의 배송 분류·주문 문의에서만
    if ((cmd.deliveryIssue() != null || blankToNull(cmd.deliveryId()) != null)
        && (!InquiryRules.CUSTOMER.equals(requesterType)
            || !"DELIVERY".equals(cmd.category())
            || blankToNull(cmd.orderId()) == null)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "배송 신고는 배송(DELIVERY) 분류와 주문이 있는 고객 문의에만 붙일 수 있습니다.");
    }
    if (InquiryRules.RIDER.equals(requesterType)
        && cmd.orderId() != null
        && !cmd.orderId().isBlank()) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED, "배송 건 문의는 배송 화면의 도움요청으로 남기세요(라이더 문의는 주문 없이).");
    }
    if (cmd.orderId() != null && !cmd.orderId().isBlank()) {
      if (props.orderServiceBaseUrl() == null) {
        throw PmartException.configRequired("pmart.cases.order-service-base-url");
      }
      var owned = orders.customerOrder(cmd.orderId());
      if (owned.isEmpty()) {
        throw new PmartException(
            "ORDER_NOT_FOUND",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "주문을 찾을 수 없습니다.",
            false,
            List.of());
      }
      // 배송 지연 신고(0.23.0): 같은 주문 조회의 상태로 — 확정 뒤 ~ 배송 완료 전만
      if (!InquiryRules.deliveryIssueAllowed(cmd.deliveryIssue(), owned.get().status())) {
        throw new PmartException(
            "ORDER_NOT_IN_PROGRESS",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "배송 지연 신고는 주문이 확정된 뒤 배송 완료 전까지만 남길 수 있습니다. 배송 완료 뒤 받지 못했다면 미수령(NOT_RECEIVED)으로 남겨 주세요.",
            false,
            List.of());
      }
    }
    // 0.37.0: 막기 단어(욕설)는 받지 않는다 — 422, 정지 없음
    profanity.rejectBlocked(cmd.title(), cmd.body());
    String inquiryId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createInquiry", customerId),
                cmd,
                String.class,
                () -> {
                  Instant now = clock.instant();
                  String id = "INQ-" + UUID.randomUUID();
                  String caseId = "CASE-" + UUID.randomUUID();
                  store.insert(
                      new InquiryRow(
                          id,
                          caseId,
                          customerId,
                          cmd.category(),
                          blankToNull(cmd.orderId()),
                          blankToNull(cmd.title()),
                          cmd.body(),
                          InquiryRules.origin(cmd.origin()),
                          InquiryRules.RECEIVED,
                          now,
                          null,
                          0,
                          null,
                          null,
                          requesterType,
                          blankToNull(cmd.deliveryId()),
                          cmd.deliveryIssue(),
                          null,
                          null));
                  attach(id, customerId, cmd.photoIds(), 0);
                  // R-16(ADR-0086): 받지 못함 + 전달 완료된 고객 배송 → 오배송 사건·선택 대기·채팅 안내(같은 트랜잭션)
                  if (InquiryRules.CUSTOMER.equals(requesterType)
                      && "NOT_RECEIVED".equals(cmd.deliveryIssue())
                      && blankToNull(cmd.orderId()) != null
                      && !CsIntakeRules.ORIGIN_STAFF_INTAKE.equals(
                          InquiryRules.origin(cmd.origin()))) {
                    misdelivery.openIfDelivered(
                        id, customerId, cmd.orderId().strip(), blankToNull(cmd.deliveryId()), now);
                  }
                  // 0.35.0 R-14·0.37.0: 정지 단어 → 정지 사건(고객 CU-7·라이더 PC 5). 문의는 그대로 받는다
                  profanity.reportSuspendable(
                      requesterType,
                      customerId,
                      id,
                      caseId,
                      "INQUIRY",
                      1L,
                      now,
                      cmd.title(),
                      cmd.body());
                  log.info(
                      "inquiry received id={} requester={} category={}",
                      id,
                      requesterType,
                      cmd.category());
                  return id;
                })
            .value();
    return view(inquiryId);
  }

  /**
   * 고객·라이더 = 본인 것, 운영자 = 볼 수 있는 종류(CS_AGENT 고객 문의, RIDER_OPS 라이더 문의, OPERATOR_ADMIN 전부 — 관리자는 보기만,
   * 2026-11-16 사용자 (가)).
   */
  public Page list(
      @Nullable String status,
      @Nullable String orderId,
      /** 직원 고객 거르기(0.29.0 — 백오피스 "고객" 상세 BF-2). 고객·라이더 토큰은 무시(늘 본인). */
      @Nullable String customerFilter,
      /** 직원 라이더 거르기(0.32.0 — R-11 라이더 백오피스 라이더 상세). 고객·라이더 토큰은 무시(늘 본인). */
      @Nullable String riderFilter,
      @Nullable String requesterType,
      @Nullable String deliveryIssue,
      @Nullable String cursor,
      int size) {
    PmartPrincipal p = principal();
    String customerId = null;
    boolean staffIntake = false;
    String type;
    if (p.type() == PmartPrincipal.PrincipalType.CUSTOMER) {
      customerId = p.subject();
      type = InquiryRules.CUSTOMER;
    } else if (p.type() == PmartPrincipal.PrincipalType.RIDER) {
      customerId = p.subject();
      type = InquiryRules.RIDER;
    } else {
      java.util.Set<String> readable = readableTypes(p);
      if (readable.isEmpty()) {
        throw new PmartException(ErrorCode.FORBIDDEN, "문의 목록을 볼 권한이 없습니다.");
      }
      String rider = blankToNull(riderFilter);
      if (rider != null
          && (blankToNull(customerFilter) != null
              || blankToNull(orderId) != null
              || deliveryIssue != null
              || InquiryRules.CUSTOMER.equals(requesterType))) {
        // 라이더 문의에는 고객·주문·배송 신고가 없다 — 같이 주면 늘 0건이라 잘못된 요청으로
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "riderId 는 고객 문의 거르기(customerId·orderId·deliveryIssue)와 같이 쓸 수 없습니다.");
      }
      if (rider != null && !readable.contains(InquiryRules.RIDER)) {
        // 못 보는 직원에게 0건으로 보이지 않게 403(customerId 와 같음)
        throw new PmartException(ErrorCode.FORBIDDEN, "라이더 문의를 볼 권한이 없습니다.");
      }
      if (requesterType != null && !readable.contains(requesterType)) {
        throw new PmartException(ErrorCode.FORBIDDEN, "그 종류의 문의를 볼 권한이 없습니다.");
      }
      // 주문으로 찾기는 고객 문의 — 고객 문의를 못 보는 직원은 예전처럼 403(통합 조회가 원천 불가로 표시)
      // 배송 신고(0.23.0 거르기)도 고객 문의에만 있다
      if ((blankToNull(orderId) != null
              || deliveryIssue != null
              || blankToNull(customerFilter) != null)
          && !readable.contains(InquiryRules.CUSTOMER)) {
        throw new PmartException(ErrorCode.FORBIDDEN, "고객 문의를 볼 권한이 없습니다.");
      }
      type =
          requesterType != null
              ? requesterType
              : readable.size() == 1 ? readable.iterator().next() : null;
      if (blankToNull(customerFilter) != null) {
        // 고객 문의만(라이더 문의의 customer_id 는 라이더 주체) — 상담원 접수 포함
        customerId = customerFilter.strip();
        staffIntake = true;
        type = InquiryRules.CUSTOMER;
      }
      if (rider != null) {
        // 라이더 문의의 customer_id 열 = 남긴 라이더(탈퇴해도 행은 그대로)
        customerId = rider.strip();
        type = InquiryRules.RIDER;
      }
    }
    List<InquiryRow> rows =
        store.page(
            customerId,
            staffIntake,
            type,
            status,
            blankToNull(orderId),
            deliveryIssue,
            cursor,
            size);
    boolean more = rows.size() > size;
    List<InquiryRow> items = more ? rows.subList(0, size) : rows;
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      // 0.36.0: 마지막으로 답한 직원 ID 는 직원 목록에만
      items = items.stream().map(InquiryRow::withoutStaff).toList();
    }
    return new Page(List.copyOf(items), more ? items.get(items.size() - 1).inquiryId() : null);
  }

  /** 남의 문의는 404(존재를 드러내지 않음). */
  public View get(String inquiryId) {
    PmartPrincipal p = principal();
    InquiryRow row = store.find(inquiryId).orElseThrow(() -> notFound(inquiryId));
    if (p.type() == PmartPrincipal.PrincipalType.CUSTOMER
        || p.type() == PmartPrincipal.PrincipalType.RIDER) {
      String mineType =
          p.type() == PmartPrincipal.PrincipalType.RIDER
              ? InquiryRules.RIDER
              : InquiryRules.CUSTOMER;
      // 상담원 접수(0.12.0)는 고객 앱에 보이지 않는다
      if (!row.customerId().equals(p.subject())
          || !mineType.equals(row.requesterType())
          || CsIntakeRules.ORIGIN_STAFF_INTAKE.equals(row.origin())) {
        throw notFound(inquiryId);
      }
    } else if (readableTypes(p).isEmpty()) {
      throw new PmartException(ErrorCode.FORBIDDEN, "문의를 볼 권한이 없습니다.");
    } else if (!readableTypes(p).contains(row.requesterType())) {
      throw notFound(inquiryId);
    }
    return new View(row, store.replies(inquiryId), photos.ofInquiry(inquiryId));
  }

  /** 상담원 답변 → ANSWERED. */
  public View reply(
      String requestId,
      String sourceSystem,
      String inquiryId,
      String body,
      @Nullable Integer expectedVersion) {
    PmartPrincipal p = principal();
    if (!isAgent(p) && !isRiderOps(p)) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, "문의 답변은 상담원(CS_AGENT)·지원센터(RIDER_OPS)만 할 수 있습니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "replyInquiry", p.subject()),
        new Object[] {inquiryId, body},
        String.class,
        () -> {
          InquiryRow row = store.lock(inquiryId).orElseThrow(() -> notFound(inquiryId));
          if (!p.hasRole(InquiryRules.answerRole(row.requesterType()))) {
            throw new PmartException(
                ErrorCode.FORBIDDEN,
                InquiryRules.RIDER.equals(row.requesterType())
                    ? "라이더 문의는 지원센터(RIDER_OPS)가 답합니다."
                    : "고객 문의는 상담원(CS_AGENT)이 답합니다.");
          }
          if (expectedVersion != null && expectedVersion != row.version()) {
            throw PmartException.versionConflict("문의가 그사이 바뀌었습니다. 다시 조회하세요.");
          }
          String rejected = InquiryRules.replyRejection(row.status());
          if (rejected != null) {
            throw new PmartException(
                rejected, HttpStatus.CONFLICT, "닫힌 문의에는 답변할 수 없습니다.", false, List.of());
          }
          Instant now = clock.instant();
          store.addReply(
              inquiryId,
              "RPL-" + UUID.randomUUID(),
              p.subject(),
              body,
              InquiryRules.afterReply(row.status()),
              now);
          // 상담원 접수(0.12.0)는 고객 앱에 없는 문의 — 알림을 내지 않는다
          if (CsIntakeRules.ORIGIN_STAFF_INTAKE.equals(row.origin())) {
            log.info("inquiry answered id={} (staff intake, no notification)", inquiryId);
            return inquiryId;
          }
          if (InquiryRules.RIDER.equals(row.requesterType())) {
            // 라이더 문의: 고객용 InquiryAnswered(customerId 필수) 대신 RiderInquiryAnswered — 라이더 알림함(§11-5).
            // 답변 내용 없음
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("inquiryId", inquiryId);
            r.put("caseId", row.caseId());
            r.put("riderId", row.customerId());
            r.put("answeredAt", now.toString());
            events.publish(
                "RiderInquiryAnswered",
                1,
                "Case",
                row.caseId(),
                row.version() + 1L,
                new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
                r);
            log.info("rider inquiry answered id={}", inquiryId);
            return inquiryId;
          }
          // 고객 알림(notification N-2, 2026-11-16). 답변 내용은 싣지 않는다
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("inquiryId", inquiryId);
          m.put("caseId", row.caseId());
          m.put("customerId", row.customerId());
          if (row.orderId() != null) {
            m.put("orderId", row.orderId());
          }
          m.put("answeredAt", now.toString());
          events.publish(
              "InquiryAnswered",
              1,
              "Case",
              row.caseId(),
              row.version() + 1L,
              new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, p.subject()),
              m);
          log.info("inquiry answered id={}", inquiryId);
          return inquiryId;
        });
    return view(inquiryId);
  }

  /**
   * 내가 올리고 아직 붙지 않은 사진만 문의에 붙인다(0.14.0). 하나라도 못 붙이면 422 PHOTO_NOT_FOUND — 호출한 트랜잭션째 되돌린다. 문의 행은 호출
   * 쪽이 잠갔다(새 문의는 방금 넣음).
   */
  void attach(String inquiryId, String ownerId, @Nullable List<String> photoIds, int already) {
    if (photoIds == null || photoIds.isEmpty()) {
      return;
    }
    if (photos.attach(inquiryId, ownerId, photoIds, already) != photoIds.size()) {
      throw new PmartException(
          "PHOTO_NOT_FOUND",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "내가 올린 사진이 아니거나 이미 다른 문의에 붙은 사진입니다.",
          false,
          List.of());
    }
  }

  private View view(String inquiryId) {
    return tx.execute(
        s ->
            new View(
                store.find(inquiryId).orElseThrow(() -> notFound(inquiryId)),
                store.replies(inquiryId),
                photos.ofInquiry(inquiryId)));
  }

  private static PmartPrincipal principal() {
    return PmartPrincipal.current()
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
  }

  /** 회원 고객(사용자 결정 C2)·라이더(0.13.0 — §11-4)만. 비회원·운영자 불가. */
  private static PmartPrincipal requireRequester() {
    PmartPrincipal p = principal();
    if (p.type() != PmartPrincipal.PrincipalType.CUSTOMER
        && p.type() != PmartPrincipal.PrincipalType.RIDER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "고객센터 문의는 로그인한 회원·라이더만 남길 수 있습니다.");
    }
    return p;
  }

  private static boolean isRiderOps(PmartPrincipal p) {
    return p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole(InquiryRules.RIDER_OPS);
  }

  /** 운영자가 볼 수 있는 문의 종류(InquiryRules.readableTypes). */
  private static java.util.Set<String> readableTypes(PmartPrincipal p) {
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      return java.util.Set.of();
    }
    java.util.Set<String> roles = new java.util.HashSet<>();
    for (String r :
        List.of(InquiryRules.CS_AGENT, InquiryRules.RIDER_OPS, InquiryRules.OPERATOR_ADMIN)) {
      if (p.hasRole(r)) {
        roles.add(r);
      }
    }
    return InquiryRules.readableTypes(roles);
  }

  private static boolean isAgent(PmartPrincipal p) {
    return p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole(InquiryRules.CS_AGENT);
  }

  private static PmartException notFound(String inquiryId) {
    return PmartException.notFound("INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다: " + inquiryId);
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  /** 설정·시계. */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(CaseProperties.class)
  static class Config {
    @Bean
    Clock caseClock() {
      return Clock.systemUTC();
    }
  }
}
