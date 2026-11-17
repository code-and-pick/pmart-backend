package com.pmart.cases.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 문의에 붙지 않은 사진 정리(1시간마다, 하루 지난 것 — 0.14.0). 여러 인스턴스가 같이 돌아도 지우기는 멱등이다. */
@Component
class InquiryPhotoSweeper {

  private static final Logger log = LoggerFactory.getLogger(InquiryPhotoSweeper.class);

  private final InquiryPhotoService photos;

  InquiryPhotoSweeper(InquiryPhotoService photos) {
    this.photos = photos;
  }

  @Scheduled(
      initialDelayString = "${pmart.cases.photo-sweep-initial-delay:PT5M}",
      fixedDelayString = "${pmart.cases.photo-sweep-interval:PT1H}")
  void sweep() {
    try {
      int n = photos.sweepOrphans();
      if (n > 0) {
        log.info("붙지 않은 문의 사진 {}장 정리", n);
      }
    } catch (RuntimeException e) {
      log.warn("문의 사진 정리 실패 — 다음에 다시: {}", e.toString());
    }
  }
}
