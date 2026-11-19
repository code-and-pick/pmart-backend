package com.pmart.riderauth.api;

import com.pmart.common.web.PmartException;
import com.pmart.riderauth.api.generated.InternalApi;
import com.pmart.riderauth.api.generated.model.GetRiderIdentityInternal200Response;
import com.pmart.riderauth.api.generated.model.Provider;
import com.pmart.riderauth.infrastructure.RiderAuthStore;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 간 조회(0.2.0, 2026-11-19) — rider-service 가 같은 번호를 다른 제공자로 가입하려 할 때 안내에 쓴다. */
@RestController
public class InternalController implements InternalApi {

  private final RiderAuthStore store;

  public InternalController(RiderAuthStore store) {
    this.store = store;
  }

  @Override
  public ResponseEntity<GetRiderIdentityInternal200Response> getRiderIdentityInternal(
      String riderId) {
    var i =
        store
            .identityOf(riderId)
            .orElseThrow(() -> PmartException.notFound("RIDER_NOT_FOUND", "라이더 계정이 없습니다."));
    return ResponseEntity.ok(
        new GetRiderIdentityInternal200Response()
            .riderId(riderId)
            .provider(Provider.fromValue(i.provider()))
            .emailMasked(i.emailMasked())
            .linkedAt(i.linkedAt().atOffset(ZoneOffset.UTC)));
  }
}
