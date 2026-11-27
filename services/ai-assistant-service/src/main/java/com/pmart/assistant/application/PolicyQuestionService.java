package com.pmart.assistant.application;

import com.pmart.assistant.domain.FollowUp;
import com.pmart.assistant.domain.HistoryPager;
import com.pmart.assistant.domain.ProductAnswerText;
import com.pmart.assistant.domain.ProductQuestion;
import com.pmart.assistant.domain.QuestionClassifier;
import com.pmart.assistant.domain.QuestionClassifier.Classification;
import com.pmart.assistant.domain.QuestionClassifier.Intent;
import com.pmart.assistant.domain.RequestHashing;
import com.pmart.assistant.domain.SourceSystemPolicy;
import com.pmart.assistant.infrastructure.AnswerStore;
import com.pmart.assistant.infrastructure.AnswerStore.AnswerRow;
import com.pmart.assistant.infrastructure.AnswerStore.Completion;
import com.pmart.assistant.infrastructure.AnswerStore.PinResult;
import com.pmart.assistant.infrastructure.ProductSearchClient;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.assistant.infrastructure.SnapshotStore.SnapshotRow;
import com.pmart.assistant.infrastructure.embedding.DescribedEmbeddingModel;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
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
 * 정책 질의 유스케이스. 트랜잭션 경계(검토 4):
 *
 * <ol>
 *   <li>검증: 운영자·출처 → 정규화 → request_hash
 *   <li>선점(짧은 TX): INSERT PROCESSING 또는 만료·실패 행 조건부 재선점(attempt+1)
 *   <li>분류(모델 없음): 실행 요청·실시간 질문은 바로 완료
 *   <li>스냅숏 고정(한 TX, 전환 잠금 공유, 선점 조건부) — 0건이면 lease 상실 → 중단, 저장 상태로 응답
 *   <li>트랜잭션 밖: 임베딩·검색·답변·인용 검사(하드 타임아웃 &lt; lease)
 *   <li>완료·실패 기록(짧은 TX, 선점 조건부) — 0건이면 내 결과를 버리고 저장 상태로 응답
 * </ol>
 *
 * 질문·문서 본문·토큰은 로그에 남기지 않는다(답변 ID·상태·소요 시간·청크 수만).
 */
public class PolicyQuestionService {

  private static final Logger log = LoggerFactory.getLogger(PolicyQuestionService.class);

  static final String REALTIME_TEXT = "특정 주문·재고·결제의 현재 상태는 이 기능으로 확인할 수 없습니다. 해당 업무 화면에서 조회하세요.";
  static final String OUT_OF_SCOPE_TEXT = "환불·승인·재고 변경 같은 처리는 이 기능이 하지 않습니다. 해당 업무 화면에서 진행하세요.";

  public static final String CONFIG_REQUIRED = "CONFIG_REQUIRED";
  public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";

  /** 앞 대화로 넘기는 최근 턴 수(ADR-0084) — 문맥 창을 근거 문서에 남긴다. */
  static final int HISTORY_TURNS = 2;

  /** 응답: 완료(200) 또는 처리 중(202). 그 밖은 PmartException. */
  public sealed interface Outcome {
    AnswerRow row();

    List<CitationRecord> citations();

    record Completed(AnswerRow row, List<CitationRecord> citations) implements Outcome {}

    record Processing(AnswerRow row, List<CitationRecord> citations) implements Outcome {}
  }

  /** 저장 상태 응답 방식: POST(lease 상실 재조회 — 실패는 422/503) vs GET(실패는 409 재시도 안내). */
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
  private final ProductSearchClient products;
  private final AnswerMetrics metrics;

  public PolicyQuestionService(
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
      ProductSearchClient products) {
    this(
        answers,
        snapshots,
        retriever,
        generator,
        embeddings,
        access,
        props,
        executor,
        json,
        clock,
        products,
        AnswerMetrics.none());
  }

  public PolicyQuestionService(
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
    this.products = products;
  }

