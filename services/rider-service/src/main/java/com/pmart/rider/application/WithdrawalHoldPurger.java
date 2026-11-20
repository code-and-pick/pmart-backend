package com.pmart.rider.application;

import com.pmart.rider.infrastructure.PhoneVerificationStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 탈퇴 뒤 보관 기한(rejoin-block — LAN 5분·운영 30일)이 지난 이름·전화와 재가입 막기 해시를 지운다(2026-11-20, 처리방침 §3 "기간이 지나면
 * 지체 없이 파기"). 여러 인스턴스가 같이 돌아도 DELETE 라 겹쳐도 무해하다. 개인정보·번호는 로그에 남기지 않는다(건수만).
 */
@Component
public class WithdrawalHoldPurger {

  private static final Logger log = LoggerFactory.getLogger(WithdrawalHoldPurger.class);

  private final PhoneVerificationStore store;
  private final Clock clock;

  public WithdrawalHoldPurger(PhoneVerificationStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${pmart.rider.withdrawal.purge-tick:PT1M}",
      fixedDelayString = "${pmart.rider.withdrawal.purge-tick:PT1M}")
  public void purge() {
    int n = store.purgeExpired(clock.instant());
    if (n > 0) {
      log.info("탈퇴 뒤 보관 기한이 지난 라이더 정보 {}건 지움", n);
    }
  }
}
