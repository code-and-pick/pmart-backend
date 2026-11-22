package com.pmart.riderauth.infrastructure;

import com.pmart.common.web.Downstream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * rider-service 내부 호출 — 이 라이더 주체가 가입을 마쳤는가({@code GET /internal/riders/{id}} — 가입한, 탈퇴하지 않은 라이더면
 * 200, 아니면 404 RIDER_NOT_FOUND). 2026-11-22 RA-1 ③(네이버 가입을 마치지 않은 계정은 교환 때 연결 끊기 — 고객 BR-79).
 * {@code /internal} 은 토큰 없이(공용 RestClient 가 X-Source-System 을 붙인다).
 */
@Component
public class RiderClient {

  private final RestClient client;

  public RiderClient(
      RestClient.Builder builder,
      @Value("${pmart.rider-auth.rider-base-url:http://rider-service:8403}") String baseUrl) {
    this.client = builder.clone().baseUrl(baseUrl).build();
  }

  /**
   * @return 가입을 마친 라이더면 true, 가입 전·탈퇴면 false
   * @throws RuntimeException 못 부름·그 밖 응답 — 호출자는 판단을 건너뛴다
   */
  public boolean signedUp(String riderId) {
    int status =
        Downstream.call(
            "rider-service internal rider",
            () ->
                client
                    .get()
                    .uri("/api/v1/internal/riders/{id}", riderId)
                    .exchange((req, res) -> res.getStatusCode().value()));
    if (status == 200) {
      return true;
    }
    if (status == 404) {
      return false;
    }
    throw new IllegalStateException("rider-service 응답 " + status);
  }
}
