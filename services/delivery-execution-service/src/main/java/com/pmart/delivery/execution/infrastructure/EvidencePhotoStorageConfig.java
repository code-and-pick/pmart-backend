package com.pmart.delivery.execution.infrastructure;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.application.EvidencePhotoStorage;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 전달 증빙 사진 저장소(customer-service 프로필 사진 사본과 같은 방식 — ADR-0016·0024). 버킷({@code PMART_S3_BUCKET})이 있을
 * 때만 만든다. 버킷이 있는데 자격 증명이 없으면 기동하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3Properties.class)
class EvidencePhotoStorageConfig {

  private static final Logger log = LoggerFactory.getLogger(EvidencePhotoStorageConfig.class);

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnExpression("!'${pmart.storage.s3.bucket:}'.isBlank()")
  static class Enabled {

    @Bean(destroyMethod = "close")
    S3Client evidenceS3Client(S3Properties p) {
      if (blank(p.accessKey()) || blank(p.secretKey())) {
        throw new IllegalStateException(
            "증빙 사진 저장소 자격 증명이 없습니다: PMART_S3_ACCESS_KEY, PMART_S3_SECRET_KEY 가 필요합니다.");
      }
      S3ClientBuilder b =
          S3Client.builder()
              .region(Region.of(p.region()))
              .credentialsProvider(
                  StaticCredentialsProvider.create(
                      AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
              .forcePathStyle(p.pathStyle());
      if (!blank(p.endpoint())) {
        b.endpointOverride(URI.create(p.endpoint()));
      }
      return b.build();
    }

    @Bean
    EvidencePhotoStorage evidencePhotoStorage(S3Client client, S3Properties p) {
      String bucket = p.bucket();
      try {
        client.headBucket(b -> b.bucket(bucket));
      } catch (S3Exception e) {
        if (e.statusCode() != 404 || !p.createBucket()) {
          throw new IllegalStateException(
              "증빙 사진 버킷 '" + bucket + "' 을 쓸 수 없습니다(HTTP " + e.statusCode() + ")", e);
        }
        client.createBucket(b -> b.bucket(bucket));
      }
      return new EvidencePhotoStorage() {
        @Override
        public void put(String key, byte[] bytes, String contentType) {
          try {
            client.putObject(
                r ->
                    r.bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength((long) bytes.length),
                RequestBody.fromBytes(bytes));
          } catch (SdkException e) {
            throw PmartException.dependencyUnavailable("증빙 사진 저장소에 연결할 수 없습니다.", e);
          }
        }

        @Override
        public void delete(String key) {
          try {
            client.deleteObject(r -> r.bucket(bucket).key(key));
          } catch (SdkException e) {
            log.warn("증빙 사진 객체 {} 지우기 실패 — 주인 없는 객체로 남음: {}", key, e.toString());
          }
        }
      };
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
