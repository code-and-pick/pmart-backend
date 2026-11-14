package com.pmart.delivery.execution.application;

/** 전달 증빙 사진 원본 저장소(비공개). 장애는 DEPENDENCY_UNAVAILABLE 로 던진다. */
public interface EvidencePhotoStorage {

  /** 같은 키로 다시 올리면 덮어쓴다(같은 요청의 재시도). */
  void put(String key, byte[] bytes, String contentType);

  /** 참조를 남기지 못한 객체 지우기 — 할 수 있는 만큼만(실패해도 던지지 않는다, 호출자의 원래 오류를 가리지 않게). */
  default void delete(String key) {}
}
