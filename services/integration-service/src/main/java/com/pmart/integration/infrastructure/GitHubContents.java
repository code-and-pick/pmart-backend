package com.pmart.integration.infrastructure;

import com.pmart.integration.application.DocsProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

/**
 * GitHub Contents API 로 파일 한 판을 읽는다(로컬 저장 없음 — ADR-0095). private 저장소라 읽기 전용 토큰을 붙인다. 공용
 * RestClient.Builder 는 쓰지 않는다(사용자 토큰·내부 헤더를 바깥으로 보내지 않게).
 */
@Component
public class GitHubContents {

  private final RestClient client;
  private final DocsProperties props;

  public GitHubContents(DocsProperties props) {
    this.props = props;
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // 평문 http 에서 h2c 업그레이드가 POST 본문을 잃는다(시험에서 빈 본문)
            .connectTimeout(props.timeout())
            .build();
    JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
    rf.setReadTimeout(props.timeout());
    RestClient.Builder b =
        RestClient.builder()
            .requestFactory(rf)
            .baseUrl(props.githubApiUrl())
            .defaultHeader("Accept", "application/vnd.github.raw+json")
            .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
            .defaultHeader("User-Agent", "pmart-integration-service");
    if (props.githubToken() != null && !props.githubToken().isBlank()) {
      b.defaultHeader("Authorization", "Bearer " + props.githubToken().strip());
    }
    this.client = b.build();
  }

  /** {@code path} 의 {@code commit} 판 본문(UTF-8). 그 판에 파일이 없으면 빈 값. */
  public Optional<String> read(String path, String commit) {
    String encoded = UriUtils.encodePath(path, StandardCharsets.UTF_8);
    try {
      byte[] body =
          client
              .get()
              .uri("/repos/" + props.repository() + "/contents/" + encoded + "?ref=" + commit)
              .retrieve()
              .body(byte[].class);
      if (body == null) {
        return Optional.empty();
      }
      if (body.length > props.maxBytes()) {
        throw new IllegalStateException(
            "파일이 너무 큼: " + path + " " + body.length + " bytes(최대 " + props.maxBytes() + ")");
      }
      return Optional.of(new String(body, StandardCharsets.UTF_8));
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
        return Optional.empty();
      }
      throw e;
    }
  }
}
