package com.pmart.delivery.execution.application;

/** 전달 증빙 사진 원본 저장소(비공개). 장애는 DEPENDENCY_UNAVAILABLE 로 던진다. */
public interface EvidencePhotoStorage {

  /** 같은 키로 다시 올리면 덮어쓴다(같은 요청의 재시도). */
  void put(String key, byte[] bytes, String contentType);

  /**
   * 참조가 없는 객체 지우기({@link PhotoSweeper}) — 던지지 않는다. @return 지웠으면(또는 이미 없으면) true, 실패면 false(다음 정리 때
   * 다시)
   */
  default boolean delete(String key) {
    return true;
  }
}
