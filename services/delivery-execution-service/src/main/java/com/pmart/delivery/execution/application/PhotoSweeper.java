package com.pmart.delivery.execution.application;

import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 참조 없는 증빙 사진 객체 정리(2026-11-14, PC 1 PR #12 검토 후속, 사용자 결정 "5시간이 지나면 삭제").
 *
 * <p>사진 올리기는 저장소에 먼저 올리고 참조를 트랜잭션으로 저장한다. 트랜잭션이 실패한 요청의 객체를 바로 지우면, 같은 요청이 동시에 와 같은 객체 키로 성공한 쪽의 사진
 * 파일을 지울 수 있었다. 그래서 실패해도 지우지 않고, 올리는 중 기록({@code photo_upload})이 {@code orphan-photo-after}(5시간) 넘게
 * 남아 있으면 여기서 참조가 없을 때만 지운다.
 *
 * <p>기록 한 줄마다 짧은 트랜잭션에서 그 줄을 잠그고 지운다 — 같은 요청이 그 사이 다시 올리기 시작하면(시작 시각 갱신) 잠그지 않고 건너뛰고, 잠근 동안 다시 올리려는
 * 요청은 이 정리가 끝난 뒤 기록을 새로 남기고 올린다.
 */
@Component
public class PhotoSweeper {

  private static final Logger log = LoggerFactory.getLogger(PhotoSweeper.class);

  private final ExecutionStore store;
  private final ObjectProvider<EvidencePhotoStorage> photos;
  private final ExecutionProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PhotoSweeper(
      ExecutionStore store,
      ObjectProvider<EvidencePhotoStorage> photos,
      ExecutionProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.photos = photos;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  @Scheduled(
      initialDelayString = "${pmart.execution.photo-sweep-interval:PT10M}",
      fixedDelayString = "${pmart.execution.photo-sweep-interval:PT10M}")
  public void sweep() {
    EvidencePhotoStorage storage = photos.getIfAvailable();
    if (storage == null) {
      return;
    }
    Instant before = clock.instant().minus(props.orphanPhotoAfter());
    int deleted = 0;
    for (String key : store.stalePhotoUploads(before, 200)) {
      Boolean gone =
          tx.execute(
              s -> {
                if (!store.lockStalePhotoUpload(key, before)) {
                  return false;
                }
                if (store.photoObjectReferenced(key)) {
                  store.forgetPhotoUpload(key); // 다른 요청이 같은 객체로 성공했다 — 객체는 그대로
                  return false;
                }
                if (!storage.delete(key)) {
                  return false; // 다음 정리 때 다시
                }
                store.forgetPhotoUpload(key);
                return true;
              });
      if (Boolean.TRUE.equals(gone)) {
        deleted++;
      }
    }
    if (deleted > 0) {
      log.info("참조 없는 증빙 사진 객체 {}개 지움({} 지남)", deleted, props.orphanPhotoAfter());
    }
  }
}
