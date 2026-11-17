package com.pmart.delivery.tracking.api;

import com.pmart.delivery.tracking.api.generated.EtaApi;
import com.pmart.delivery.tracking.api.generated.model.DeliveryEta;
import com.pmart.delivery.tracking.api.generated.model.StreamEvent;
import com.pmart.delivery.tracking.application.ProgressService;
import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 고객·직원 ETA 조회·실시간 구독(TR-3, 2026-11-17). 판정은 {@link ProgressService}. */
@RestController
public class EtaController implements EtaApi {

  private final ProgressService progress;
  private final EtaStreams streams;
  private final Clock clock;

  public EtaController(ProgressService progress, EtaStreams streams, Clock clock) {
    this.progress = progress;
    this.streams = streams;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<DeliveryEta> getDeliveryEta(String deliveryId) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(EtaViews.eta(progress.view(deliveryId), clock.instant()));
  }

  /**
   * SSE — 응답을 서블릿 비동기로 직접 쓰고 null 을 돌려준다(Spring 은 처리 끝으로 본다). 권한·404 는 스트림을 열기 전에 판정해 보통 오류 응답으로.
   */
  @Override
  public @Nullable ResponseEntity<StreamEvent> streamDeliveryTracking(
      String deliveryId, @Nullable String lastEventID) {
    ProgressService.View first = progress.view(deliveryId);
    ServletRequestAttributes attrs =
        (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
    streams.open(
        attrs.getRequest(),
        java.util.Objects.requireNonNull(attrs.getResponse()),
        first,
        lastEventID);
    return null;
  }
}
