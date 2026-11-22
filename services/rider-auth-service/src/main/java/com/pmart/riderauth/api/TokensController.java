package com.pmart.riderauth.api;

import com.pmart.riderauth.api.generated.TokensApi;
import com.pmart.riderauth.api.generated.model.RefreshRiderTokensRequest;
import com.pmart.riderauth.api.generated.model.RiderLogoutRequest;
import com.pmart.riderauth.api.generated.model.RiderSession;
import com.pmart.riderauth.api.generated.model.TokenPair;
import com.pmart.riderauth.application.RiderSessionService;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 TokensApi — 토큰 갱신·로그아웃·현재 세션. refresh token 은 회전이라 같은 Idempotency-Key 재전송도 결과를 재생하지 않는다(토큰을
 * 저장하지 않으려고) — 쓴 토큰을 다시 보내면 TOKEN_REUSED.
 */
@RestController
public class TokensController implements TokensApi {

  private final RiderSessionService sessions;

  public TokensController(RiderSessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  public ResponseEntity<TokenPair> refreshRiderTokens(
      String idempotencyKey,
      String xSourceSystem,
      String xDeviceId,
      RefreshRiderTokensRequest body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(RiderAuthMapping.tokens(sessions.refresh(body.getRefreshToken(), xDeviceId)));
  }

  @Override
  public ResponseEntity<Void> riderLogout(
      String idempotencyKey, String xSourceSystem, @Nullable RiderLogoutRequest body) {
    sessions.logout(body != null && Boolean.TRUE.equals(body.getAllDevices()));
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<RiderSession> getCurrentRiderSession() {
    RiderSessionService.Current c = sessions.current();
    return ResponseEntity.ok(
        new RiderSession()
            .sessionId(c.sessionId())
            .riderId(c.riderId())
            .issuedAt(RiderAuthMapping.odt(c.issuedAt()))
            .expiresAt(RiderAuthMapping.odt(c.expiresAt()))
            .provider(
                c.provider() == null ? null : RiderSession.ProviderEnum.fromValue(c.provider())));
  }
}
