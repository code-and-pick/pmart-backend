package com.pmart.customerassistant.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customerassistant.domain.OrderStatusText;
import com.pmart.customerassistant.domain.ProductAnswerText;
import com.pmart.customerassistant.domain.ProductQuestion;
import com.pmart.customerassistant.domain.ProfanityRules;
import com.pmart.customerassistant.domain.QuestionClassifier;
import com.pmart.customerassistant.domain.QuestionClassifier.Classification;
import com.pmart.customerassistant.domain.QuestionClassifier.Intent;
import com.pmart.customerassistant.domain.RateLimit;
import com.pmart.customerassistant.domain.RequestHashing;
import com.pmart.customerassistant.domain.SourceSystemPolicy;
import com.pmart.customerassistant.infrastructure.AnswerStore;
import com.pmart.customerassistant.infrastructure.AnswerStore.AnswerRow;
import com.pmart.customerassistant.infrastructure.AnswerStore.Completion;
import com.pmart.customerassistant.infrastructure.AnswerStore.PinResult;
import com.pmart.customerassistant.infrastructure.OrderClient;
import com.pmart.customerassistant.infrastructure.OrderClient.OrderView;
import com.pmart.customerassistant.infrastructure.ProductSearchClient;
import com.pmart.customerassistant.infrastructure.SnapshotStore;
import com.pmart.customerassistant.infrastructure.SnapshotStore.SnapshotRow;
import com.pmart.customerassistant.infrastructure.embedding.DescribedEmbeddingModel;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객 질문 유스케이스(ADR-0051, 운영자용 PolicyQuestionService 에서 출발). 트랜잭션 경계는 운영자용과 같다:
 *
 * <ol>
 *   <li>검증: 회원 고객·출처 → 정규화 → request_hash(질문 + 고른 주문)
 *   <li>같은 키면 저장 상태로 응답(요청 제한에 세지 않음). 새 키면 요청 제한(C3) → 고른 주문이 본인 주문인지(C6)
 *   <li>선점(짧은 TX) — 질문 원문은 저장하지 않는다(C5)
 *   <li>분류(모델 없음): 실행 요청 → OUT_OF_SCOPE, 주문 상태 질문 → 주문을 골랐으면 ORDER_STATUS(정해진 문장), 아니면
 *       REALTIME_DATA_REQUIRED(최근 주문에서 고르게)
 *   <li>스냅숏 고정 → 트랜잭션 밖 검색·생성(하드 타임아웃 &lt; lease) → 완료 기록(선점 조건부)
 * </ol>
 *
 * 질문·문서 본문·토큰·주문 내용은 로그에 남기지 않는다(답변 ID·상태·소요 시간·청크 수만).
 */
public class CustomerQuestionService {

  private static final Logger log = LoggerFactory.getLogger(CustomerQuestionService.class);

  static final String REALTIME_TEXT = "어떤 주문이 궁금하신가요? 아래 최근 주문에서 골라 주시면 그 주문의 상태를 알려 드릴게요.";
  static final String OUT_OF_SCOPE_TEXT =
      "주문 취소·환불·반품은 AI 상담이 직접 처리하지 않아요. 앱의 주문 내역에서 직접 하시거나, 고객센터에 문의를 남겨 주세요.";

  public static final String CONFIG_REQUIRED = "CONFIG_REQUIRED";
  public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";

  /** 응답: 완료(200) 또는 처리 중(202). 그 밖은 PmartException·{@link RateLimitedException}. */
  public sealed interface Outcome {
    AnswerRow row();

    List<CitationRecord> citations();

    record Completed(AnswerRow row, List<CitationRecord> citations) implements Outcome {}

    record Processing(AnswerRow row, List<CitationRecord> citations) implements Outcome {}
  }

  /** 요청 제한(C3) 초과 → 429 + Retry-After. */
  public static final class RateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    RateLimitedException(String message, long retryAfterSeconds) {
      super(message);
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }

  enum ReadMode {
    POST,
    GET
  }

  private final AnswerStore answers;
  private final SnapshotStore snapshots;
  private final Retriever retriever;
  private final AnswerGenerator generator;
  private final DescribedEmbeddingModel embeddings;
  private final AccessPolicy access;
  private final AssistantProperties props;
  private final ExecutorService executor;
  private final ObjectMapper json;
  private final Clock clock;
  private final OrderClient orders;
  private final ProductSearchClient products;
  private final AnswerMetrics metrics;

