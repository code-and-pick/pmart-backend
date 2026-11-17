package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.InternalApi;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에
 * 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class InternalController implements InternalApi {

  private final com.pmart.delivery.execution.application.RiderOpenItems openItems;

  public InternalController(com.pmart.delivery.execution.application.RiderOpenItems openItems) {
    this.openItems = openItems;
  }

  /** 라이더 탈퇴를 막는 것(rider-service, 2026-11-17). */
  @Override
  public org.springframework.http.ResponseEntity<
          com.pmart.delivery.execution.api.generated.model.GetRiderWithdrawalBlockers200Response>
      getRiderWithdrawalBlockers(String riderId) {
    return org.springframework.http.ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(
            new com.pmart.delivery.execution.api.generated.model
                    .GetRiderWithdrawalBlockers200Response()
                .riderId(riderId)
                .blockers(
                    openItems.withdrawalBlockers(riderId).stream()
                        .map(
                            com.pmart.delivery.execution.api.generated.model
                                    .GetRiderWithdrawalBlockers200Response.BlockersEnum
                                ::fromValue)
                        .toList()));
  }
}
