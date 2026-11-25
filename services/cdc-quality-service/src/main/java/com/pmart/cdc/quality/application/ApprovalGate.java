package com.pmart.cdc.quality.application;

import com.pmart.cdc.quality.domain.HqApprovalRules;
import com.pmart.cdc.quality.domain.HqApprovalRules.Assessment;
import com.pmart.cdc.quality.domain.HqApprovalRules.Line;
import com.pmart.cdc.quality.domain.HqApprovalRules.Price;
import com.pmart.cdc.quality.infrastructure.ApprovalRefClient;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.common.web.PmartException.Detail;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 품질 해제·폐기 승인 관문(cdc-quality 0.2.0 — 사용자 2026-11-26 "MFC 품질과 같게", cdc-inventory 실사 관문을 옮겨 씀). 설정
 * {@code pmart.quality.approval-ref.mode}:
 *
 * <ul>
 *   <li>{@code VERIFY}: 본사 기준({@link HqApprovalRules} — 손실 {@code hq-loss-krw}원·한 SKU {@code
 *       hq-sku-qty}개, 사용자 "MFC 와 같게" = 100000·20) 아래면 센터 관리자 결정만(번호는 주면 기록). 이상이면 번호가 없을 때 422
 *       APPROVAL_REQUIRED + details {@code approvalRequest}(approval-service 변경 요청 본문 — 종류 {@code
 *       CDC_STOCK_ADJUSTMENT}, AP-4), 있으면 approval-service 로 한 번 사용·범위 비교.
 *   <li>{@code RECORD_ONLY}: 번호를 확인 없이 기록(번호 필수) — approval AP-4 전·LAN 시험용.
 *   <li>그 밖·미설정: CONFIG_REQUIRED(확인 없이 집행하지 않는다 — CDC-F-030 "승인선이 없으면 조정 집행 보류").
 * </ul>
 */
@Component
public class ApprovalGate {

  private static final Logger log = LoggerFactory.getLogger(ApprovalGate.class);

  public static final String KEY =
      "pmart.quality.approval-ref.mode (품질 해제·폐기 승인 번호 검증 — VERIFY 또는 시험용 RECORD_ONLY)";
  static final String TYPE = "CDC_STOCK_ADJUSTMENT";

  private final String mode;
  private final @Nullable Long lossThreshold;
  private final @Nullable Integer skuQtyThreshold;
  private final ApprovalRefClient client;
  private final ObjectMapper mapper;

  public ApprovalGate(
      @Value("${pmart.quality.approval-ref.mode:}") String mode,
      @Value("${pmart.quality.approval-ref.hq-loss-krw:#{null}}") @Nullable Long lossThreshold,
      @Value("${pmart.quality.approval-ref.hq-sku-qty:#{null}}") @Nullable Integer skuQtyThreshold,
      ApprovalRefClient client,
      ObjectMapper mapper) {
    this.mode = mode == null ? "" : mode.trim();
    this.lossThreshold = lossThreshold;
    this.skuQtyThreshold = skuQtyThreshold;
    this.client = client;
    this.mapper = mapper;
  }

  /**
   * 집행 전 관문. 통과하면 기록할 승인 번호(없을 수 있음).
   *
   * @param kind QUALITY_RELEASE · DISPOSAL
   * @param ref 해제 요청·폐기 ID — 승인 요청의 targetRef 와 같아야 한다
   */
  public @Nullable String require(
      String cdcId,
      String kind,
      String ref,
      String actorId,
      String summary,
      List<Line> lines,
      @Nullable String approvalRef) {
    String given = approvalRef == null || approvalRef.isBlank() ? null : approvalRef.trim();
    if ("RECORD_ONLY".equals(mode)) {
      if (given == null) {
        throw new PmartException(ErrorCode.APPROVAL_REQUIRED, "승인 근거(approvalRef)가 필요합니다.");
      }
      return given;
    }
    if (!"VERIFY".equals(mode)) {
      throw PmartException.configRequired(KEY);
    }
    if (lossThreshold == null) {
      throw PmartException.configRequired("pmart.quality.approval-ref.hq-loss-krw");
    }
    if (skuQtyThreshold == null) {
      throw PmartException.configRequired("pmart.quality.approval-ref.hq-sku-qty");
    }
    List<String> decreasing =
        lines.stream().filter(l -> l.decrease() > 0).map(Line::sku).distinct().toList();
    Map<String, Price> prices = decreasing.isEmpty() ? Map.of() : client.prices(decreasing);
    Assessment a = HqApprovalRules.assess(lines, prices, lossThreshold, skuQtyThreshold);
    if (!a.hqRequired()) {
      return given;
    }
    String targetRef = cdcId + ":" + kind + ":" + ref;
    if (given == null) {
      List<Detail> details = new ArrayList<>();
      details.add(
          new Detail(
              "approvalRequest",
              mapper.writeValueAsString(request(cdcId, kind, targetRef, summary, a))));
      a.reasons().forEach(r -> details.add(new Detail("threshold", r)));
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED,
          "본사 재고 승인이 필요합니다("
              + String.join(", ", a.reasons())
              + ") — details 의 approvalRequest 로 승인을 받고 그 승인 번호를 approvalRef 로 보내 주세요.",
          details);
    }
    String useRef = ApprovalRefClient.SELF + ":" + kind + ":" + ref;
    ApprovalRefClient.Use use = client.use(given, TYPE, targetRef, actorId, useRef);
    if (!use.usable()) {
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED,
          "승인 번호 " + given + " 를 쓸 수 없습니다: " + use.reason(),
          List.of(new Detail("approvalRef", String.valueOf(use.reason()))));
    }
    String out = HqApprovalRules.outOfScope(lines, use.approved());
    if (out != null) {
      throw new PmartException(
          ErrorCode.APPROVAL_REQUIRED, out, List.of(new Detail("approvalRef", "OUT_OF_SCOPE")));
    }
    log.info("본사 승인 {} 사용 — {} by {}", given, targetRef, actorId);
    return given;
  }

  static Map<String, Object> request(
      String cdcId, String kind, String targetRef, String summary, Assessment a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", TYPE);
    m.put("targetService", ApprovalRefClient.SELF);
    m.put("targetRef", targetRef);
    m.put("centerScope", List.of(cdcId));
    m.put("allowedActions", List.of("POST_" + kind));
    m.put("summary", summary + " — " + String.join(", ", a.reasons()));
    m.put("lines", a.lines());
    return m;
  }
}