  public CustomerQuestionService(
      AnswerStore answers,
      SnapshotStore snapshots,
      Retriever retriever,
      AnswerGenerator generator,
      DescribedEmbeddingModel embeddings,
      AccessPolicy access,
      AssistantProperties props,
      ExecutorService executor,
      ObjectMapper json,
      Clock clock,
      OrderClient orders,
      ProductSearchClient products,
      AnswerMetrics metrics) {
    this.metrics = metrics;
    this.answers = answers;
    this.snapshots = snapshots;
    this.retriever = retriever;
    this.generator = generator;
    this.embeddings = embeddings;
    this.access = access;
    this.props = props;
    this.executor = executor;
    this.json = json;
    this.clock = clock;
    this.orders = orders;
    this.products = products;
  }

  public Outcome ask(
      @Nullable PmartPrincipal principal,
      @Nullable String sourceHeader,
      String requestId,
      String question,
      @Nullable String orderId) {
    PmartPrincipal p = requireCustomerAndSource(principal, sourceHeader);
    rejectBlocked(question);
    String src = sourceHeader;
    String order = orderId == null || orderId.isBlank() ? null : orderId.strip();
    String requestHash = RequestHashing.requestHash(question, order);
    Instant now = clock.instant();

    // 같은 키: 저장 상태 재응답(요청 제한·주문 확인을 다시 하지 않는다)
    Optional<AnswerRow> prior = answers.findByRequest(src, p.subject(), requestId);
    if (prior.isPresent()) {
      Outcome again = retryExisting(prior.get(), requestHash, p, now);
      if (again != null) {
        return again;
      }
      return process(p, prior.get().answerId(), prior.get().attempt() + 1, question, order, null);
    }

    checkRateLimit(p.subject(), now);
    OrderView chosen = null;
    if (order != null) {
      chosen =
          orders
              .get(order)
              .orElseThrow(
                  () ->
                      new PmartException(
                          "ORDER_NOT_FOUND",
                          HttpStatus.UNPROCESSABLE_CONTENT,
                          "주문을 찾을 수 없습니다.",
                          false,
                          List.of()));
    }

    String answerId = "CANS-" + UUID.randomUUID();
    Instant lease = now.plus(props.getProcessing().getLease());
    if (!answers.insertClaim(
        answerId, src, p.subject(), requestId, requestHash, lease, order, now)) {
      // 같은 키가 동시에 들어왔다 — 먼저 들어온 쪽 기준
      AnswerRow existing =
          answers
              .findByRequest(src, p.subject(), requestId)
              .orElseThrow(() -> PmartException.dependencyUnavailable("선점 기록을 읽지 못했습니다.", null));
      Outcome again = retryExisting(existing, requestHash, p, now);
      if (again != null) {
        return again;
      }
      return process(p, existing.answerId(), existing.attempt() + 1, question, order, null);
    }
    return process(p, answerId, 1, question, order, chosen);
  }

  /** 같은 키 재전송: 응답할 저장 상태가 있으면 그것, 다시 처리해야 하면(만료·실패 재선점 성공) null. */
  private @Nullable Outcome retryExisting(
      AnswerRow existing, String requestHash, PmartPrincipal p, Instant now) {
    if (!existing.requestHash().equals(requestHash)) {
      throw PmartException.idempotencyConflict("같은 Idempotency-Key 를 다른 질문·주문에 썼습니다.");
    }
    if (existing.state() == AnswerStore.State.COMPLETED || existing.leaseValid(now)) {
      return stored(existing, p, ReadMode.POST);
    }
    Instant lease = now.plus(props.getProcessing().getLease());
    if (!answers.reclaim(existing.answerId(), existing.attempt(), lease, now)) {
      return reread(existing.answerId(), p, ReadMode.POST);
    }
    return null;
  }

  /**
   * CA-2 막기 단어(욕설): 받지 않는다 — 422 BLOCKED_WORDS, 정지 없음. 멱등 기록·요청 제한보다 먼저 불러 질문·요청 해시를 남기지 않는다(같은 키로
   * 고쳐 보내도 된다). 질문·걸린 단어는 응답·로그에 싣지 않는다.
   */
  private void rejectBlocked(String question) {
    if (ProfanityRules.contains(props.getCustomer().getBlockedWords(), question)) {
      log.info("customer-assistant question rejected reason=blocked_words");
      metrics.exit("BLOCKED_WORDS");
      throw new PmartException(
          "BLOCKED_WORDS",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "쓸 수 없는 말이 들어 있어요. 고쳐서 다시 보내 주세요.",
          false,
          List.of());
    }
  }

