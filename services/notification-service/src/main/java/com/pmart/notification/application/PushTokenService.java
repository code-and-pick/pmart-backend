package com.pmart.notification.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.notification.infrastructure.PushTokenStore;
import com.pmart.notification.infrastructure.PushTokenStore.PushDeviceRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 단말 푸시 토큰 등록·해제·목록(2026-11-17, 계약 0.3.0 — 클라이언트 요청 BR-09). 토큰만 저장한다 — 발송(FCM)은 아직 없다. 주인은 호출한
 * 고객·라이더 (운영자 403, 알림함과 같은 규칙). 토큰 원문은 로그·응답·예외 메시지에 넣지 않는다.
 *
 * <ul>
 *   <li>같은 주인·단말이면 토큰을 바꾼다(처음 등록 시각은 그대로).
 *   <li>같은 토큰이 다른 주인·단말에 있으면 그 줄을 지우고 옮긴다(토큰 하나는 한 번에 한 주인).
 *   <li>주인당 단말 수 {@code pmart.notification.push-token.max-devices} — 넘으면 가장 오래 갱신되지 않은 단말부터 지운다.
 *       설정이 없으면 422 CONFIG_REQUIRED.
 * </ul>
 */
@Service
public class PushTokenService {

  static final String MAX_DEVICES_KEY = "pmart.notification.push-token.max-devices";
  private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
  private static final int TOKEN_MAX = 4096;
  private static final int APP_VERSION_MAX = 32;

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(PushTokenService.class);

  private final PushTokenStore store;
  private final NotificationProperties props;
  private final Clock clock;

  public PushTokenService(PushTokenStore store, NotificationProperties props, Clock clock) {
    this.store = store;
    this.props = props;
    this.clock = clock;
  }

  /** 등록·교체. 돌려주는 행에는 토큰이 없다. */
  @Transactional
  public PushDeviceRow register(
      String deviceId, String token, String platform, @Nullable String appVersion) {
    PmartPrincipal p = NotificationService.recipient();
    checkDeviceId(deviceId);
    if (token.isBlank() || token.length() > TOKEN_MAX) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "token 은 1~4096자입니다.");
    }
    if (appVersion != null && appVersion.length() > APP_VERSION_MAX) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "appVersion 은 32자까지입니다.");
    }
    int max = maxDevices();
    String type = p.type().name();
    String owner = p.subject();
    String sha = sha256(token);
    // 잠금 순서는 언제나 토큰 → 주인(교착 없음). 해제는 주인 잠금만 쓴다.
    store.lock("push-token:" + sha);
    store.lock("push-owner:" + type + ":" + owner);
    store.deleteTokenElsewhere(sha, type, owner, deviceId);
    store.upsert(type, owner, deviceId, token, sha, platform, appVersion, now());
    store.evictBeyond(type, owner, deviceId, max);
    return store
        .find(type, owner, deviceId)
        .orElseThrow(() -> new IllegalStateException("방금 넣은 단말 줄이 없습니다."));
  }

  /** 해제(본인 단말만, 없으면 그대로 — 멱등). */
  @Transactional
  public void unregister(String deviceId) {
    PmartPrincipal p = NotificationService.recipient();
    checkDeviceId(deviceId);
    String type = p.type().name();
    store.lock("push-owner:" + type + ":" + p.subject());
    store.delete(type, p.subject(), deviceId);
  }

  public List<PushDeviceRow> list() {
    PmartPrincipal p = NotificationService.recipient();
    return store.list(p.type().name(), p.subject());
  }

  private int maxDevices() {
    Integer max = props.pushToken() == null ? null : props.pushToken().maxDevices();
    if (max == null || max < 1) {
      throw PmartException.configRequired(MAX_DEVICES_KEY);
    }
    return max;
  }

  private static void checkDeviceId(String deviceId) {
    if (!DEVICE_ID.matcher(deviceId).matches()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "deviceId 는 영문·숫자·._:- 1~128자입니다.");
    }
  }

  private static String sha256(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * 라이더 탈퇴(2026-11-19 — 사용자 "응", rider {@code RiderWithdrawn}): 그 라이더의 단말 푸시 토큰을 모두 지운다 — 탈퇴한 폰으로
   * 지급 완료 같은 알림이 가지 않게. 알림함 줄은 남는다(그 주체로 다시 로그인할 수 없다). 멱등(다시 와도 지울 것이 없을 뿐).
   */
  public int forgetRider(String riderId) {
    int n = store.deleteOwner("RIDER", riderId);
    if (n > 0) {
      log.info("라이더 탈퇴 — 단말 푸시 토큰 {}개 지움", n);
    }
    return n;
  }
}
