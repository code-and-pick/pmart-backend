package com.pmart.integration.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/** GitHub 웹훅 서명 {@code X-Hub-Signature-256: sha256=<HMAC-SHA256 16진수>} 확인(본문 바이트 그대로, 시간 일정 비교). */
public final class WebhookSignature {

  private static final String PREFIX = "sha256=";

  private WebhookSignature() {}

  public static String sign(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 비밀이 비었거나 서명이 없거나 다르면 false. */
  public static boolean valid(@Nullable String secret, byte[] body, @Nullable String header) {
    if (secret == null || secret.isBlank() || header == null || !header.startsWith(PREFIX)) {
      return false;
    }
    return MessageDigest.isEqual(
        sign(secret, body).getBytes(StandardCharsets.US_ASCII),
        header.getBytes(StandardCharsets.US_ASCII));
  }
}
