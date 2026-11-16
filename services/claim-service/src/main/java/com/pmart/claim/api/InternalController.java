package com.pmart.claim.api;

import com.pmart.claim.api.generated.InternalApi;
import com.pmart.claim.api.generated.model.Claim;
import com.pmart.claim.api.generated.model.SystemCancellationRequest;
import com.pmart.claim.application.ClaimService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi — 시스템 자동 전체 취소(슬라이스 6a, D6-4). 회수 수령 확인은 인터페이스 default 가 501 을 돌려준다. 업무 규칙은
 * application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class InternalController implements InternalApi {

  private final ClaimService claims;

  public InternalController(ClaimService claims) {
    this.claims = claims;
  }

  @Override
  public ResponseEntity<Claim> createSystemCancellation(
      String idempotencyKey,
      String xSourceSystem,
      SystemCancellationRequest body,
      @Nullable String traceparent) {
    ClaimService.SystemCancellation r =
        claims.createSystemCancellation(
            idempotencyKey,
            xSourceSystem,
            body.getOrderId(),
            body.getReason().getValue(),
            detail(body));
    return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(ClaimMapping.claim(r.view()));
  }

  @Override
  public ResponseEntity<Claim> createShortageRefund(
      String idempotencyKey,
      String xSourceSystem,
      com.pmart.claim.api.generated.model.CreateShortageRefundRequest body) {
    var view =
        claims.createShortageRefund(
            idempotencyKey,
            xSourceSystem,
            new ClaimService.ShortageRefund(
                body.getOrderId(),
                body.getLines().stream()
                    .map(
                        l ->
                            new com.pmart.claim.domain.ClaimRules.Requested(
                                l.getOrderLineId(), l.getQty()))
                    .toList(),
                body.getReasonDetail(),
                body.getSourceRef(),
                body.getFeeDeduction() == null ? 0 : body.getFeeDeduction()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ClaimMapping.claim(view));
  }

  /** 사유 상세에 요청 사가를 붙여 둔다(추적용, 1000자 상한). */
  private static @Nullable String detail(SystemCancellationRequest b) {
    String d = b.getReasonDetail();
    if (b.getSourceRef() != null) {
      d = (d == null ? "" : d + " ") + "[saga " + b.getSourceRef() + "]";
    }
    return d == null || d.length() <= 1000 ? d : d.substring(0, 1000);
  }
}
