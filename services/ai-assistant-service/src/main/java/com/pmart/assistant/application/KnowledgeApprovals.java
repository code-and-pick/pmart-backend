package com.pmart.assistant.application;

import com.pmart.assistant.domain.KnowledgeDocumentRules;
import com.pmart.assistant.infrastructure.KnowledgeStore;
import com.pmart.assistant.infrastructure.KnowledgeStore.ApplicationRow;
import com.pmart.assistant.infrastructure.KnowledgeStore.DocumentSummary;
import com.pmart.assistant.infrastructure.KnowledgeStore.DraftRow;
import com.pmart.common.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 문서 승인 반영(ADR-0092 결정 3) — ApprovalDecided 중 {@code RAG_DOCUMENT}·이 서비스 대상·승인된 결정만.
 *
 * <p>멱등은 반영 기록({@code knowledge_application}, 승인 ID): 기록이 있으면 같은 회신만 다시 보낸다. 없으면 한 트랜잭션에서 초안·기준
 * 판·본문 해시를 확인하고 새 판 + 초안 닫기 + 반영 기록 + 재색인 요청을 쓴 뒤, 트랜잭션 밖에서 회신한다. 회신이 실패하면 예외 → 바인더가 다시 주고 기록을 보고
 * 같은 회신을 보낸다(search-service 동의어와 같은 방식 — outbox 를 rag-pgvector 에 두지 않는다).
 *
 * <p>반려 결정은 반영 요청이 아니라 회신하지 않고, 초안도 열어 둔다(고쳐서 다시 요청할 수 있게).
 */
@Service
public class KnowledgeApprovals {

  public static final String TYPE = "RAG_DOCUMENT";
  static final String TARGET_SERVICE = "ai-assistant-service";

  private static final Logger log = LoggerFactory.getLogger(KnowledgeApprovals.class);

  /** 회신 보내기(Kafka). 시험은 기록하는 구현으로 바꾼다. */
  public interface Receipts {
    String sourceSystem();

    void send(String approvalId, Map<String, Object> payload);
  }

  private final KnowledgeStore store;
  private final Receipts receipts;
  private final ObjectMapper mapper;
  private final Clock clock;

  public KnowledgeApprovals(
      KnowledgeStore store, Receipts receipts, ObjectMapper mapper, Clock clock) {
    this.store = store;
    this.receipts = receipts;
    this.mapper = mapper;
    this.clock = clock;
  }

  public synchronized void onApprovalDecided(EventEnvelope envelope) {
    if (!"ApprovalDecided".equals(envelope.eventType())) {
      return;
    }
    JsonNode p = mapper.valueToTree(envelope.payload());
    JsonNode target = p.path("targetRef");
    if (!TYPE.equals(p.path("type").asString())
        || !TARGET_SERVICE.equals(target.path("targetService").asString())) {
      return;
    }
    String decision = p.path("decision").asString();
    if (!"APPROVED".equals(decision) && !"PARTIALLY_APPROVED".equals(decision)) {
      return;
    }
    String approvalId = p.path("approvalId").asString();
    Optional<ApplicationRow> done = store.findApplication(approvalId);
    if (done.isPresent()) {
      reply(p, done.get());
      return;
    }
    ApplicationRow a = store.inTransaction(() -> apply(p, approvalId));
    if ("APPLIED".equals(a.result())) {
      log.info("RAG 문서 승인 {} 반영 → {} 판 {}", approvalId, a.documentId(), a.version());
    } else {
      log.warn("RAG 문서 승인 {} 반영 안 함: {}", approvalId, a.reason());
    }
    reply(p, a);
  }