  private void checkRateLimit(String subject, Instant now) {
    var c = props.getCustomer();
    RateLimit limit = new RateLimit(c.getRatePerMinute(), c.getRatePerDay());
    Instant minute = RateLimit.minuteStart(now);
    RateLimit.Decision d =
        limit.decide(
            now,
            answers.countSince(subject, minute),
            answers.earliestSince(subject, minute).orElse(null),
            answers.countSince(subject, RateLimit.dayStart(now)));
    if (!d.allowed()) {
      log.info("customer-assistant rate limited window={}", d.window());
      throw new RateLimitedException(
          "DAY".equals(d.window())
              ? "오늘 질문할 수 있는 횟수를 모두 썼어요. 내일 다시 이용해 주세요."
              : "질문이 너무 빨라요. 잠시 뒤 다시 질문해 주세요.",
          d.retryAfterSeconds());
    }
  }

  private Outcome process(
      PmartPrincipal p,
      String answerId,
      int attempt,
      String question,
      @Nullable String orderId,
      @Nullable OrderView chosen) {
    long started = System.nanoTime();
    Classification cls = QuestionClassifier.classify(question);
    if (cls.intent() == Intent.OUT_OF_SCOPE) {
      metrics.exit("OUT_OF_SCOPE");
      return finish(
          p,
          answerId,
          attempt,
          new Completion("OUT_OF_SCOPE", OUT_OF_SCOPE_TEXT, "[]", null, "NONE", "NONE", null, null),
          started,
          0);
    }
    if (cls.intent() == Intent.REALTIME_DATA_REQUIRED) {
      if (orderId == null) {
        metrics.exit("REALTIME_DATA_REQUIRED");
        return finish(
            p,
            answerId,
            attempt,
            new Completion(
                "REALTIME_DATA_REQUIRED", REALTIME_TEXT, "[]", null, "NONE", "NONE", null, null),
            started,
            0);
      }
      OrderView o = chosen != null ? chosen : orders.get(orderId).orElse(null);
      if (o == null) {
        return failed(
            p, answerId, attempt, DEPENDENCY_UNAVAILABLE, "주문을 다시 확인하지 못했습니다. 같은 키로 다시 보내세요.");
      }
      metrics.exit("ORDER_STATUS");
      return finish(
          p,
          answerId,
          attempt,
          new Completion(
              "ORDER_STATUS",
              OrderStatusText.sentence(o.status(), o.window()),
              "[]",
              null,
              "ORDER_TEMPLATE",
              "NONE",
              null,
              null),
          started,
          0);
    }

    // 상품 질문(2026-11-20, ADR-0083): 상품 검색 결과를 정해진 문장으로. 결과가 없거나 검색이 안 되면 안내 문서 검색으로 넘어간다
    Optional<Completion> product = productAnswer(answerId, question);
    if (product.isPresent()) {
      metrics.exit("PRODUCT_INFO");
      return finish(p, answerId, attempt, product.get(), started, 0);
    }

    PinResult pin = answers.pinActiveSnapshot(answerId, attempt, snapshots);
    SnapshotRow snapshot;
    switch (pin) {
      case PinResult.Pinned pinned -> snapshot = pinned.snapshot();
      case PinResult.LeaseLost lost -> {
        log.info("customer-assistant answer={} lease lost before pin", answerId);
        return reread(answerId, p, ReadMode.POST);
      }
      case PinResult.NoActiveIndex none -> {
        return failed(
            p, answerId, attempt, CONFIG_REQUIRED, "활성 안내 문서 색인이 없습니다. 색인 작업 뒤 같은 키로 다시 보내세요.");
      }
    }

    Set<String> scopes = access.allowedScopes(p);
    AnswerGenerator.Generated generated;
    String retrievalMode = embeddings.spec().semantic() ? "SEMANTIC" : "BASELINE_HASH";
    double[] top = {Double.NaN};
    String[] sections = {""};
    Future<AnswerGenerator.Generated> work =
        executor.submit(
            () -> {
              Retriever.Retrieval r =
                  retriever.retrieve(
                      snapshot,
                      com.pmart.customerassistant.domain.ProductQuestion.retrievalQuery(question),
                      false,
                      scopes);
              top[0] = r.topSimilarity();
              sections[0] = r.topSections();
              return generator.generate(question, r.ranked(), r.companions());
            });
    try {
      generated = work.get(props.getProcessing().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      work.cancel(true);
      log.warn("customer-assistant answer={} timed out", answerId);
      return failed(p, answerId, attempt, DEPENDENCY_UNAVAILABLE, "처리 시간이 초과됐습니다. 같은 키로 다시 보내세요.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed(p, answerId, attempt, DEPENDENCY_UNAVAILABLE, "처리가 중단됐습니다. 같은 키로 다시 보내세요.");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Retriever.SnapshotModelMismatchException) {
        return failed(p, answerId, attempt, CONFIG_REQUIRED, "색인과 임베딩 모델 설정이 다릅니다. 재색인 뒤 다시 보내세요.");
      }
      log.warn(
          "customer-assistant answer={} dependency failure {}",
          answerId,
          e.getCause().getClass().getSimpleName());
      return failed(p, answerId, attempt, DEPENDENCY_UNAVAILABLE, "검색·모델 장애입니다. 같은 키로 다시 보내세요.");
    }

    // 임계값 맞추기용: 1위 후보 유사도(숫자만 — 질문·본문은 남기지 않는다)
    log.info(
        "customer-assistant answer={} top={} mode={} sections={}",
        answerId,
        String.format(java.util.Locale.ROOT, "%.3f", top[0]),
        generated.mode(),
        sections[0]);
    metrics.generated(generated);
    List<CitationRecord> citations =
        generated.used().stream().map(c -> CitationRecord.of(c, snapshot)).toList();
    Completion completion =
        new Completion(
            generated.grounded() ? "ANSWERED" : "NO_GROUNDING",
            generated.text(),
            json.writeValueAsString(citations),
            snapshot.snapshotId(),
            generated.mode().name(),
            retrievalMode,
            generated.chatModel(),
            embeddings.spec().describe());
    return finish(p, answerId, attempt, completion, started, citations.size());
  }

  /** 상품 질문이고 검색 결과가 있으면 PRODUCT_INFO 완료값. 아니면 비어 있다(검색어·질문은 로그에 남기지 않는다). */
  private Optional<Completion> productAnswer(String answerId, String question) {
    if (!products.enabled()) {
      return Optional.empty();
    }
    Optional<String> keyword = ProductQuestion.keyword(question);
    if (keyword.isEmpty()) {
      return Optional.empty();
    }
    Optional<ProductSearchClient.Result> found;
    try {
      found = products.search(keyword.get(), null);
    } catch (RuntimeException e) {
      log.warn(
          "customer-assistant answer={} product search failed {} — 안내 문서로",
          answerId,
          e.getClass().getSimpleName());
      return Optional.empty();
    }
    if (found.isEmpty() || found.get().items().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Completion(
            "PRODUCT_INFO",
            ProductAnswerText.customer(keyword.get(), found.get().items()),
            "[]",
            null,
            "PRODUCT_TEMPLATE",
            "NONE",
            null,
            null));
  }

  private Outcome finish(
      PmartPrincipal p, String answerId, int attempt, Completion c, long started, int chunks) {
    boolean mine = answers.complete(answerId, attempt, c, clock.instant());
    log.info(
        "customer-assistant answer={} state={} chunks={} tookMs={} written={}",
        answerId,
        c.resultState(),
        chunks,
        (System.nanoTime() - started) / 1_000_000,
        mine);
    if (!mine) {
      return reread(answerId, p, ReadMode.POST);
    }
    AnswerRow row = answers.findById(answerId).orElseThrow();
    return new Outcome.Completed(row, citations(row));
  }

  private Outcome failed(
      PmartPrincipal p, String answerId, int attempt, String code, String message) {
    metrics.exit("FAILED_" + code);
    if (!answers.fail(answerId, attempt, code)) {
      return reread(answerId, p, ReadMode.POST);
    }
    throw error(code, message);
  }

  // ---- 조회 ----

  public Outcome findByRequestId(
      @Nullable PmartPrincipal principal, @Nullable String sourceHeader, String requestId) {
    PmartPrincipal p = requireCustomerAndSource(principal, sourceHeader);
    AnswerRow row =
        answers
            .findByRequest(sourceHeader, p.subject(), requestId)
            .orElseThrow(CustomerQuestionService::notFound);
    return stored(row, p, ReadMode.GET);
  }

  public Outcome getAnswer(@Nullable PmartPrincipal principal, String answerId) {
    PmartPrincipal p = requireCustomer(principal);
    AnswerRow row = answers.findById(answerId).orElseThrow(CustomerQuestionService::notFound);
    if (!row.askerSubject().equals(p.subject())) {
      throw notFound();
    }
    return stored(row, p, ReadMode.GET);
  }

  /** "주문 건에 대한 문의"에서 고를 최근 주문(C6). 요청 제한에 세지 않는다. */
  public List<OrderView> recentOrders(@Nullable PmartPrincipal principal, int size) {
    requireCustomer(principal);
    return orders.recent(Math.max(1, Math.min(10, size)));
  }

  /** 응답에 붙일 주문 정보 — 응답 때마다 새로 읽는다(저장하지 않음). 읽지 못하면 비어 있다(답변은 그대로 준다). */
  public Optional<OrderView> currentOrder(@Nullable String orderId) {
    if (orderId == null) {
      return Optional.empty();
    }
    try {
      return orders.get(orderId);
    } catch (RuntimeException e) {
      log.info("customer-assistant order lookup failed {}", e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /** REALTIME_DATA_REQUIRED 응답에 붙일 최근 주문. 읽지 못하면 빈 목록(앱은 주문 내역으로 안내). */
  public List<OrderView> recentForAnswer() {
    try {
      return orders.recent(props.getCustomer().getRecentOrders());
    } catch (RuntimeException e) {
      log.info("customer-assistant recent orders failed {}", e.getClass().getSimpleName());
      return List.of();
    }
  }

  private Outcome reread(String answerId, PmartPrincipal p, ReadMode mode) {
    return stored(
        answers.findById(answerId).orElseThrow(CustomerQuestionService::notFound), p, mode);
  }

  /** 저장 상태대로 응답한다 — 저장 답변을 돌려주는 모든 경로가 여기를 지난다(현재 권한 재검사, 아니면 404). */
  Outcome stored(AnswerRow row, PmartPrincipal p, ReadMode mode) {
    Instant now = clock.instant();
    return switch (row.state()) {
      case COMPLETED -> {
        List<CitationRecord> cites = citations(row);
        if (!access.stillAllowed(
            p, row.askerSubject(), cites.stream().map(CitationRecord::accessScope).toList())) {
          throw notFound();
        }
        yield new Outcome.Completed(row, cites);
      }
      case PROCESSING -> {
        if (!row.askerSubject().equals(p.subject())) {
          throw notFound();
        }
        if (row.leaseValid(now)) {
          yield new Outcome.Processing(row, List.of());
        }
        throw retryWithPost("처리 중이던 시도의 lease 가 만료됐습니다.");
      }
      case FAILED_RETRYABLE -> {
        if (!row.askerSubject().equals(p.subject())) {
          throw notFound();
        }
        if (mode == ReadMode.POST) {
          String code = row.lastErrorCode() == null ? DEPENDENCY_UNAVAILABLE : row.lastErrorCode();
          throw error(code, "직전 시도가 실패했습니다(" + code + "). 같은 키로 다시 보내세요.");
        }
        throw retryWithPost("직전 시도가 실패했습니다(" + row.lastErrorCode() + ").");
      }
    };
  }

  List<CitationRecord> citations(AnswerRow row) {
    if (row.citationsJson() == null || row.citationsJson().isBlank()) {
      return List.of();
    }
    return json.readValue(row.citationsJson(), new TypeReference<List<CitationRecord>>() {});
  }

  // ---- 검사·오류 ----

  private PmartPrincipal requireCustomerAndSource(
      @Nullable PmartPrincipal principal, @Nullable String sourceHeader) {
    PmartPrincipal p = requireCustomer(principal);
    if (!SourceSystemPolicy.accepts(p.sourceSystem(), sourceHeader)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "X-Source-System 이 인증된 출처와 다릅니다.");
    }
    return p;
  }

  /** 회원 고객만(C2). 비회원(GUEST)·운영자는 403. */
  private static PmartPrincipal requireCustomer(@Nullable PmartPrincipal principal) {
    if (principal == null || principal.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인한 회원만 이용할 수 있습니다.");
    }
    return principal;
  }

  private static PmartException notFound() {
    return PmartException.notFound(ErrorCode.NOT_FOUND.name(), "답변을 찾을 수 없습니다.");
  }

  private static PmartException retryWithPost(String why) {
    return new PmartException(
        ErrorCode.INVALID_STATE.name(),
        HttpStatus.CONFLICT,
        why + " 같은 Idempotency-Key·같은 본문으로 POST 를 다시 보내세요.",
        true,
        List.of());
  }

  private static PmartException error(String code, String message) {
    return CONFIG_REQUIRED.equals(code)
        ? new PmartException(ErrorCode.CONFIG_REQUIRED, message)
        : new PmartException(
            ErrorCode.DEPENDENCY_UNAVAILABLE.name(),
            HttpStatus.SERVICE_UNAVAILABLE,
            message,
            true,
            List.of());
  }
}
