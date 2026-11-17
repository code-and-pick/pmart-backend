package com.pmart.delivery.tracking.api;

import com.pmart.common.web.PmartException;
import com.pmart.delivery.tracking.api.generated.model.StreamEvent;
import com.pmart.delivery.tracking.application.ProgressService;
import com.pmart.delivery.tracking.domain.ProgressRules;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 실시간 ETA·진행 구독(TR-3, SSE). 생성된 계약 메서드는 {@code ResponseEntity<StreamEvent>} 라 흘려보낼 수 없어서 서블릿 비동기로
 * 직접 쓴다. 권한·404 는 접속 때 한 번 판정({@link ProgressService#view}). 그 뒤 {@code stream-poll-interval} 마다
 * 사본을 다시 읽어 바뀐 것만 보낸다 — 단계가 바뀌면 STAGE_CHANGED, 도착 예정만 바뀌면 ETA_UPDATED, 조용하면 15초마다 HEARTBEAT, DONE
 * 이면 STREAM_END 뒤 닫는다. 최대 연결 시간이 지나도 닫는다(앱은 Last-Event-ID 로 다시 접속).
 *
 * <p>쓰기는 갱신 스레드가 아니라 쓰기 스레드 묶음({@code stream-write-threads})에서 연결마다 한 번에 하나씩 한다(PC 1 #89 검토 후속): 멈춘
 * 클라이언트에 쓰다가 막혀도 다른 구독의 갱신은 늦어지지 않는다. 앞 쓰기가 아직 안 끝난 연결은 그 회차를 건너뛰고, {@code stream-write-timeout} 넘게
 * 막혀 있으면 닫는다.
 */
@Component
class EtaStreams {

  private static final Logger log = LoggerFactory.getLogger(EtaStreams.class);

  private final class Sub {
    final String deliveryId;
    final boolean staff;
    final AsyncContext ctx;
    final Instant openedAt;
    final java.util.concurrent.atomic.AtomicBoolean writing =
        new java.util.concurrent.atomic.AtomicBoolean();
    volatile @Nullable Instant writeStartedAt;
    String lastId;
    String lastStage;
    volatile Instant lastSent;
    volatile boolean closed;

    Sub(String deliveryId, boolean staff, AsyncContext ctx, Instant now, ProgressService.View v) {
      this.deliveryId = deliveryId;
      this.staff = staff;
      this.ctx = ctx;
      this.openedAt = now;
      this.lastId = EtaViews.fingerprint(v);
      this.lastStage = v.progress().stage();
      this.lastSent = now;
    }
  }

  private final List<Sub> subs = new CopyOnWriteArrayList<>();
  private final ProgressService progress;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Duration heartbeat;
  private final Duration maxDuration;
  private final int maxConnections;
  private final Duration writeTimeout;
  private final java.util.concurrent.ExecutorService writers;

  EtaStreams(
      ProgressService progress,
      ObjectMapper mapper,
      Clock clock,
      @Value("${pmart.tracking.stream-heartbeat:PT15S}") Duration heartbeat,
      @Value("${pmart.tracking.stream-max-duration:PT30M}") Duration maxDuration,
      @Value("${pmart.tracking.stream-max-connections:1000}") int maxConnections,
      @Value("${pmart.tracking.stream-poll-interval:PT3S}") Duration pollInterval,
      @Value("${pmart.tracking.stream-write-timeout:PT30S}") Duration writeTimeout,
      @Value("${pmart.tracking.stream-write-threads:4}") int writeThreads) {
    this.progress = progress;
    this.mapper = mapper;
    this.clock = clock;
    this.heartbeat = heartbeat;
    this.maxDuration = maxDuration;
    this.maxConnections = maxConnections;
    this.writeTimeout = writeTimeout;
    java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
    this.writers =
        java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(1, writeThreads),
            r -> {
              Thread t = new Thread(r, "eta-stream-write-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
    // 전용 스레드(PC 1 #75 검토 후속): 공용 @Scheduled 스레드 하나를 ETA 계산·위치 끊김 감시와 나눠 쓰면, 멈춘 클라이언트에 쓰다가
    // 막혔을 때 그 작업들까지 늦어진다
    this.poller =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "eta-stream-poll");
              t.setDaemon(true);
              return t;
            });
    long ms = Math.max(100, pollInterval.toMillis());
    this.poller.scheduleWithFixedDelay(
        () -> {
          try {
            poll();
          } catch (RuntimeException e) {
            log.warn("실시간 구독 갱신 실패: {}", e.toString());
          }
        },
        ms,
        ms,
        java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  private final java.util.concurrent.ScheduledExecutorService poller;

  @jakarta.annotation.PreDestroy
  void stop() {
    poller.shutdownNow();
    writers.shutdownNow();
  }

  /** 접속 — 권한 판정이 끝난 View 로. 지금 상태를 바로 보내고(Last-Event-ID 가 같으면 건너뜀), DONE 이면 바로 닫는다. */
  void open(
      HttpServletRequest req,
      HttpServletResponse res,
      ProgressService.View first,
      @Nullable String lastEventId) {
    if (subs.size() >= maxConnections) {
      throw new PmartException(
          "STREAM_LIMIT",
          HttpStatus.SERVICE_UNAVAILABLE,
          "실시간 구독이 많습니다. 잠시 뒤 다시 접속하거나 조회를 쓰세요.",
          true,
          List.of());
    }
    res.setStatus(200);
    res.setContentType("text/event-stream");
    res.setCharacterEncoding("UTF-8");
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Accel-Buffering", "no"); // 앞단 프록시가 모아 보내지 않게
    AsyncContext ctx = req.startAsync();
    ctx.setTimeout(maxDuration.toMillis() + 60_000);
    Instant now = clock.instant();
    Sub s = new Sub(first.progress().deliveryId(), first.staff(), ctx, now, first);
    ctx.addListener(
        new AsyncListener() {
          @Override
          public void onComplete(AsyncEvent e) {
            s.closed = true;
            subs.remove(s);
          }

          @Override
          public void onTimeout(AsyncEvent e) {
            close(s);
          }

          @Override
          public void onError(AsyncEvent e) {
            close(s);
          }

          @Override
          public void onStartAsync(AsyncEvent e) {}
        });
    boolean ok = true;
    if (lastEventId == null || !lastEventId.equals(s.lastId)) {
      ok = send(s, StreamEvent.TypeEnum.ETA_UPDATED, first, now);
    }
    if (!ok) {
      return;
    }
    if (ProgressRules.DONE.equals(first.progress().stage())) {
      send(s, StreamEvent.TypeEnum.STREAM_END, first, now);
      close(s);
      return;
    }
    subs.add(s);
  }

  void poll() {
    Instant now = clock.instant();
    // 한 번 돌 때 같은 배송·같은 보기 범위는 한 번만 읽는다(같은 배송을 여럿이 볼 때 DB 조회가 겹치지 않게)
    java.util.Map<String, java.util.Optional<ProgressService.View>> seen =
        new java.util.HashMap<>();
    for (Sub s : subs) {
      if (s.closed) {
        subs.remove(s);
        continue;
      }
      if (s.writing.get()) {
        // 앞 쓰기가 아직 막혀 있음 — 이번 회차는 건너뛰고, 너무 오래면 닫는다
        Instant started = s.writeStartedAt;
        if (started != null && !now.isBefore(started.plus(writeTimeout))) {
          log.warn("배송 {} 구독 쓰기가 {} 넘게 막힘 — 닫음", s.deliveryId, writeTimeout);
          close(s);
        }
        continue;
      }
      try {
        tick(
            s,
            now,
            seen.computeIfAbsent(
                    s.deliveryId + (s.staff ? "#S" : "#C"),
                    k -> java.util.Optional.ofNullable(progress.refresh(s.deliveryId, s.staff)))
                .orElse(null));
      } catch (RuntimeException e) {
        log.warn("배송 {} 구독 갱신 실패 — 닫음: {}", s.deliveryId, e.toString());
        close(s);
      }
    }
  }

  private void tick(Sub s, Instant now, ProgressService.@Nullable View v) {
    if (v == null) {
      close(s); // 사본이 지워짐
      return;
    }
    String id = EtaViews.fingerprint(v);
    StreamEvent.@Nullable TypeEnum type = null;
    if (!id.equals(s.lastId)) {
      type =
          v.progress().stage().equals(s.lastStage)
              ? StreamEvent.TypeEnum.ETA_UPDATED
              : StreamEvent.TypeEnum.STAGE_CHANGED;
      s.lastId = id;
      s.lastStage = v.progress().stage();
    } else if (!now.isBefore(s.lastSent.plus(heartbeat))) {
      type = StreamEvent.TypeEnum.HEARTBEAT;
    }
    boolean end =
        ProgressRules.DONE.equals(v.progress().stage())
            || !now.isBefore(s.openedAt.plus(maxDuration));
    if (type == null && !end) {
      return;
    }
    StreamEvent.@Nullable TypeEnum first = type;
    s.writing.set(true);
    s.writeStartedAt = now;
    try {
      writers.execute(
          () -> {
            try {
              boolean ok =
                  first == null
                      || send(s, first, first == StreamEvent.TypeEnum.HEARTBEAT ? null : v, now);
              if (ok && end) {
                send(s, StreamEvent.TypeEnum.STREAM_END, v, now);
                close(s);
              }
            } catch (RuntimeException e) {
              log.warn("배송 {} 구독 쓰기 실패 — 닫음: {}", s.deliveryId, e.toString());
              close(s);
            } finally {
              s.writeStartedAt = null;
              s.writing.set(false);
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      s.writing.set(false);
      close(s); // 끄는 중
    }
  }

  /** 한 이벤트 쓰기. 못 쓰면(끊김) 닫고 false. */
  private boolean send(
      Sub s, StreamEvent.TypeEnum type, ProgressService.@Nullable View v, Instant now) {
    StreamEvent e =
        new StreamEvent()
            .id(s.lastId)
            .type(type)
            .deliveryId(s.deliveryId)
            .sentAt(EtaViews.time(now));
    if (v != null) {
      e.eta(EtaViews.eta(v, now)).stage(v.progress().stage());
    }
    try {
      PrintWriter out = s.ctx.getResponse().getWriter();
      out.write("id: " + s.lastId + "\n");
      out.write("event: " + type.getValue() + "\n");
      out.write("data: " + mapper.writeValueAsString(e) + "\n\n");
      out.flush();
      if (out.checkError()) {
        throw new IOException("client gone");
      }
      s.lastSent = now;
      return true;
    } catch (IOException | IllegalStateException ex) {
      close(s);
      return false;
    }
  }

  private void close(Sub s) {
    if (s.closed) {
      return;
    }
    s.closed = true;
    subs.remove(s);
    try {
      s.ctx.complete();
    } catch (IllegalStateException ignored) {
      // 이미 끝남
    }
  }

  int size() {
    return subs.size();
  }
}