  private ApplicationRow apply(JsonNode p, String approvalId) {
    Instant at = clock.instant();
    // 한 트랜잭션 안에서 다시 확인(동시에 같은 승인이 두 번 오지 않게 synchronized 이지만, 인스턴스가 늘어도 PK 가 막는다)
    Optional<ApplicationRow> again = store.findApplication(approvalId);
    if (again.isPresent()) {
      return again.get();
    }
    JsonNode target = p.path("targetRef");
    String draftId = target.path("targetId").asString(null);
    Integer expected =
        target.hasNonNull("expectedVersion") ? target.get("expectedVersion").asInt() : null;
    String approverId = p.path("approverId").asString(null);
    List<JsonNode> lines = new java.util.ArrayList<>();
    p.path("lines").forEach(lines::add);
    JsonNode line = lines.size() == 1 ? lines.get(0) : null;
    JsonNode attrs = line == null ? mapper.createObjectNode() : line.path("attributes");
    String documentId = line == null ? null : line.path("lineId").asString(null);

    Optional<DraftRow> found = draftId == null ? Optional.empty() : store.findDraft(draftId);
    String reject = null;
    DraftRow d = found.orElse(null);
    if (line == null || !"APPROVED".equals(line.path("decision").asString("APPROVED"))) {
      reject = "LINE_NOT_APPROVED";
    } else if (d == null) {
      reject = "DRAFT_NOT_FOUND";
    } else if (!"OPEN".equals(d.state())) {
      reject = "DRAFT_CLOSED";
    } else if (!d.documentId().equals(documentId)
        || !d.op().equals(attrs.path("op").asString(null))
        || expected == null
        || expected != d.baseVersion()) {
      reject = "DRAFT_MISMATCH";
    } else if ("UPSERT".equals(d.op())
        && (d.contentSha256() == null
            || !d.contentSha256().equals(attrs.path("contentSha256").asString(null)))) {
      reject = "DRAFT_CHANGED";
    }
    DocumentSummary current = null;
    int now = 0;
    if (reject == null) {
      current = store.lockDocument(d.documentId()).orElse(null);
      now = current == null ? 0 : current.version();
      if (now != d.baseVersion()) {
        reject = "STALE_VERSION";
      } else if (current != null && current.readOnly()
          || KnowledgeDocumentRules.readOnly(d.documentId())) {
        reject = "READ_ONLY_DOCUMENT";
      } else if ("RETIRE".equals(d.op()) && (current == null || current.retired())) {
        reject = "STALE_VERSION";
      }
    }
    if (reject != null) {
      if (d != null) {
        store.closeDraft(d.draftId(), "REJECTED", approvalId, reject, at);
      }
      ApplicationRow a =
          new ApplicationRow(
              approvalId, draftId, documentId, "REJECTED", reject, now, approverId, at);
      store.insertApplication(a);
      return a;
    }
    int next = now + 1;
    boolean retire = "RETIRE".equals(d.op());
    String content;
    String title;
    String sha;
    if (retire) {
      var doc = store.findDocument(d.documentId()).orElseThrow();
      content = doc.content();
      title = doc.summary().title();
      sha = doc.summary().contentSha256();
    } else {
      content = d.content();
      title = d.title() == null ? d.documentId() : d.title();
      sha = d.contentSha256();
    }
    String domain = KnowledgeDocumentRules.domainOf(d.documentId());
    store.writeVersion(
        d.documentId(),
        domain == null ? "common" : domain,
        d.op(),
        next,
        title,
        content,
        sha,
        retire,
        approvalId,
        d.draftId(),
        d.authorSubject(),
        at);
    store.closeDraft(d.draftId(), "APPLIED", approvalId, null, at);
    ApplicationRow a =
        new ApplicationRow(
            approvalId, d.draftId(), d.documentId(), "APPLIED", null, next, approverId, at);
    store.insertApplication(a);
    store.requestReindex(at);
    return a;
  }

  /** 회신 — 행 하나(APPLIED 또는 REJECTED·사유). */
  private void reply(JsonNode p, ApplicationRow a) {
    boolean applied = "APPLIED".equals(a.result());
    java.util.List<Map<String, Object>> lines = new java.util.ArrayList<>();
    int i = 0;
    for (JsonNode line : p.path("lines")) {
      String key = line.hasNonNull("lineId") ? line.get("lineId").asString() : "line-" + i;
      i++;
      Map<String, Object> rl = new LinkedHashMap<>();
      rl.put("lineKey", key);
      rl.put("result", applied ? "APPLIED" : "REJECTED");
      rl.put("reasonCode", applied ? null : a.reason());
      lines.add(rl);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("approvalId", a.approvalId());
    payload.put("receiverService", receipts.sourceSystem());
    payload.put("receiptSeq", 1);
    payload.put("result", a.result());
    payload.put("domainRef", domainRef(a));
    payload.put("reason", applied ? null : a.reason());
    payload.put("lines", lines);
    receipts.send(a.approvalId(), payload);
  }

  static @Nullable String domainRef(ApplicationRow a) {
    return a.documentId() == null ? null : a.documentId() + "@v" + a.version();
  }
}
