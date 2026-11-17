package com.pmart.cases;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** case-service. 책임·비소유는 services/case-service/CLAUDE.md 와 docs/architecture/service-map.md. */
@SpringBootApplication
@EnableScheduling // 문의 사진 정리(0.14.0 — InquiryPhotoSweeper)
public class CaseServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(CaseServiceApplication.class, args);
  }
}
