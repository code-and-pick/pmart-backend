package com.pmart.rider.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.web.PmartException;
import com.pmart.rider.domain.Rider;
import com.pmart.rider.infrastructure.ExecutionClient;
import com.pmart.rider.infrastructure.RiderStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 라이더 탈퇴(2026-11-17 — 라이더 앱 요청 §17, 원문 §8.3 "탈퇴·활동 중단 시 수행 중 배송·미지급 정산·미해결 사건 처리 기준").
 *
 * <p>사용자 결정(2026-11-17): 막는 것 = 진행 중 배송(ACTIVE_DELIVERY)·<b>확정 전</b> 수행료(UNCONFIRMED_EARNING — 확정된
 * 금액은 탈퇴 뒤에도 지급일에 그대로)·산정 불가 조정 대기(EARNING_ADJUSTMENT_PENDING)·열린 도움요청(OPEN_HELP_REQUEST) —
 * delivery-execution 내부 조회 — 와 제한 검토 중(RESTRICTION_REVIEW — 이 서비스). 이미 걸린 계정 제한은 막지 않는다. 막는 것이 있으면
 * REJECTED(blockers)를 남기고 409 INVALID_STATE. 없으면 바로 COMPLETED: 개인정보(이름·전화·계좌·거주지·면허)를 지우고(사용자 "즉시
 * 가림·재가입 허용") 운행을 끄고 {@code RiderWithdrawn} → rider-auth 가 세션을 끊고 Google 연결을 끊는다(다음 로그인은 새 라이더).
 * 배송·수행료·제한 이력은 riderId 로 남는다.
 */
@Service
public class WithdrawalService {

  private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

  public static final String RESTRICTION_REVIEW = "RESTRICTION_REVIEW";

  public record Withdrawal(
      String requestId,
      String state,
      List<String> blockers,
      Instant requestedAt,
      @Nullable Instant completedAt) {}

  private final RiderStore store;
  private final org.springframework.beans.factory.ObjectProvider<PhoneVerificationService> phones;
  private final ExecutionClient execution;
  private final EventPublisher events;
  private final TransactionTemplate tx;
  private final Clock clock;

  public WithdrawalService(
      RiderStore store,
      ExecutionClient execution,
      EventPublisher events,
      TransactionTemplate tx,
      Clock clock,
      org.springframework.beans.factory.ObjectProvider<PhoneVerificationService> phones) {
    this.phones = phones;
    this.store = store;
    this.execution = execution;
    this.events = events;
    this.tx = tx;
    this.clock = clock;
  }

  /** 탈퇴 신청. 같은 requestId 로 이미 끝났으면 그 결과(완료)를 그대로. 거절됐던 요청은 다시 검사한다(막던 것이 풀렸을 수 있다). */
  public Withdrawal request(String requestId, @Nullable String reason) {
    String riderId = RiderService.requireRider();
    var done = store.withdrawal(riderId, requestId).filter(w -> "COMPLETED".equals(w.state()));
    if (done.isPresent()) {
      return done.get();
    }
    if (store.find(riderId).isEmpty()) {
      throw PmartException.notFound("RIDER_NOT_FOUND", "가입한 라이더가 아닙니다.");
    }
    // 외부 조회는 트랜잭션 밖 — 못 부르면 503(기록 없음, 다시 누르면 된다)
    List<String> blockers = new ArrayList<>(execution.withdrawalBlockers(riderId));
    Withdrawal out =
        tx.execute(
            s -> {
              Instant now = clock.instant();
              Rider r =
                  store
                      .lock(riderId)
                      .orElseThrow(
                          () -> PmartException.notFound("RIDER_NOT_FOUND", "가입한 라이더가 아닙니다."));
              if (store.openRestrictionReviews(riderId) > 0) {
                blockers.add(RESTRICTION_REVIEW);
              }
              if (!blockers.isEmpty()) {
                store.upsertWithdrawal(riderId, requestId, "REJECTED", blockers, reason, now, null);
                return new Withdrawal(requestId, "REJECTED", List.copyOf(blockers), now, null);
              }
              // 탈퇴 번호 30일 재가입 막기(2026-11-19 사용자 결정) — 번호를 지우기 전에 해시로 남긴다
              store.find(riderId).ifPresent(me -> phones.getObject().blockRejoin(me.phone(), now));
              store.withdraw(riderId, now);
              store.recordDuty(riderId, true, true, false, false, now); // 열린 운행 구간 닫기
              store.upsertWithdrawal(riderId, requestId, "COMPLETED", List.of(), reason, now, now);
              Map<String, Object> p = new LinkedHashMap<>();
              p.put("riderId", riderId);
              p.put("requestId", requestId);
              p.put("withdrawnAt", now.toString());
              events.publish(
                  "RiderWithdrawn",
                  1,
                  "Rider",
                  riderId,
                  r.version() + 1L,
                  new EventEnvelope.Actor(EventEnvelope.ActorType.RIDER, riderId),
                  p);
              return new Withdrawal(requestId, "COMPLETED", List.of(), now, now);
            });
    if ("REJECTED".equals(out.state())) {
      log.info("라이더 {} 탈퇴 신청 거절 — {}", riderId, out.blockers());
      throw new PmartException(
          "INVALID_STATE",
          HttpStatus.CONFLICT,
          "지금은 탈퇴할 수 없습니다: " + String.join(", ", out.blockers()),
          false,
          out.blockers().stream().map(b -> new PmartException.Detail("blockers", b)).toList());
    }
    log.info("라이더 {} 탈퇴 완료 — 개인정보 지움", riderId);
    return out;
  }

  public Withdrawal result(String requestId) {
    String riderId = RiderService.requireRider();
    return store
        .withdrawal(riderId, requestId)
        .orElseThrow(() -> PmartException.notFound("WITHDRAWAL_NOT_FOUND", "탈퇴 신청이 없습니다."));
  }
}
