package com.pmart.approval.application;

import com.pmart.approval.domain.ApprovalRules;
import com.pmart.approval.domain.MfcTargets;
import com.pmart.approval.domain.RagDocumentTargets;
import com.pmart.approval.domain.SearchSynonymTargets;
import com.pmart.approval.infrastructure.ApprovalStore;
import com.pmart.approval.infrastructure.ApprovalStore.ApprovalRow;
import com.pmart.approval.infrastructure.ApprovalStore.ChangeRequestRow;
import com.pmart.approval.infrastructure.ApprovalStore.DecisionRow;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.Outcome;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 변경 요청·승인 최소 흐름(ADR-0014). 모든 변경 명령은 {@link RequestIdempotency} 트랜잭션 안에서 상태 저장과 Outbox 기록을 함께 한다.
 */
@Service
public class ApprovalService {

  /** 명령 호출자(운영자). */
  public record Operator(String id, List<String> roles, String sourceSystem) {}

  /** 변경 요청 생성 입력. {@code body} 는 계약의 ChangeRequestBody 그대로(JSON 스냅샷으로 보존). */
  public record NewChangeRequest(
      String type, String targetService, String targetRef, Object body) {}

  /** 결정 입력. {@code body} 는 계약의 DecisionRequest 그대로. */
  public record NewDecision(
      String decision,
      @Nullable Instant effectiveFrom,
      @Nullable Instant expiresAt,
      List<String> conditions,
      Object body) {}