  public Outcome ask(
      @Nullable PmartPrincipal principal,
      @Nullable String sourceHeader,
      String requestId,
      String question,
      @Nullable String screen,
      @Nullable String centerId,
      @Nullable String sessionId) {
    PmartPrincipal p = requireOperatorAndSource(principal, sourceHeader);
    String src = sourceHeader;
    String chosen = sessionId == null || sessionId.isBlank() ? null : sessionId.strip();
    String requestHash = RequestHashing.requestHash(question, screen, centerId, chosen);
    Instant now = clock.instant();
    Instant lease = now.plus(props.getProcessing().getLease());

    // 채팅 세션(ADR-0084): 고른 세션은 본인(출처·주체) 것이어야 한다 — 아니면 없는 것과 같다(404). 고르지 않으면 새 세션
    if (chosen != null) {
      var s = answers.findSession(chosen).orElseThrow(PolicyQuestionService::sessionNotFound);
      if (!s.ownerSubject().equals(p.subject()) || !s.sourceSystem().equals(src)) {
        throw sessionNotFound();
      }
    }
    String answerId = "ANS-" + UUID.randomUUID();
    String session = chosen != null ? chosen : "SES-" + answerId.substring(4);
    int attempt;
    if (answers.insertClaim(
        answerId,
        src,
        p.subject(),
        requestId,
        requestHash,
        lease,
        props.getAnswers().isStoreQuestion() ? RequestHashing.normalize(question) : null,
        now,
        session,
        chosen == null)) {
      attempt = 1;
    } else {
      AnswerRow existing =
          answers
              .findByRequest(src, p.subject(), requestId)
              .orElseThrow(() -> PmartException.dependencyUnavailable("선점 기록을 읽지 못했습니다.", null));
      if (!existing.requestHash().equals(requestHash)) {
        throw PmartException.idempotencyConflict("같은 Idempotency-Key 를 다른 질문·context 에 썼습니다.");
      }
      if (existing.state() == AnswerStore.State.COMPLETED || existing.leaseValid(now)) {
        return stored(existing, p, ReadMode.POST);
      }
      if (!answers.reclaim(existing.answerId(), existing.attempt(), lease, now)) {
        return reread(existing.answerId(), p, ReadMode.POST);
      }
      answerId = existing.answerId();
      attempt = existing.attempt() + 1;
    }
    return process(p, answerId, attempt, question, centerId);
  }

