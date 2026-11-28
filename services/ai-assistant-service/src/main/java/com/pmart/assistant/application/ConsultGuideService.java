package com.pmart.assistant.application;

import com.pmart.assistant.domain.RetrievedChunk;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.assistant.infrastructure.SnapshotStore.SnapshotRow;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 상담 도우미(ADR-0093): 상담원이 고른 문의 종류·회원 조회 결과·주문 상태로 본인 확인 절과 정책 근거를 먼저 보여 준다. <b>LLM 을 쓰지 않는다</b> —
 * 설정 검색어로 지금 스냅숏에서 조각을 찾을 뿐이라 문서에 없는 말을 만들지 않는다. 요청을 저장하지 않는다(지표만).
 *
 * <p>개인정보·식별자(고객 ID·주문 번호·전화·주소)를 받지 않는다. 주문 상태는 화면이 이미 조회한 상태 이름뿐이다.
 */
@Service
public class ConsultGuideService {

  /** 상담 접수(case-service cs-intakes)와 같은 문의 종류. */
  public static final Set<String> CATEGORIES =
      Set.of("ORDER", "DELIVERY", "PAYMENT", "REFUND", "PRODUCT", "ACCOUNT", "OTHER");

  public record Guide(
      String category,
      @Nullable Boolean memberFound,
      @Nullable String orderStatus,
      @Nullable CitationRecord identity,
      List<CitationRecord> policies,
      String snapshotId) {}

  private final SnapshotStore snapshots;
  private final Retriever retriever;
  private final AccessPolicy access;
  private final KnowledgeProperties props;
  private final @Nullable MeterRegistry meters;

  public ConsultGuideService(
      SnapshotStore snapshots,
      Retriever retriever,
      AccessPolicy access,
      KnowledgeProperties props,
      ObjectProvider<MeterRegistry> meters) {
    this.snapshots = snapshots;
    this.retriever = retriever;
    this.access = access;
    this.props = props;
    this.meters = meters.getIfAvailable();
  }

  public Guide guide(
      @Nullable PmartPrincipal principal,
      @Nullable String source,
      String category,
      @Nullable Boolean memberFound,
      @Nullable String orderStatus) {
    PmartPrincipal p = KnowledgeDocumentService.requireOperatorAndSource(principal, source);
    var cfg = props.getConsultGuide();
    if (!KnowledgeDocumentService.hasAny(p.roles(), cfg.getRoles())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "상담 도우미는 " + cfg.getRoles() + " 만 쓸 수 있습니다.");
    }
    String cat = category.toUpperCase(Locale.ROOT);
    if (!CATEGORIES.contains(cat)) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "문의 종류가 맞지 않습니다.",
          List.of(new PmartException.Detail("category", CATEGORIES + " 중 하나입니다.")));
    }
    String status = orderStatus == null || orderStatus.isBlank() ? null : orderStatus.strip();
    if (status != null && !status.matches("[A-Z_]{1,40}")) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED,
          "주문 상태가 맞지 않습니다.",
          List.of(new PmartException.Detail("orderStatus", "주문 서비스의 상태 이름(대문자·_ 40자 이하)입니다.")));
    }
    SnapshotRow snapshot =
        snapshots.findActive().orElseThrow(() -> unavailable("활성 색인이 없습니다.", null));
    Set<String> scopes = access.allowedScopes(p);
    try {
      CitationRecord identity = null;
      if (!cfg.getIdentityQuery().isBlank()) {
        var ranked = retriever.retrieve(snapshot, cfg.getIdentityQuery(), false, scopes).ranked();
        if (!ranked.isEmpty()) {
          identity = CitationRecord.of(ranked.get(0), snapshot);
        }
      }
      List<String> queries = new ArrayList<>(queriesFor(cfg.getCategories(), cat));
      if (status != null) {
        queries.addAll(cfg.getOrderStatuses().getOrDefault(status, List.of()));
      }
      Map<String, CitationRecord> picked = new LinkedHashMap<>();
      Set<String> skip = new LinkedHashSet<>();
      if (identity != null) {
        skip.add(identity.chunkId());
      }
      for (String q : queries) {
        int taken = 0;
        for (RetrievedChunk c : retriever.retrieve(snapshot, q, false, scopes).ranked()) {
          if (taken >= cfg.getPerQuery() || picked.size() >= cfg.getMaxPolicies()) {
            break;
          }
          if (skip.contains(c.chunkId()) || picked.containsKey(c.chunkId())) {
            continue;
          }
          picked.put(c.chunkId(), CitationRecord.of(c, snapshot));
          taken++;
        }
      }
      count(cat, picked.isEmpty() && identity == null ? "EMPTY" : "OK");
      return new Guide(
          cat, memberFound, status, identity, List.copyOf(picked.values()), snapshot.snapshotId());
    } catch (Retriever.SnapshotModelMismatchException e) {
      count(cat, "FAILED");
      throw unavailable(e.getMessage(), e);
    } catch (PmartException e) {
      throw e;
    } catch (RuntimeException e) {
      count(cat, "FAILED");
      throw unavailable("근거를 찾지 못했습니다(임베딩·검색 장애).", e);
    }
  }

  static List<String> queriesFor(Map<String, List<String>> categories, String cat) {
    List<String> q = categories.get(cat);
    if (q == null || q.isEmpty()) {
      q = categories.getOrDefault("OTHER", List.of());
    }
    return q;
  }

  private void count(String category, String result) {
    if (meters != null) {
      Counter.builder("pmart.assistant.consult.guides")
          .description("상담 도우미 요청(ADR-0093)")
          .tag("category", category)
          .tag("result", result)
          .register(meters)
          .increment();
    }
  }

  private static PmartException unavailable(String message, @Nullable Throwable cause) {
    return PmartException.dependencyUnavailable(message, cause);
  }
}
