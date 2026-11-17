package com.pmart.cases.infrastructure;

import com.pmart.cases.application.InquiryPhotoStorage;
import com.pmart.common.web.PmartException;
import java.io.InputStream;
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
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 문의 사진 저장소(review-service 사진과 같은 방식 — ADR-0016, 0.14.0). 버킷({@code pmart.storage.s3.bucket})이 있을
 * 때만 S3 를 쓴다. 버킷이 없으면 사진 기능만 503(문의 글은 그대로).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(S3Properties.class)
class InquiryPhotoStorageConfig {

  private static final Logger log = LoggerFactory.getLogger(InquiryPhotoStorageConfig.class);

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnExpression("!'${pmart.storage.s3.bucket:}'.isBlank()")
  static class Enabled {

    @Bean(destroyMethod = "close")
    S3Client inquiryS3Client(S3Properties p) {
      if (blank(p.accessKey()) || blank(p.secretKey())) {
        throw new IllegalStateException(
            "문의 사진 저장소 자격 증명이 없습니다: pmart.storage.s3.access-key·secret-key 가 필요합니다.");
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
    InquiryPhotoStorage inquiryPhotoStorage(S3Client client, S3Properties p) {
      String bucket = p.bucket();
      try {
        client.headBucket(b -> b.bucket(bucket));
      } catch (S3Exception e) {
        if (e.statusCode() != 404 || !p.createBucket()) {
          throw new IllegalStateException(
              "문의 사진 버킷 '" + bucket + "' 을 쓸 수 없습니다(HTTP " + e.statusCode() + ")", e);
        }
        client.createBucket(b -> b.bucket(bucket));
      }
      return new InquiryPhotoStorage() {
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
            throw PmartException.dependencyUnavailable("사진 저장소에 연결할 수 없습니다.", e);
          }
        }

        @Override
        public InputStream get(String key) {
          try {
            return client.getObject(r -> r.bucket(bucket).key(key));
          } catch (NoSuchKeyException e) {
            throw PmartException.notFound("PHOTO_NOT_FOUND", "사진이 없습니다.");
          } catch (SdkException e) {
            throw PmartException.dependencyUnavailable("사진 저장소에 연결할 수 없습니다.", e);
          }
        }

        @Override
        public boolean delete(String key) {
          try {
            client.deleteObject(r -> r.bucket(bucket).key(key));
            return true;
          } catch (SdkException e) {
            log.warn("문의 사진 객체 {} 지우기 실패 — 다음 정리 때 다시: {}", key, e.toString());
            return false;
          }
        }
      };
    }
  }

  /** 버킷 미설정: 사진만 503. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnExpression("'${pmart.storage.s3.bucket:}'.isBlank()")
  static class Disabled {
    @Bean
    InquiryPhotoStorage unavailablePhotoStorage() {
      return new InquiryPhotoStorage() {
        @Override
        public void put(String key, byte[] bytes, String contentType) {
          throw unavailable();
        }

        @Override
        public InputStream get(String key) {
          throw unavailable();
        }

        @Override
        public boolean delete(String key) {
          return false;
        }

        private PmartException unavailable() {
          return PmartException.dependencyUnavailable(
              "사진 저장소가 설정되지 않았습니다.", new IllegalStateException("pmart.storage.s3.bucket 없음"));
        }
      };
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
