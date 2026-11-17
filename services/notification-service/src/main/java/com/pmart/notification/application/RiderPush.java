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

  void send(String riderId, String notificationId, com.pmart.notification.domain.Notices.Notice n) {
    Map<String, String> data = new java.util.LinkedHashMap<>();
    data.put("notificationId", notificationId);
    data.put("templateKey", n.templateKey());
    data.put("destinationType", n.destinationType());
    if (n.destinationRef() != null) {
      data.put("destinationRef", n.destinationRef());
    }
    int sent = 0;
    for (PushTokenStore.TokenRow t : tokens.tokens("RIDER", riderId)) {
      FcmSender.Result r = fcm.send(t.token(), n.title(), n.body(), data);
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
