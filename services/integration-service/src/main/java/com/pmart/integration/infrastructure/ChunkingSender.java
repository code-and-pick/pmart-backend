package com.pmart.integration.infrastructure;

import com.pmart.integration.application.DocsProperties;
import com.pmart.integration.domain.ExtractedDocument;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 추출 내용을 청킹 서비스로 보낸다({@code POST pmart.integration.docs.chunking-url}, JSON {@link
 * ExtractedDocument}). 주소가 없으면 보내지 않고 기록만 남긴다(청킹 서비스가 생기기 전). 본문은 로그에 싣지 않는다.
 */
@Component
public class ChunkingSender {

  private static final Logger log = LoggerFactory.getLogger(ChunkingSender.class);

  private final DocsProperties props;
  private final RestClient client;

  public ChunkingSender(DocsProperties props) {
    this.props = props;
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // 평문 http 에서 h2c 업그레이드가 POST 본문을 잃는다(시험에서 빈 본문)
            .connectTimeout(props.timeout())
            .build();
    JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
    rf.setReadTimeout(props.timeout());
    this.client = RestClient.builder().requestFactory(rf).build();
  }

  /** 보냈으면 true, 주소가 없어 기록만 했으면 false. 실패는 예외(흐름이 다시 시도). */
  public boolean send(ExtractedDocument doc) {
    if (!props.chunkingConfigured()) {
      log.info(
          "추출 완료(청킹 서비스 주소 없음 — 보내지 않음) path={} status={} commit={} sha256={} chars={} delivery={}",
          doc.path(),
          doc.status(),
          shortSha(doc.commit()),
          doc.contentSha256(),
          doc.content() == null ? 0 : doc.content().length(),
          doc.deliveryId());
      return false;
    }
    client
        .post()
        .uri(props.chunkingUrl())
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Source-System", "integration-service")
        // 공통 규칙(common-web): 변경 요청은 Idempotency-Key — 같은 커밋·경로·상태는 같은 키(받는 쪽은 문서 해시로도 멱등)
        .header("Idempotency-Key", idempotencyKey(doc))
        .body(doc)
        .retrieve()
        .toBodilessEntity();
    log.info(
        "청킹 서비스로 보냄 path={} status={} commit={} delivery={}",
        doc.path(),
        doc.status(),
        shortSha(doc.commit()),
        doc.deliveryId());
    return true;
  }

  static String idempotencyKey(ExtractedDocument doc) {
    return java.util
        .UUID
        .nameUUIDFromBytes(
            (doc.commit() + "|" + doc.path() + "|" + doc.status())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }

  private static String shortSha(String sha) {
    return sha.length() > 7 ? sha.substring(0, 7) : sha;
  }
}
