package com.pmart.cases.application;

import com.pmart.cases.domain.InquiryRules;
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
      @Nullable String origin) {}

  public record View(InquiryRow inquiry, List<ReplyRow> replies) {}

  public record Page(List<InquiryRow> items, @Nullable String nextCursor) {}

  private final InquiryStore store;
  private final OrderClient orders;
  private final CaseProperties props;
  private final RequestIdempotency idempotency;
  private final TransactionTemplate tx;
  private final EventPublisher events;
  private final Clock clock;

  public InquiryService(
      InquiryStore store,
      OrderClient orders,
      CaseProperties props,
      RequestIdempotency idempotency,
      TransactionTemplate tx,
      EventPublisher events,
      Clock clock) {
    this.store = store;
    this.orders = orders;
    this.props = props;
    this.idempotency = idempotency;
    this.tx = tx;
    this.events = events;
    this.clock = clock;
  }

  /** 고객이 문의를 남긴다. 본인 주문 확인은 트랜잭션 밖(외부 호출). */
  public View create(String requestId, String sourceSystem, CreateCommand cmd) {
    String customerId = requireCustomer();
    if (cmd.orderId() != null && !cmd.orderId().isBlank()) {
      if (props.orderServiceBaseUrl() == null) {
        throw PmartException.configRequired("pmart.cases.order-service-base-url");
      }
      if (!orders.ownsOrder(cmd.orderId())) {
        throw new PmartException(
            "ORDER_NOT_FOUND",
            HttpStatus.UNPROCESSABLE_CONTENT,
            "주문을 찾을 수 없습니다.",
            false,
            List.of());
      }
    }
    String inquiryId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "createInquiry", customerId),
                cmd,
                String.class,
                () -> {
                  Instant now = clock.instant();
                  String id = "INQ-" + UUID.randomUUID();
                  store.insert(
                      new InquiryRow(
                          id,
                          "CASE-" + UUID.randomUUID(),
                          customerId,
                          cmd.category(),
                          blankToNull(cmd.orderId()),
                          blankToNull(cmd.title()),
                          cmd.body(),
                          InquiryRules.origin(cmd.origin()),
                          InquiryRules.RECEIVED,
                          now,
                          null,
                          0));
                  log.info("inquiry received id={} category={}", id, cmd.category());
                  return id;
                })
            .value();
    return view(inquiryId);
  }

  /** 고객 = 본인 것, CS_AGENT·OPERATOR_ADMIN = 전체(관리자는 보기만 — 2026-11-16 사용자 (가)). */
  public Page list(@Nullable String status, @Nullable String cursor, int size) {
    PmartPrincipal p = principal();
    String customerId = null;
    if (p.type() == PmartPrincipal.PrincipalType.CUSTOMER) {
      customerId = p.subject();
    } else if (!canRead(p)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "문의 목록을 볼 권한이 없습니다.");
    }
    List<InquiryRow> rows = store.page(customerId, status, cursor, size);
    boolean more = rows.size() > size;
    List<InquiryRow> items = more ? rows.subList(0, size) : rows;
    return new Page(List.copyOf(items), more ? items.get(items.size() - 1).inquiryId() : null);
  }

  /** 남의 문의는 404(존재를 드러내지 않음). */
  public View get(String inquiryId) {
    PmartPrincipal p = principal();
    InquiryRow row = store.find(inquiryId).orElseThrow(() -> notFound(inquiryId));
    if (p.type() == PmartPrincipal.PrincipalType.CUSTOMER) {
      if (!row.customerId().equals(p.subject())) {
        throw notFound(inquiryId);
      }
    } else if (!canRead(p)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "문의를 볼 권한이 없습니다.");
    }
    return new View(row, store.replies(inquiryId));
  }

  /** 상담원 답변 → ANSWERED. */
  public View reply(
      String requestId,
      String sourceSystem,
      String inquiryId,
      String body,
      @Nullable Integer expectedVersion) {
    PmartPrincipal p = principal();
    if (!isAgent(p)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "문의 답변은 상담원(CS_AGENT)만 할 수 있습니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "replyInquiry", p.subject()),
        new Object[] {inquiryId, body},
        String.class,
        () -> {
          InquiryRow row = store.lock(inquiryId).orElseThrow(() -> notFound(inquiryId));
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

  private View view(String inquiryId) {
    return tx.execute(
        s ->
            new View(
                store.find(inquiryId).orElseThrow(() -> notFound(inquiryId)),
                store.replies(inquiryId)));
  }

  private static PmartPrincipal principal() {
    return PmartPrincipal.current()
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
  }

  /** 회원 고객만(비회원·운영자·라이더 불가 — 사용자 결정 C2). */
  private static String requireCustomer() {
    PmartPrincipal p = principal();
    if (p.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "고객센터 문의는 로그인한 회원만 남길 수 있습니다.");
    }
    return p.subject();
  }

  private static boolean isAgent(PmartPrincipal p) {
    return p.type() == PmartPrincipal.PrincipalType.OPERATOR && p.hasRole(InquiryRules.CS_AGENT);
  }

  /** 운영자 보기: 상담원 + 본사 관리자(보기만, 답변은 상담원 — 2026-11-16 사용자 (가)). */
  private static boolean canRead(PmartPrincipal p) {
    return isAgent(p)
        || (p.type() == PmartPrincipal.PrincipalType.OPERATOR
            && p.hasRole(InquiryRules.OPERATOR_ADMIN));
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
