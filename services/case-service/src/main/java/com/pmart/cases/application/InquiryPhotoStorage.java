package com.pmart.cases.application;

import java.io.InputStream;

/** 문의 사진 본문 저장소(S3 호환 — LAN SeaweedFS 버킷 pmart-case, 0.14.0). 실패는 503 DEPENDENCY_UNAVAILABLE. */
public interface InquiryPhotoStorage {

  void put(String key, byte[] bytes, String contentType);

  /** 본문. 없으면 404 PHOTO_NOT_FOUND. */
  InputStream get(String key);

  /** 지우기. 실패하면 false(다음 정리 때 다시). */
  boolean delete(String key);
}
