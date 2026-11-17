package com.pmart.cases.infrastructure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pmart.storage.s3.*} — 문의 사진 저장소(S3 호환, ADR-0016 — 0.14.0). 자격 증명은 설정 저장소의 {@code {cipher}}
 * 값으로만 받는다. 로그·응답에 남기지 않는다(toString 에서 가린다).
 *
 * @param endpoint 비우면 AWS 기본. SeaweedFS 는 {@code http://s3:8333}
 * @param createBucket 버킷이 없을 때 만들지(로컬·시험만). 아니면 기동 실패
 */
@ConfigurationProperties(prefix = "pmart.storage.s3")
public record S3Properties(
    @Nullable String endpoint,
    @DefaultValue("us-east-1") String region,
    @Nullable String bucket,
    @Nullable String accessKey,
    @Nullable String secretKey,
    @DefaultValue("true") boolean pathStyle,
    @DefaultValue("false") boolean createBucket) {

  @Override
  public String toString() {
    return "S3Properties[endpoint="
        + endpoint
        + ", region="
        + region
        + ", bucket="
        + bucket
        + ", pathStyle="
        + pathStyle
        + ", createBucket="
        + createBucket
        + "]";
  }
}
