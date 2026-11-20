package com.pmart.rider.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.PmartException;
import com.pmart.rider.domain.PhoneRules;
import com.pmart.rider.infrastructure.OctomoClient;
import com.pmart.rider.infrastructure.PhoneVerificationStore;
import com.pmart.rider.infrastructure.PhoneVerificationStore.Row;
import com.pmart.rider.infrastructure.RiderAuthClient;
import com.pmart.rider.infrastructure.RiderStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 라이더 휴대폰 번호 확인(2026-11-19 — 사용자 "전화번호로 신원인증하는 건 옥토모", 라이더 앱 요청 R2~R4). 고객과 같은 MO 방식: 6자리를 주면 라이더가
 * 대표번호로 문자를 보내고, "보냈어요" 때만 OCTOMO 에 묻는다. 그 번호의 다른 라이더 계정이 있으면 IDENTITY_ALREADY_REGISTERED(로그인
 * 수단·가입일·가린 이메일 — 계정 하나 = 제공자 하나), 탈퇴 뒤 {@code rejoin-block}(기본 30일) 안이면 RIDER_REJOIN_BLOCKED. 번호
 * 원문은 두지 않는다(해시), 번호·숫자는 로그 금지.
 */
@Service
public class PhoneVerificationService {

  private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);

  /** 확인 뒤 가입까지 쓸 수 있는 시간. */
  static final Duration USABLE_AFTER_VERIFY = Duration.ofHours(1);

  public record Started(
      String verificationId,
      String sendTo,
      String text,
      String expiresAt,
      String phoneMasked,
      int maxAttempts) {}

  public record View(
      String verificationId,
      String state,
      @Nullable String failureReason,
      int remainingAttempts,
      Instant expiresAt,
      @Nullable Instant verifiedAt) {}

  private final PhoneVerificationStore store;
  private final RiderStore riders;
  private final OctomoClient octomo;
  private final RiderAuthClient auth;
  private final PhoneVerificationProperties props;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public PhoneVerificationService(
      PhoneVerificationStore store,
      RiderStore riders,
      OctomoClient octomo,
      RiderAuthClient auth,
      PhoneVerificationProperties props,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.riders = riders;
    this.octomo = octomo;
    this.auth = auth;
    this.props = props;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  private PhoneVerificationProperties cfg() {
    if (!props.complete()) {
      throw PmartException.configRequired("pmart.rider.phone-verification");
    }
    return props;
  }

  /** 가입·확인 시작 공통 — 다른 라이더의 번호이거나 탈퇴 뒤 {@code rejoin-block} 안이면 409. */
  void requireJoinable(String subject, String phone) {
    riders
        .phoneOwner(phone)
        .filter(owner -> !owner.riderId().equals(subject))
        .ifPresent(
            owner -> {
              List<PmartException.Detail> d = new ArrayList<>();
              auth.identity(owner.riderId())
                  .ifPresent(
                      i -> {
                        d.add(new PmartException.Detail("provider", i.provider()));
                        if (i.emailMasked() != null) {
                          d.add(new PmartException.Detail("emailMasked", i.emailMasked()));
                        }
                      });
              d.add(new PmartException.Detail("joinedAt", owner.joinedAt().toString()));
              throw new PmartException(
                  "IDENTITY_ALREADY_REGISTERED",
                  HttpStatus.CONFLICT,
                  "이 번호로 가입한 계정이 있어요. 그 계정으로 로그인해 주세요.",
                  false,
                  d);
            });
    store
        .blockedUntil(PhoneRules.hash(phone), clock.instant())
        .ifPresent(
            until -> {
              throw new PmartException(
                  "RIDER_REJOIN_BLOCKED",
                  HttpStatus.CONFLICT,
                  "탈퇴한 번호는 " + PhoneRules.spanText(props.rejoinBlock()) + " 동안 다시 가입할 수 없어요.",
                  false,
                  List.of(new PmartException.Detail("until", until.toString())));
            });
  }

  public Started start(String subject, String requestId, String source, String phone) {
    PhoneVerificationProperties c = cfg();
    if (!PhoneRules.supported(phone)) {
      throw new PmartException(
          "PHONE_NOT_SUPPORTED",
          HttpStatus.BAD_REQUEST,
          "010 휴대폰 번호만 확인할 수 있어요.",
          false,
          List.of());
    }
    return idempotency
        .execute(
            new RequestKey(source, requestId, "startRiderPhoneVerification", subject),
            Map.of("phoneHash", PhoneRules.hash(phone)),
            Started.class,
            () -> {
              requireJoinable(subject, phone);
              Instant now = clock.instant();
              if (store.startsSince(subject, now.minus(Duration.ofDays(1)))
                  >= c.maxStartsPerDay()) {
                throw new PmartException(
                    "TOO_MANY_VERIFICATIONS",
                    HttpStatus.TOO_MANY_REQUESTS,
                    "오늘은 번호 확인을 더 시작할 수 없어요. 내일 다시 시도해 주세요.",
                    false,
                    List.of());
              }
              String id = "RPV-" + UUID.randomUUID();
              String code = PhoneRules.newCode();
              Instant expires = now.plus(c.ttl());
              store.start(
                  new Row(
                      id,
                      subject,
                      PhoneRules.hash(phone),
                      code,
                      PhoneRules.PENDING,
                      0,
                      null,
                      expires,
                      now,
                      null));
              log.info("라이더 휴대폰 확인 시작 {} {}", subject, id);
              return new Started(
                  id,
                  c.sendTo(),
                  code,
                  expires.toString(),
                  PhoneRules.mask(phone),
                  c.maxAttempts());
            })
        .value();
  }

  public View get(String subject, String verificationId) {
    return view(own(subject, verificationId), cfg());
  }

  public View confirm(String subject, String verificationId, String phone) {
    PhoneVerificationProperties c = cfg();
    Row r = own(subject, verificationId);
    if (!PhoneRules.PENDING.equals(r.state())) {
      return view(r, c);
    }
    Instant now = clock.instant();
    if (!now.isBefore(r.expiresAt())) {
      store.finish(verificationId, PhoneRules.EXPIRED, null, now);
      return view(own(subject, verificationId), c);
    }
    if (!PhoneRules.supported(phone) || !PhoneRules.hash(phone).equals(r.phoneHash())) {
      store.finish(verificationId, PhoneRules.FAILED, "PHONE_CHANGED", now);
      return view(own(subject, verificationId), c);
    }
    boolean arrived =
        octomo.exists(c.octomoBaseUrl(), c.apiKey(), phone, r.code(), c.withinMinutes());
    Instant at = clock.instant();
    if (arrived) {
      store.verify(verificationId, at);
      log.info("라이더 휴대폰 확인됨 {} {}", subject, verificationId);
    } else {
      store.miss(verificationId, c.maxAttempts(), at);
    }
    return view(own(subject, verificationId), c);
  }

  /**
   * 가입 때(호출자 트랜잭션 안): 확인 ID 가 있으면 VERIFIED·같은 주체·같은 번호·확인 뒤 1시간 안이어야 한다. 없으면 {@code required} 일 때만
   * 422. 확인됐으면 true.
   */
  public boolean requireForSignup(String subject, String phone, @Nullable String verificationId) {
    if (verificationId == null || verificationId.isBlank()) {
      if (props.required()) {
        throw notVerified();
      }
      return false;
    }
    Row r =
        store
            .find(verificationId)
            .filter(x -> x.subject().equals(subject))
            .orElseThrow(PhoneVerificationService::notVerified);
    Instant now = clock.instant();
    if (!PhoneRules.VERIFIED.equals(r.state())
        || !PhoneRules.hash(phone).equals(r.phoneHash())
        || r.finishedAt() == null
        || now.isAfter(r.finishedAt().plus(USABLE_AFTER_VERIFY))) {
      throw notVerified();
    }
    return true;
  }

  /** 탈퇴 때 — 그 번호를 {@code rejoin-block} 동안 막는다(번호는 해시로만). */
  public void blockRejoin(@Nullable String phone, Instant now) {
    if (phone != null && !phone.isBlank()) {
      store.block(PhoneRules.hash(phone), now.plus(props.rejoinBlock()), now);
    }
  }

  static PmartException notVerified() {
    return new PmartException(
        "PHONE_NOT_VERIFIED",
        HttpStatus.UNPROCESSABLE_CONTENT,
        "휴대폰 번호 확인을 먼저 해 주세요.",
        false,
        List.of());
  }

  private Row own(String subject, String verificationId) {
    return store
        .find(verificationId)
        .filter(r -> r.subject().equals(subject))
        .orElseThrow(
            () -> PmartException.notFound("PHONE_VERIFICATION_NOT_FOUND", "번호 확인을 찾을 수 없습니다."));
  }

  private View view(Row r, PhoneVerificationProperties c) {
    boolean expired =
        PhoneRules.PENDING.equals(r.state()) && !clock.instant().isBefore(r.expiresAt());
    return new View(
        r.verificationId(),
        expired ? PhoneRules.EXPIRED : r.state(),
        r.failureReason(),
        Math.max(0, c.maxAttempts() - r.attempts()),
        r.expiresAt(),
        PhoneRules.VERIFIED.equals(r.state()) ? r.finishedAt() : null);
  }
}
