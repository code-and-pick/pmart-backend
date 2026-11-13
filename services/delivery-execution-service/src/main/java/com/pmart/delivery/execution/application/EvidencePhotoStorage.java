package com.pmart.delivery.execution.application;

/** 전달 증빙 사진 원본 저장소(비공개). 장애는 DEPENDENCY_UNAVAILABLE 로 던진다. */
public interface EvidencePhotoStorage {

  void put(String key, byte[] bytes, String contentType);
}
