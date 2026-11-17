package com.pmart.notification.infrastructure;

import com.pmart.notification.application.NotificationProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FCM HTTP v1 발송(2026-11-17 — 사용자 "발송 코드만 먼저, 키는 나중"). 서비스 계정 키(JSON —
 * project_id·client_email·private_key)가 {@code pmart.notification.fcm.service-account-json} 에 없으면
 * 꺼짐({@link #enabled()} false — 알림함만). 키로 OAuth 접근 토큰(RS256 JWT → token_uri)을 받아 캐시하고 단말 토큰마다
 * {@code messages:send}. 새 의존성 없이 JDK 서명. 토큰·키·알림 내용은 로그에 남기지 않는다.
 */
@Component
public class FcmSender {

  private static final Logger log = LoggerFactory.getLogger(FcmSender.class);
  static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

  /** 발송 결과 — UNREGISTERED 면 그 단말 토큰을 지운다. */
  public enum Result {
    SENT,
    UNREGISTERED,
    FAILED
  }

  private record Account(String projectId, String clientEmail, PrivateKey key, String tokenUri) {}

  private final @Nullable Account account;
  private final RestClient http;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final String sendBaseUrl;
  private @Nullable String accessToken;
  private Instant accessTokenUntil = Instant.EPOCH;

  public FcmSender(
      NotificationProperties props, RestClient.Builder builder, ObjectMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
    this.http = builder.clone().build();
    NotificationProperties.Fcm fcm = props.fcm();
    this.sendBaseUrl =
        fcm == null || fcm.sendBaseUrl() == null ? "https://fcm.googleapis.com" : fcm.sendBaseUrl();
    this.account = fcm == null ? null : parse(fcm.serviceAccountJson(), fcm.tokenUri(), mapper);
    if (account == null) {
      log.info("FCM 키 없음 — 라이더 푸시 꺼짐(알림함만)");
    }
  }

  public boolean enabled() {
    return account != null;
  }

  /** 단말 하나에 보낸다. 실패는 결과로만(알림 실패로 업무를 다시 하지 않는다). */
  public Result send(String deviceToken, String title, String body, Map<String, String> data) {
    Account a = account;
    if (a == null) {
      return Result.FAILED;
    }
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("token", deviceToken);
    message.put("notification", Map.of("title", title, "body", body));
    message.put("data", data);
    message.put("android", Map.of("priority", "HIGH"));
    try {
      http.post()
          .uri(sendBaseUrl + "/v1/projects/{p}/messages:send", a.projectId())
          .header("Authorization", "Bearer " + token(a))
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("message", message))
          .retrieve()
          .toBodilessEntity();
      return Result.SENT;
    } catch (HttpClientErrorException e) {
      // 404 NOT_FOUND·UNREGISTERED, 400 INVALID_ARGUMENT(토큰 형식) — 그 단말은 더 쓸 수 없다
      String b = e.getResponseBodyAsString();
      if (e.getStatusCode().value() == 404 || b.contains("UNREGISTERED")) {
        return Result.UNREGISTERED;
      }
      if (e.getStatusCode().value() == 401) {
        synchronized (this) {
          accessToken = null; // 다음에 새로 받는다
        }
      }
      log.warn("FCM 발송 실패 HTTP {}", e.getStatusCode().value());
      return Result.FAILED;
    } catch (RuntimeException e) {
      log.warn("FCM 발송 실패: {}", e.getClass().getSimpleName());
      return Result.FAILED;
    }
  }

  private synchronized String token(Account a) {
    Instant now = clock.instant();
    if (accessToken != null && now.isBefore(accessTokenUntil)) {
      return accessToken;
    }
    long iat = now.getEpochSecond();
    String header = b64(mapper.writeValueAsString(Map.of("alg", "RS256", "typ", "JWT")));
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", a.clientEmail());
    claims.put("scope", SCOPE);
    claims.put("aud", a.tokenUri());
    claims.put("iat", iat);
    claims.put("exp", iat + 3600);
    String unsigned = header + "." + b64(mapper.writeValueAsString(claims));
    String jwt;
    try {
      Signature s = Signature.getInstance("SHA256withRSA");
      s.initSign(a.key());
      s.update(unsigned.getBytes(StandardCharsets.US_ASCII));
      jwt = unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("FCM 서명 실패", e);
    }
    LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
    form.add("assertion", jwt);
    String raw =
        http.post()
            .uri(a.tokenUri())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(String.class);
    JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    accessToken = n.path("access_token").asString(null);
    if (accessToken == null) {
      throw new IllegalStateException("FCM 접근 토큰 응답에 access_token 없음");
    }
    long ttl = n.path("expires_in").asLong(3600);
    accessTokenUntil = now.plus(Duration.ofSeconds(Math.max(60, ttl - 60)));
    return accessToken;
  }

  private static String b64(String s) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  private static @Nullable Account parse(
      @Nullable String json, @Nullable String tokenUriOverride, ObjectMapper mapper) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode n = mapper.readTree(json);
      String pem =
          n.path("private_key")
              .asString("")
              .replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      PrivateKey key =
          KeyFactory.getInstance("RSA")
              .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      String tokenUri =
          tokenUriOverride != null
              ? tokenUriOverride
              : n.path("token_uri").asString("https://oauth2.googleapis.com/token");
      return new Account(
          n.path("project_id").asString(), n.path("client_email").asString(), key, tokenUri);
    } catch (RuntimeException | java.security.GeneralSecurityException e) {
      log.warn("FCM 서비스 계정 키를 읽지 못함 — 푸시 꺼짐: {}", e.getClass().getSimpleName());
      return null;
    }
  }
}