  private Outcome process(
      PmartPrincipal p, String answerId, int attempt, String question, @Nullable String centerId) {
    long started = System.nanoTime();
    Classification cls = QuestionClassifier.classify(question);
    if (cls.intent() != Intent.POLICY) {
      String state = cls.intent().name();
      metrics.exit(state);
      String text =
          cls.intent() == Intent.REALTIME_DATA_REQUIRED ? REALTIME_TEXT : OUT_OF_SCOPE_TEXT;
      return finish(
          p,
          answerId,
          attempt,
          new Completion(state, text, "[]", null, "NONE", "NONE", null, null),
          started,
          0);
    }

    // 상품 질문(2026-11-20, ADR-0083): 상품 검색 결과를 정해진 문장으로. 결과가 없거나 검색이 안 되면 문서 검색으로 넘어간다
    Optional<Completion> product = productAnswer(answerId, question, centerId);
    if (product.isPresent()) {
      metrics.exit("PRODUCT_INFO");
      return finish(p, answerId, attempt, product.get(), started, 0);
    }

    PinResult pin = answers.pinActiveSnapshot(answerId, attempt, snapshots);
    SnapshotRow snapshot;
    switch (pin) {
      case PinResult.Pinned pinned -> snapshot = pinned.snapshot();
      case PinResult.LeaseLost lost -> {
        log.info("assistant answer={} lease lost before pin", answerId);
        return reread(answerId, p, ReadMode.POST);
      }
      case PinResult.NoActiveIndex none -> {
        return failed(
            p, answerId, attempt, CONFIG_REQUIRED, "활성 문서 색인이 없습니다. 색인 작업 뒤 같은 키로 다시 보내세요.");
      }
    }

    Set<String> scopes = access.allowedScopes(p);
    AnswerGenerator.Generated generated;
    String retrievalMode = embeddings.spec().semantic() ? "SEMANTIC" : "BASELINE_HASH";
    // 앞 대화(ADR-0084): 같은 세션의 근거 있는 완료 답변 중 질문 원문이 있는 것(최근 HISTORY_TURNS 턴). 이어지는 질문이면 직전 질문을
    // 붙여 검색하고, 생성에는 늘 참고로 넘긴다(근거 아님)
    List<AnswerGenerator.PriorTurn> history =
        answers
            .previousTurns(
                answers.findById(answerId).orElseThrow().sessionId(), answerId, HISTORY_TURNS)
            .stream()
            .map(t -> new AnswerGenerator.PriorTurn(t.question(), t.answer()))
            .toList();
    String searchQuery =
        FollowUp.retrievalQuery(
            question, history.stream().map(AnswerGenerator.PriorTurn::question).toList());
    Future<AnswerGenerator.Generated> work =
        executor.submit(
            () -> {
              Retriever.Retrieval r =
                  retriever.retrieve(snapshot, searchQuery, cls.history(), scopes);
              return generator.generate(question, r.ranked(), r.companions(), history);
            });
    try {
      generated = work.get(props.getProcessing().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      work.cancel(true);
      log.warn("assistant answer={} timed out", answerId);
      return failed(
          p,
          answerId,
          attempt,
          DEPENDENCY_UNAVAILABLE,
          "처리 시간이 초과됐습니다. 같은 Idempotency-Key 로 다시 보내세요.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failed(
          p,
          answerId,
          attempt,
          DEPENDENCY_UNAVAILABLE,
          "처리가 중단됐습니다. 같은 Idempotency-Key 로 다시 보내세요.");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Retriever.SnapshotModelMismatchException) {
        return failed(p, answerId, attempt, CONFIG_REQUIRED, "색인과 임베딩 모델 설정이 다릅니다. 재색인 뒤 다시 보내세요.");
      }
      log.warn(
          "assistant answer={} dependency failure {}",
          answerId,
          e.getCause().getClass().getSimpleName());
      return failed(
          p,
          answerId,
          attempt,
          DEPENDENCY_UNAVAILABLE,
          "검색·모델 의존성 장애입니다. 같은 Idempotency-Key 로 다시 보내세요.");
    }

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
  private Optional<Completion> productAnswer(
      String answerId, String question, @Nullable String centerId) {
    if (!products.enabled()) {
      return Optional.empty();
    }
    Optional<String> keyword = ProductQuestion.keyword(question);
    if (keyword.isEmpty()) {
      return Optional.empty();
    }
    Optional<ProductSearchClient.Result> found;
    try {
      found = products.search(keyword.get(), centerId);
    } catch (RuntimeException e) {
      log.warn(
          "assistant answer={} product search failed {} — 문서 검색으로",
          answerId,
          e.getClass().getSimpleName());
      return Optional.empty();
    }
    if (found.isEmpty() || found.get().items().isEmpty()) {
      return Optional.empty();
    }
    boolean center = centerId != null && !centerId.isBlank();
    return Optional.of(
        new Completion(
            "PRODUCT_INFO",
            ProductAnswerText.operator(
                keyword.get(), found.get().items(), found.get().total(), center),
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
        "assistant answer={} state={} chunks={} tookMs={} written={}",
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
    PmartPrincipal p = requireOperatorAndSource(principal, sourceHeader);
    AnswerRow row =
        answers
            .findByRequest(sourceHeader, p.subject(), requestId)
            .orElseThrow(PolicyQuestionService::notFound);
    return stored(row, p, ReadMode.GET);
  }

  public Outcome getAnswer(@Nullable PmartPrincipal principal, String answerId) {
    PmartPrincipal p = requireOperator(principal);
    AnswerRow row = answers.findById(answerId).orElseThrow(PolicyQuestionService::notFound);
    if (!row.askerSubject().equals(p.subject())) {
      throw notFound();
    }
    return stored(row, p, ReadMode.GET);
  }

  /** 내 질문 목록 한 줄(BR-46). */
  public record HistoryItem(AnswerRow row, int citationCount) {}

  public record HistoryPage(List<HistoryItem> items, @Nullable String nextCursor) {}

  /** 내 질문 목록(BR-46): 출처 + 본인 범위, 최근 순. 완료 답변은 현재 권한을 다시 확인해 볼 수 없는 것은 뺀다(저장 답변 반환 경로와 같은 규칙). */
  public HistoryPage history(
      @Nullable PmartPrincipal principal,
      @Nullable String sourceHeader,
      int size,
      @Nullable String cursor) {
    PmartPrincipal p = requireOperatorAndSource(principal, sourceHeader);
    Instant beforeAt = null;
    String beforeId = null;
    if (cursor != null && !cursor.isBlank()) {
      try {
        String raw =
            new String(
                java.util.Base64.getUrlDecoder().decode(cursor),
                java.nio.charset.StandardCharsets.UTF_8);
        int bar = raw.indexOf('|');
        beforeAt = Instant.parse(raw.substring(0, bar));
        beforeId = raw.substring(bar + 1);
      } catch (RuntimeException e) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
      }
    }
    Instant fromAt = beforeAt;
    String fromId = beforeId;
    // 권한이 사라진 완료 답변은 건너뛴다. 커서는 다음 페이지에 보이는 행이 있을 때만(HistoryPager)
    HistoryPager.Page<AnswerRow, HistoryItem> page =
        HistoryPager.page(
            size,
            null,
            (after, limit) ->
                after == null
                    ? answers.listMine(sourceHeader, p.subject(), fromAt, fromId, limit)
                    : answers.listMine(
                        sourceHeader, p.subject(), after.createdAt(), after.answerId(), limit),
            r -> historyItem(r, p));
    AnswerRow last = page.nextAfter();
    String next =
        last == null
            ? null
            : java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    (last.createdAt() + "|" + last.answerId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new HistoryPage(page.items(), next);
  }

  // ---- 채팅 세션(ADR-0084) ----

  public record SessionPage(List<AnswerStore.SessionSummary> items, @Nullable String nextCursor) {}

  /** 세션 하나와 그 안의 질문·답변(질문 순). 지금 권한으로 볼 수 없는 완료 답변과 실패한 시도는 빼고 보인다. */
  public record SessionView(AnswerStore.SessionRow session, List<Outcome> messages) {}

  /** 내 채팅 목록: 출처 + 본인 범위, 마지막 질문 최근 순. */
  public SessionPage sessions(
      @Nullable PmartPrincipal principal,
      @Nullable String sourceHeader,
      int size,
      @Nullable String cursor) {
    PmartPrincipal p = requireOperatorAndSource(principal, sourceHeader);
    Instant beforeAt = null;
    String beforeId = null;
    if (cursor != null && !cursor.isBlank()) {
      try {
        String raw =
            new String(
                java.util.Base64.getUrlDecoder().decode(cursor),
                java.nio.charset.StandardCharsets.UTF_8);
        int bar = raw.indexOf('|');
        beforeAt = Instant.parse(raw.substring(0, bar));
        beforeId = raw.substring(bar + 1);
      } catch (RuntimeException e) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "cursor 가 올바르지 않습니다.");
      }
    }
    List<AnswerStore.SessionSummary> rows =
        answers.listSessions(sourceHeader, p.subject(), beforeAt, beforeId, size + 1);
    String next = null;
    if (rows.size() > size) {
      rows = rows.subList(0, size);
      AnswerStore.SessionRow last = rows.getLast().session();
      next =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (last.lastAskedAt() + "|" + last.sessionId())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return new SessionPage(List.copyOf(rows), next);
  }

  public SessionView session(
      @Nullable PmartPrincipal principal, @Nullable String sourceHeader, String sessionId) {
    PmartPrincipal p = requireOperatorAndSource(principal, sourceHeader);
    AnswerStore.SessionRow s =
        answers.findSession(sessionId).orElseThrow(PolicyQuestionService::sessionNotFound);
    if (!s.ownerSubject().equals(p.subject()) || !s.sourceSystem().equals(sourceHeader)) {
      throw sessionNotFound();
    }
    Instant now = clock.instant();
    List<Outcome> messages = new java.util.ArrayList<>();
    for (AnswerRow r : answers.sessionAnswers(sessionId)) {
      switch (r.state()) {
        case COMPLETED -> {
          List<CitationRecord> cites = citations(r);
          if (access.stillAllowed(
              p, r.askerSubject(), cites.stream().map(CitationRecord::accessScope).toList())) {
            messages.add(new Outcome.Completed(r, cites));
          }
        }
        case PROCESSING -> {
          if (r.leaseValid(now)) {
            messages.add(new Outcome.Processing(r, List.of()));
          }
        }
        case FAILED_RETRYABLE -> {
          // 같은 키로 다시 보내야 하는 시도 — 대화에는 싣지 않는다
        }
      }
    }
    return new SessionView(s, List.copyOf(messages));
  }

  /** 본인 채팅 삭제(질문·답변째). 없거나 남의 것이면 아무것도 지우지 않고 성공(멱등 — 존재를 드러내지 않는다). */
  public void deleteSession(
      @Nullable PmartPrincipal principal, @Nullable String sourceHeader, String sessionId) {
    PmartPrincipal p = requireOperatorAndSource(principal, sourceHeader);
    int n = answers.deleteSession(sessionId, sourceHeader, p.subject());
    log.info("assistant chat session delete session={} deleted={}", sessionId, n);
  }

  /** 목록에 보일 항목. 완료 답변은 현재 권한을 다시 확인해 볼 수 없으면 비어 있다. */
  private java.util.Optional<HistoryItem> historyItem(AnswerRow r, PmartPrincipal p) {
    if (r.state() != AnswerStore.State.COMPLETED) {
      return java.util.Optional.of(new HistoryItem(r, 0));
    }
    List<CitationRecord> cites = citations(r);
    return access.stillAllowed(
            p, r.askerSubject(), cites.stream().map(CitationRecord::accessScope).toList())
        ? java.util.Optional.of(new HistoryItem(r, cites.size()))
        : java.util.Optional.empty();
  }

  private Outcome reread(String answerId, PmartPrincipal p, ReadMode mode) {
    return stored(answers.findById(answerId).orElseThrow(PolicyQuestionService::notFound), p, mode);
  }

  /**
   * 저장 상태대로 응답한다 — 모든 저장 답변 반환 경로(POST 재응답·requestId 조회·lease 상실 재조회·answerId 조회)가 여기를 지난다. 완료 답변은
   * 현재 권한(지금도 운영자·본인·인용 접근 범위)을 다시 확인하고, 아니면 404.
   */
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
          throw error(code, "직전 시도가 실패했습니다(" + code + "). 같은 Idempotency-Key 로 다시 보내세요.");
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

  private PmartPrincipal requireOperatorAndSource(
      @Nullable PmartPrincipal principal, @Nullable String sourceHeader) {
    PmartPrincipal p = requireOperator(principal);
    if (!SourceSystemPolicy.accepts(p.sourceSystem(), sourceHeader)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "X-Source-System 이 인증된 출처와 다릅니다.");
    }
    return p;
  }

  private static PmartPrincipal requireOperator(@Nullable PmartPrincipal principal) {
    if (principal == null || principal.type() != PmartPrincipal.PrincipalType.OPERATOR) {
      throw new PmartException(ErrorCode.FORBIDDEN, "운영자만 사용할 수 있습니다.");
    }
    return principal;
  }

  private static PmartException notFound() {
    return PmartException.notFound(ErrorCode.NOT_FOUND.name(), "답변을 찾을 수 없습니다.");
  }

  private static PmartException sessionNotFound() {
    return PmartException.notFound(ErrorCode.NOT_FOUND.name(), "채팅을 찾을 수 없습니다.");
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
