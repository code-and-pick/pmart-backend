package com.pmart.notification.application;

import com.pmart.notification.infrastructure.FcmSender;
import com.pmart.notification.infrastructure.PushTokenStore;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 라이더 휴대폰 푸시(2026-11-17 — 사용자 "발송 코드만 먼저, 키는 나중"). 라이더 알림함에 새 알림이 저장되면 <b>커밋 뒤</b> 그 라이더의 단말 토큰마다
 * FCM 으로 보낸다(제목·본문은 알림함과 같고 개인정보 없음, data 에 알림 ID·목적지). 키가 없으면 아무 것도 하지 않는다. 실패는 기록만 — 알림함이 정본이고 다시
 * 보내지 않는다. 더는 쓸 수 없는 토큰(UNREGISTERED)은 지운다. 고객 푸시는 아직 없다(APP_PUSH 승인 전).
 */
@Component
public class RiderPush {

  private static final Logger log = LoggerFactory.getLogger(RiderPush.class);

  private final FcmSender fcm;
  private final PushTokenStore tokens;

  public RiderPush(FcmSender fcm, PushTokenStore tokens) {
    this.fcm = fcm;
    this.tokens = tokens;
  }

  /** 지금 트랜잭션이 커밋되면 보낸다(트랜잭션 밖이면 바로). */
  public void afterCommit(
      String riderId, String notificationId, com.pmart.notification.domain.Notices.Notice n) {
    if (!fcm.enabled()) {
      return;
    }
    Runnable send = () -> send(riderId, notificationId, n);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              send.run();
            }
          });
    } else {
      send.run();
    }
  }

  /** 앱 알림 채널(2026-11-17 — 서버가 정함, 앱은 이 ID 로 채널을 만든다). */
  static final String CH_OFFER = "dispatch_offer";

  static final String CH_SUPPORT = "support";
  static final String CH_ACCOUNT = "account";
  static final String CH_SETTLEMENT = "settlement";

  static String channel(String templateKey) {
    return switch (templateKey) {
      case "RIDER_EARNING_ADJUSTED", "RIDER_PAYOUT_PAID", "RIDER_PAYOUT_FAILED" -> CH_SETTLEMENT;
      case "RIDER_DISPATCH_RESTRICTED",
          "RIDER_SUSPENDED",
          "RIDER_TERMINATED",
          "RIDER_RESTRICTION_LIFTED" ->
          CH_ACCOUNT;
      default -> CH_SUPPORT; // 지원센터 답장·도움요청 처리 끝·문의 답변
    };
  }

  /**
   * 새 배차 제안(2026-11-17 라이더 앱 요청 P2 "가장 필요"). 알림함에는 넣지 않고 푸시만 — 높은 우선순위, 만료까지만 살아 있음(TTL), 같은 tag 로
   * 앞 제안 알림을 덮는다. 내용에 주소·고객 정보 없음(잠금 화면). 앱은 누르면 `GET /offers` 로 다시 읽는다. 키가 없으면 아무 것도 하지 않는다.
   */
  public void offer(
      String riderId,
      String offerId,
      String deliveryId,
      @org.jspecify.annotations.Nullable Long fee,
      java.time.Instant expiresAt,
      java.time.Instant now) {
    if (!fcm.enabled()) {
      return;
    }
    long ttl = java.time.Duration.between(now, expiresAt).toSeconds();
    if (ttl <= 0) {
      return; // 이미 만료
    }
    Map<String, String> data = new java.util.LinkedHashMap<>();
    data.put("type", "DISPATCH_OFFER");
    data.put("offerId", offerId);
    data.put("deliveryId", deliveryId);
    data.put("expiresAt", expiresAt.toString());
    String body =
        (fee == null ? "" : String.format(java.util.Locale.KOREAN, "예상 배달료 %,d원 · ", fee))
            + "1분 안에 수락해 주세요.";
    Runnable send =
        () -> {
          for (PushTokenStore.TokenRow t : tokens.tokens("RIDER", riderId)) {
            FcmSender.Result r =
                fcm.send(
                    t.token(),
                    "새 배차 제안이 왔어요",
                    body,
                    data,
                    new FcmSender.Options(CH_OFFER, true, ttl, "dispatch_offer"));
            if (r == FcmSender.Result.UNREGISTERED) {
              tokens.delete("RIDER", riderId, t.deviceId());
            }
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              send.run();
            }
          });
    } else {
      send.run();
    }
  }

  /**
   * 일반배차모드 새 배달(2026-11-18 — 사용자 "보냄 — 묶어서", dispatch DeliveryListUpdated — 라이더마다 1분에 한 번까지). 알림함에는
   * 넣지 않고 푸시만, 제안과 같은 채널·높은 우선순위, 1분 TTL, 같은 tag 로 앞 알림을 덮는다. 앱은 누르면 배달리스트를 다시 읽는다.
   */
  public void listUpdated(String riderId, int newCount, java.time.Instant now) {
    if (!fcm.enabled() || riderId.isBlank()) {
      return;
    }
    Map<String, String> data = new java.util.LinkedHashMap<>();
    data.put("type", "DELIVERY_LIST");
    data.put("newCount", String.valueOf(newCount));
    data.put("sentAt", now.toString());
    String body = "배달 목록에 새 배달 " + newCount + "건이 올라왔어요.";
    Runnable send =
        () -> {
          for (PushTokenStore.TokenRow t : tokens.tokens("RIDER", riderId)) {
            FcmSender.Result r =
                fcm.send(
                    t.token(),
                    "새 배달이 있어요",
                    body,
                    data,
                    new FcmSender.Options(CH_OFFER, true, 60L, "delivery_list"));
            if (r == FcmSender.Result.UNREGISTERED) {
              tokens.delete("RIDER", riderId, t.deviceId());
            }
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              send.run();
            }
          });
    } else {
      send.run();
    }
  }

  void send(String riderId, String notificationId, com.pmart.notification.domain.Notices.Notice n) {
    // 앱 요청서의 이름(type·ref) + 알림함 이름(destinationType·destinationRef) 둘 다
    Map<String, String> data = new java.util.LinkedHashMap<>();
    data.put("notificationId", notificationId);
    data.put("templateKey", n.templateKey());
    data.put("type", n.destinationType());
    data.put("destinationType", n.destinationType());
    if (n.destinationRef() != null) {
      data.put("ref", n.destinationRef());
      data.put("destinationRef", n.destinationRef());
    }
    FcmSender.Options options = new FcmSender.Options(channel(n.templateKey()), false, null, null);
    int sent = 0;
    for (PushTokenStore.TokenRow t : tokens.tokens("RIDER", riderId)) {
      FcmSender.Result r = fcm.send(t.token(), n.title(), n.body(), data, options);
      if (r == FcmSender.Result.SENT) {
        sent++;
      } else if (r == FcmSender.Result.UNREGISTERED) {
        tokens.delete("RIDER", riderId, t.deviceId());
        log.info("라이더 {} 단말 {} 토큰이 더는 유효하지 않아 지움", riderId, t.deviceId());
      }
    }
    if (sent > 0) {
      log.info("라이더 {} 푸시 {} — 단말 {}대", riderId, n.templateKey(), sent);
    }
  }
}
