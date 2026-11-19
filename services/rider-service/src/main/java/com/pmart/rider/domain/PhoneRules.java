package com.pmart.rider.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/** 라이더 휴대폰 번호 확인(2026-11-19 — OCTOMO, 고객 PhoneVerificationRules 와 같은 규칙). */
public final class PhoneRules {

  private PhoneRules() {}

  public static final String PENDING = "PENDING";
  public static final String VERIFIED = "VERIFIED";
  public static final String FAILED = "FAILED";
  public static final String EXPIRED = "EXPIRED";

  private static final SecureRandom RANDOM = new SecureRandom();

  /** OCTOMO 가 받는 번호 — 010 + 8자리. */
  public static boolean supported(@Nullable String phone) {
    return phone != null && phone.matches("^010\\d{8}$");
  }

  public static String newCode() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }

  /** 원문 대신 두는 해시(확인·재가입 막기). */
  public static String hash(String phone) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(("rider-phone:" + phone).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String mask(String phone) {
    return phone.length() < 8
        ? "***"
        : phone.substring(0, 3) + "-****-" + phone.substring(phone.length() - 4);
  }
}