  private final ApprovalStore store;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final ApprovalProperties properties;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ApprovalService(
      ApprovalStore store,
      RequestIdempotency idempotency,
      EventPublisher events,
      ApprovalProperties properties,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.idempotency = idempotency;
    this.events = events;
    this.properties = properties;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Outcome<ChangeRequestRow> createChangeRequest(
      String requestId, Operator operator, NewChangeRequest in) {
    if (MfcTargets.isMfcType(in.type())) {
      // MFC 종류(AP-1): 대상 <mfcId>:<kind>:<ref>·정본·재고 행 형식 — 정본이 승인 번호로 집행할 때 이 문자열로 맞춰 본다
      List<MfcTargets.Line> lines = new ArrayList<>();
      for (JsonNode l : mapper.<JsonNode>valueToTree(in.body()).path("lines")) {
        lines.add(
            new MfcTargets.Line(
                l.path("lineKey").asString(null),
                l.hasNonNull("requestedQty") ? l.get("requestedQty").asInt() : null));
      }
      MfcTargets.validate(in.type(), in.targetService(), in.targetRef(), lines);
    }
    ApprovalRules.requireRequesterRole(
        in.type(), operator.roles(), properties.getRequesterRoles().get(in.type()));
    if (SearchSynonymTargets.isSynonymType(in.type())) {
      // 검색 동의어(ADR-0073 단계 B): 대상·기준 버전·행(규칙 ID·op·같은 뜻 낱말) 형식
      JsonNode body = mapper.valueToTree(in.body());
      List<SearchSynonymTargets.Line> lines = new ArrayList<>();
      for (JsonNode l : body.path("lines")) {
        JsonNode attrs = l.path("attributes");
        List<String> terms = null;
        if (attrs.path("terms").isArray()) {
          terms = new ArrayList<>();
          for (JsonNode t : attrs.get("terms")) {
            terms.add(t.isString() ? t.asString() : null);
          }
        }
        lines.add(
            new SearchSynonymTargets.Line(
                l.path("lineKey").asString(null), attrs.path("op").asString(null), terms));
      }
      SearchSynonymTargets.validate(
          in.targetService(),
          in.targetRef(),
          body.hasNonNull("targetExpectedVersion")
              ? body.get("targetExpectedVersion").asInt()
              : null,
          lines);
    }
    if (RagDocumentTargets.isRagDocumentType(in.type())) {
      // 운영자 RAG 문서(ADR-0092): 대상 초안·기준 판·행 하나(문서 ID·op·초안·본문 해시) 형식
      JsonNode body = mapper.valueToTree(in.body());
      List<RagDocumentTargets.Line> lines = new ArrayList<>();
      for (JsonNode l : body.path("lines")) {
        JsonNode attrs = l.path("attributes");
        lines.add(
            new RagDocumentTargets.Line(
                l.path("lineKey").asString(null),
                attrs.path("op").asString(null),
                attrs.path("draftId").asString(null),
                attrs.path("contentSha256").asString(null)));
      }
      RagDocumentTargets.validate(
          in.targetService(),
          in.targetRef(),
          body.hasNonNull("targetExpectedVersion")
              ? body.get("targetExpectedVersion").asInt()
              : null,
          lines);
    }
    return idempotency.execute(
        key(requestId, "createChangeRequest", operator),
        in.body(),
        ChangeRequestRow.class,
        () -> {
          ChangeRequestRow row =
              new ChangeRequestRow(
                  "CR-" + UUID.randomUUID(),
                  1,
                  in.type(),
                  in.targetService(),
                  in.targetRef(),
                  "DRAFT",
                  operator.id(),
                  null,
                  mapper.writeValueAsString(in.body()),
                  now(),
                  null);
          store.insert(row);
          return row;
        });
  }

  public ChangeRequestRow getChangeRequest(String id) {
    return store
        .findChangeRequest(id)
        .orElseThrow(() -> PmartException.notFound("CHANGE_REQUEST_NOT_FOUND", "변경 요청이 없습니다."));
  }

  /** DRAFT → SUBMITTED, 승인 단위 확정(approvalId 발급), ChangeRequestSubmitted. */
  public Outcome<CommandResponse> submit(
      String requestId,
      Operator operator,
      String changeRequestId,
      @Nullable Integer expectedVersion) {
    return idempotency.execute(
        key(requestId, "submitChangeRequest", operator),
        Map.of("changeRequestId", changeRequestId),
        CommandResponse.class,
        () -> {
          ChangeRequestRow cr = getChangeRequest(changeRequestId);
          checkVersion(expectedVersion, cr.version());
          if (!cr.requesterId().equals(operator.id())) {
            throw ApprovalRules.forbidden("발의자만 제출할 수 있습니다.");
          }
          ApprovalRules.requireDraft(cr.requestState());
          JsonNode body = mapper.readTree(cr.body());
          Instant at = now();
          String approvalId = "AP-" + UUID.randomUUID();
          store.insert(
              new ApprovalRow(
                  approvalId,
                  1,
                  cr.changeRequestId(),
                  cr.type(),
                  cr.targetService(),
                  cr.targetRef(),
                  cr.requesterId(),
                  "REVIEW_PENDING",
                  "NOT_REQUESTED",
                  "PENDING",
                  cr.body(),
                  instantOrNull(body, "requestedEffectiveFrom"),
                  instantOrNull(body, "expiresAt"),
                  at));
          store.markSubmitted(cr.changeRequestId(), cr.version(), approvalId, at);

          publishSubmitted(cr, body, operator.id(), at);
          return new CommandResponse(
              CommandStatus.APPLIED,
              requestId,
              approvalId,
              null,
              cr.version() + 1,
              false,
              null,
              at);
        });
  }

  private void publishSubmitted(
      ChangeRequestRow cr, JsonNode body, String submittedBy, Instant at) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("changeRequestId", cr.changeRequestId());
    payload.put("type", cr.type());
    payload.put(
        "targetRef",
        Map.of(
            "targetType", ApprovalRules.targetType(cr.type()),
            "targetId", cr.targetRef(),
            "targetService", cr.targetService()));
    if (body.hasNonNull("targetExpectedVersion")) {
      payload.put("expectedVersion", body.get("targetExpectedVersion").asInt());
    }
    payload.put("payloadRef", "approval-service/change-requests/" + cr.changeRequestId());
    payload.put("submittedBy", submittedBy);
    payload.put("submittedAt", at.toString());
    events.publish(
        "ChangeRequestSubmitted",
        1,
        "ChangeRequest",
        cr.changeRequestId(),
        cr.version() + 1L,
        new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, submittedBy),
        payload);
  }

  // ---------------------------------------------------------------- 직원 권한 요청(ADR-0056 5-4)

  /** 권한 반영 정본(ADR-0056 결정 4). */
  static final String GRANT_TARGET_SERVICE = "employee-auth-service";

  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  /**
   * hr-service 의 {@code GrantRequested}(직원 등록 화면에서 받은 요청 권한)를 변경 요청 + 승인으로 올린다. 호출자 inbox 트랜잭션 안(같은
   * 사건 두 번 없음). 요청자 = 등록한 인사팀 직원.
   *
   * <p>행마다 자동 승인 규칙(ADR-0056 결정 6)을 본다. 맞는 행은 규칙별로 한 승인에 모아 바로 APPROVE(승인자 = {@code
   * RULE:<id>@v<버전>}, 행위자 SYSTEM) → ApprovalDecided. 나머지 행은 한 승인으로 심사 중(REVIEW_PENDING) — 승인자가
   * 승인함에서 결정한다. 부분 승인이 아직 없으므로 승인을 나눠 행 단위 결과를 지킨다.
   *
   * <p>같은 직원에 심사 중인 권한 승인이 이미 있으면 DUPLICATE_APPROVAL 로 실패한다(재시도 뒤 DLT) — 지금은 등록 때 한 번만 온다.
   */
  public void intakeGrantRequest(EventEnvelope e) {
    JsonNode p = e.payload();
    String requestId = required(p, "requestId");
    String operatorId = required(p, "operatorId");
    String requestedBy = required(p, "requestedBy");
    String affType = p.path("affiliation").path("type").asString(null);
    String affCode = p.path("affiliation").path("code").asString(null);

    Map<ApprovalProperties.AutoApprovalRule, List<JsonNode>> auto = new LinkedHashMap<>();
    List<JsonNode> manual = new ArrayList<>();
    for (JsonNode line : p.path("lines")) {
      String role = line.path("role").asString();
      List<String> centers = strings(line.path("centers"));
      ApprovalProperties.AutoApprovalRule rule =
          properties.getAutoApprovalRules().stream()
              .filter(
                  r ->
                      ApprovalRules.autoApprovable(
                          r.roles(), r.sameCenterAsAffiliation(), role, centers, affType, affCode))
              .findFirst()
              .orElse(null);
      if (rule == null) {
        manual.add(line);
      } else {
        auto.computeIfAbsent(rule, k -> new ArrayList<>()).add(line);
      }
    }
    if (auto.isEmpty() && manual.isEmpty()) {
      throw new IllegalArgumentException("권한 요청 행 없음: " + requestId); // 계약 위반 → DLT
    }
    // 승인자가 누구의 권한인지 알아보게 — 사번·메일 주소·소속(이름은 사건에 없다)
    Map<String, Object> who = new LinkedHashMap<>();
    who.put("operatorId", operatorId);
    if (p.hasNonNull("employeeNo")) {
      who.put("employeeNo", p.get("employeeNo").asString());
    }
    if (p.hasNonNull("mailAddress")) {
      who.put("mailAddress", p.get("mailAddress").asString());
    }
    if (affType != null) {
      who.put("affiliation", Map.of("type", affType, "code", affCode == null ? "" : affCode));
    }
    Instant at = now();
    // 자동 승인부터 — 바로 닫히므로 아래 심사 중 승인과 '대상마다 열린 승인 하나' 제약이 겹치지 않는다
    for (var g : auto.entrySet()) {
      ApprovalRow ap = openGrantApproval(requestId, operatorId, requestedBy, who, g.getValue(), at);
      ApprovalProperties.AutoApprovalRule rule = g.getKey();
      String comment = "자동 승인 규칙 " + rule.approverId() + "(ADR-0056 결정 6)";
      store.insert(
          new DecisionRow(
              "DC-" + UUID.randomUUID(),
              ap.approvalId(),
              "APPROVE",
              rule.approverId(),
              mapper.writeValueAsString(Map.of("decision", "APPROVE", "comment", comment)),
              at));
      JsonNode unit = mapper.readTree(ap.unit());
      Instant effectiveFrom = ap.effectiveFrom();
      store.markDecided(ap.approvalId(), ap.version(), "APPROVED", effectiveFrom, null);
      events.publish(
          "ApprovalDecided",
          1,
          "Approval",
          ap.approvalId(),
          ap.version() + 1L,
          new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, "approval-service"),
          decidedPayload(
              ap, unit, "APPROVED", rule.approverId(), List.of(), effectiveFrom, null, at));
    }
    if (!manual.isEmpty()) {
      openGrantApproval(requestId, operatorId, requestedBy, who, manual, at);
    }
  }

  /** 변경 요청(제출됨) + 심사 중 승인 하나 + ChangeRequestSubmitted. */
  private ApprovalRow openGrantApproval(
      String requestId,
      String operatorId,
      String requestedBy,
      Map<String, Object> who,
      List<JsonNode> lines,
      Instant at) {
    TreeSet<String> centerScope = new TreeSet<>();
    List<Map<String, Object>> bodyLines = new ArrayList<>();
    LocalDate earliest = null;
    for (JsonNode l : lines) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("role", l.path("role").asString());
      List<String> centers = strings(l.path("centers"));
      centerScope.addAll(centers);
      attrs.put("centers", centers);
      LocalDate from = LocalDate.parse(l.path("validFrom").asString());
      earliest = earliest == null || from.isBefore(earliest) ? from : earliest;
      attrs.put("validFrom", from.toString());
      if (l.hasNonNull("validTo")) {
        attrs.put("validTo", l.get("validTo").asString());
      }
      if (l.hasNonNull("reason")) {
        attrs.put("reason", l.get("reason").asString());
      }
      attrs.put("employee", who);
      bodyLines.add(
          Map.of(
              "lineKey",
              l.path("lineKey").asString(),
              "targetRef",
              operatorId,
              "attributes",
              attrs));
    }
    Instant effectiveFrom = Objects.requireNonNull(earliest).atStartOfDay(SEOUL).toInstant();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "GRANT");
    body.put("targetService", GRANT_TARGET_SERVICE);
    body.put("targetRef", operatorId);
    body.put("centerScope", List.copyOf(centerScope));
    body.put("allowedActions", List.of("GRANT_ROLE"));
    body.put(
        "summary",
        "직원 권한 요청 "
            + who.getOrDefault("employeeNo", operatorId)
            + (who.containsKey("mailAddress") ? " " + who.get("mailAddress") : "")
            + " (hr-service "
            + requestId
            + ")");
    body.put("lines", bodyLines);
    body.put("requestedEffectiveFrom", earliest.atStartOfDay(SEOUL).toOffsetDateTime().toString());
    String bodyJson = mapper.writeValueAsString(body);

    String crId = "CR-" + UUID.randomUUID();
    ChangeRequestRow cr =
        new ChangeRequestRow(
            crId,
            1,
            "GRANT",
            GRANT_TARGET_SERVICE,
            operatorId,
            "DRAFT",
            requestedBy,
            null,
            bodyJson,
            at,
            null);
    store.insert(cr);
    ApprovalRow ap =
        new ApprovalRow(
            "AP-" + UUID.randomUUID(),
            1,
            crId,
            "GRANT",
            GRANT_TARGET_SERVICE,
            operatorId,
            requestedBy,
            "REVIEW_PENDING",
            "NOT_REQUESTED",
            "PENDING",
            bodyJson,
            effectiveFrom,
            null,
            at);
    store.insert(ap);
    store.markSubmitted(crId, cr.version(), ap.approvalId(), at);
    publishSubmitted(cr, mapper.readTree(bodyJson), requestedBy, at);
    return ap;
  }

  private static List<String> strings(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(c -> out.add(c.asString()));
    return out;
  }

  private static String required(JsonNode p, String field) {
    String v = p.path(field).asString(null);
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException(field + " 없음"); // 계약 위반 → DLT
    }
    return v;
  }

  public ApprovalRow getApproval(String approvalId) {
    return store
        .findApproval(approvalId)
        .orElseThrow(() -> PmartException.notFound("APPROVAL_NOT_FOUND", "승인이 없습니다."));
  }

  public List<DecisionRow> decisions(String approvalId) {
    return store.decisions(approvalId);
  }

  /** 반영 회신(ADR-0039), 받은 순. */
  public List<ApprovalStore.ReceiptRow> receipts(String approvalId) {
    return store.receipts(approvalId);
  }

  public DecisionRow getDecision(String approvalId, String decisionId) {
    return store
        .findDecision(approvalId, decisionId)
        .orElseThrow(() -> PmartException.notFound("DECISION_NOT_FOUND", "결정이 없습니다."));
  }

  /**
   * 승인함. {@code decidableBy} 가 있으면 그 사람이 지금 결정할 수 있는 승인만(AP-3) — {@link ApprovalRules#decideBlock}
   * 과 같은 조건을 SQL 로(심사 중·자기 요청 아님·승인 역할을 가진 종류·자기 권한 요청 아님). 승인 역할 설정은 이 서비스 설정값이라 원격 호출 없음.
   */
  public List<ApprovalRow> list(
      @Nullable String type,
      @Nullable String reviewState,
      @Nullable String executionState,
      @Nullable String requesterId,
      @Nullable String targetRef,
      @Nullable Operator decidableBy,
      @Nullable Instant afterSubmittedAt,
      @Nullable String afterId,
      int limit) {
    ApprovalStore.DecidableFilter decidable = null;
    if (decidableBy != null) {
      decidable =
          new ApprovalStore.DecidableFilter(
              decidableBy.id(),
              ApprovalRules.decidableTypes(decidableBy.roles(), properties.getApproverRoles()));
      if (decidable.types().isEmpty()) {
        return List.of();
      }
    }
    return store.list(
        type,
        reviewState,
        executionState,
        requesterId,
        targetRef,
        decidable,
        afterSubmittedAt,
        afterId,
        limit);
  }

  /** 보는 사람이 이 승인을 지금 결정할 수 있는지(AP-3) — 결정 API 와 같은 검사. null = 할 수 있음. */
  public ApprovalRules.@Nullable DecideBlock viewerBlock(ApprovalRow ap, Operator viewer) {
    return ApprovalRules.decideBlock(
        ap.reviewState(),
        ap.type(),
        ap.requesterId(),
        ap.targetRef(),
        viewer.id(),
        viewer.roles(),
        properties.getApproverRoles());
  }

  /** 심사 결정(APPROVE / REJECT) → ApprovalDecided. */
  public Outcome<CommandResponse> decide(
      String requestId,
      Operator operator,
      String approvalId,
      @Nullable Integer expectedVersion,
      NewDecision in) {
    return idempotency.execute(
        key(requestId, "decideApproval", operator),
        Map.of("approvalId", approvalId, "decision", in.body()),
        CommandResponse.class,
        () -> {
          ApprovalRow ap = getApproval(approvalId);
          checkVersion(expectedVersion, ap.version());
          ApprovalRules.requireSupported(in.decision());
          ApprovalRules.requireCanDecide(
              ap.reviewState(),
              ap.type(),
              ap.requesterId(),
              ap.targetRef(),
              operator.id(),
              operator.roles(),
              properties.getApproverRoles());

          Instant at = now();
          String decided = ApprovalRules.decidedState(in.decision());
          JsonNode unit = mapper.readTree(ap.unit());
          Instant effectiveFrom =
              in.effectiveFrom() != null
                  ? in.effectiveFrom()
                  : instantOrNull(unit, "requestedEffectiveFrom");
          Instant expiresAt = in.expiresAt() != null ? in.expiresAt() : ap.expiresAt();
          String decisionId = "DC-" + UUID.randomUUID();
          store.insert(
              new DecisionRow(
                  decisionId,
                  approvalId,
                  in.decision(),
                  operator.id(),
                  mapper.writeValueAsString(in.body()),
                  at));
          store.markDecided(approvalId, ap.version(), decided, effectiveFrom, expiresAt);

          events.publish(
              "ApprovalDecided",
              1,
              "Approval",
              approvalId,
              ap.version() + 1L,
              actor(operator),
              decidedPayload(
                  ap, unit, decided, operator.id(), in.conditions(), effectiveFrom, expiresAt, at));
          return new CommandResponse(
              CommandStatus.APPLIED,
              requestId,
              decisionId,
              null,
              ap.version() + 1,
              false,
              null,
              at);
        });
  }

  private Map<String, Object> decidedPayload(
      ApprovalRow ap,
      JsonNode unit,
      String decided,
      String approverId,
      List<String> conditions,
      @Nullable Instant effectiveFrom,
      @Nullable Instant expiresAt,
      Instant at) {
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetType", ApprovalRules.targetType(ap.type()));
    target.put("targetId", ap.targetRef());
    target.put("targetService", ap.targetService());
    if (unit.hasNonNull("targetExpectedVersion")) {
      target.put("expectedVersion", unit.get("targetExpectedVersion").asInt());
    }

    // 승인 단위의 행(최소 범위: 요청 행 그대로 승인/반려). 정본은 attributes 로 변경 내용을 읽는다.
    List<Map<String, Object>> lines = new ArrayList<>();
    if (unit.has("lines")) {
      for (JsonNode l : unit.get("lines")) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineId", l.path("lineKey").asString());
        if (l.hasNonNull("requestedQty")) {
          line.put("qty", l.get("requestedQty").asInt());
        }
        if (l.hasNonNull("requestedAmount")) {
          line.put("amount", l.get("requestedAmount").asLong());
        }
        line.put("decision", decided);
        if (l.hasNonNull("targetRef")) {
          line.put("targetRef", l.get("targetRef").asString());
        }
        if (l.has("attributes")) {
          line.put("attributes", mapper.convertValue(l.get("attributes"), Map.class));
        }
        lines.add(line);
      }
    }

    Map<String, Object> p = new LinkedHashMap<>();
    p.put("approvalId", ap.approvalId());
    p.put("changeRequestId", ap.changeRequestId());
    p.put("type", ap.type());
    p.put("decision", decided);
    p.put("targetRef", target);
    p.put("lines", lines);
    p.put("conditions", conditions.stream().map(c -> Map.of("code", c)).toList());
    if (effectiveFrom != null) {
      p.put("effectiveFrom", effectiveFrom.toString());
    }
    if (expiresAt != null) {
      p.put("expiresAt", expiresAt.toString());
    }
    p.put("approverId", approverId);
    p.put("dossierVersion", ap.version() + 1);
    p.put("decidedAt", at.toString());
    return p;
  }

  private static RequestKey key(String requestId, String operation, Operator operator) {
    return new RequestKey(operator.sourceSystem(), requestId, operation, operator.id());
  }

  private static void checkVersion(@Nullable Integer expected, int current) {
    if (expected != null && expected != current) {
      throw PmartException.versionConflict(
          "대상이 이미 바뀌었습니다(기대 버전 " + expected + ", 현재 " + current + "). 다시 조회한 뒤 요청하세요.");
    }
  }

  private static EventEnvelope.Actor actor(Operator operator) {
    return new EventEnvelope.Actor(EventEnvelope.ActorType.OPERATOR, operator.id());
  }

  private static @Nullable Instant instantOrNull(JsonNode n, String field) {
    // 계약 date-time 은 오프셋을 포함할 수 있다(+09:00 등)
    return n.hasNonNull(field) ? OffsetDateTime.parse(n.get(field).asString()).toInstant() : null;
  }

  private Instant now() {
    return clock.instant();
  }
}
