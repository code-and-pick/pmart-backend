package com.pmart.delivery.tracking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pmart.delivery.tracking.application.ProgressService;
import com.pmart.delivery.tracking.infrastructure.ProgressStore;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 멈춘 연결 하나가 다른 구독의 갱신을 막지 않는다(PC 1 #89 검토 후속, 2026-11-17). */
class EtaStreamsTest {

  private static ProgressService.View view(String deliveryId, String stage, Instant etaDrop) {
    var p =
        new ProgressStore.Progress(
            deliveryId,
            "ORD-1",
            "MFC-1",
            stage,
            null,
            null,
            null,
            null,
            Instant.now(),
            "RD-1",
            null,
            etaDrop,
            Instant.now());
    return new ProgressService.View(p, true, true, false, null, null);
  }

  private record Conn(HttpServletRequest req, HttpServletResponse res, AsyncContext ctx) {}

  private static Conn conn(PrintWriter out) throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse res = mock(HttpServletResponse.class);
    AsyncContext ctx = mock(AsyncContext.class);
    when(req.startAsync()).thenReturn(ctx);
    when(ctx.getResponse()).thenReturn(res);
    when(res.getWriter()).thenReturn(out);
    return new Conn(req, res, ctx);
  }

  @Test
  void stuckClientDoesNotDelayOthersAndIsClosedAfterWriteTimeout() throws Exception {
    ProgressService progress = mock(ProgressService.class);
    Instant t0 = Instant.parse("2026-11-17T05:00:00Z");
    var stuckFirst = view("DLV-STUCK", "TO_CUSTOMER", t0);
    var okFirst = view("DLV-OK", "TO_CUSTOMER", t0);
    // 다음 회차: 둘 다 도착 예정이 바뀜 → 둘 다 써야 함
    when(progress.refresh(eq("DLV-STUCK"), anyBoolean(), org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(view("DLV-STUCK", "TO_CUSTOMER", t0.plusSeconds(120)));
    when(progress.refresh(eq("DLV-OK"), anyBoolean(), org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(view("DLV-OK", "TO_CUSTOMER", t0.plusSeconds(120)));

    CountDownLatch release = new CountDownLatch(1);
    OutputStream blocking =
        new OutputStream() {
          @Override
          public void write(int b) {
            await(release);
          }

          @Override
          public void write(byte[] b, int off, int len) {
            await(release);
          }
        };
    ByteArrayOutputStream okBytes = new ByteArrayOutputStream();
    Conn stuck = conn(new PrintWriter(new OutputStreamWriter(blocking, StandardCharsets.UTF_8)));
    Conn ok = conn(new PrintWriter(new OutputStreamWriter(okBytes, StandardCharsets.UTF_8)));

    EtaStreams streams =
        new EtaStreams(
            progress,
            JsonMapper.builder().build(),
            Clock.systemUTC(),
            Duration.ofMinutes(10), // 생존 신호는 이 시험에서 안 나오게
            Duration.ofMinutes(30),
            10,
            Duration.ofHours(1), // 갱신은 시험이 직접 부른다
            Duration.ofMillis(300),
            2);
    try {
      // 마지막으로 받은 ID 가 지금과 같으면 접속 때 쓰지 않는다
      streams.open(stuck.req(), stuck.res(), stuckFirst, EtaViews.fingerprint(stuckFirst));
      streams.open(ok.req(), ok.res(), okFirst, EtaViews.fingerprint(okFirst));
      assertThat(streams.size()).isEqualTo(2);

      streams.poll();
      // 멈춘 연결이 쓰기 스레드 하나를 붙잡고 있어도 다른 연결은 바로 받는다
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!okBytes.toString(StandardCharsets.UTF_8).contains("event: ETA_UPDATED")
          && System.nanoTime() < until) {
        Thread.sleep(20);
      }
      assertThat(okBytes.toString(StandardCharsets.UTF_8)).contains("event: ETA_UPDATED");

      // 쓰기 시간 제한이 지나면 다음 회차에서 멈춘 연결을 닫는다
      Thread.sleep(400);
      streams.poll();
      verify(stuck.ctx(), timeout(2000)).complete();
      assertThat(streams.size()).isEqualTo(1);
    } finally {
      release.countDown();
      streams.stop();
    }
  }

  private static void await(CountDownLatch l) {
    try {
      l.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
