package com.pmart.riderauth.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.riderauth.infrastructure.KakaoUnlinkClient;
import com.pmart.riderauth.infrastructure.RiderAuthStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

/** 탈퇴 반영(RA-1 ①): 카카오 sub 를 지우기 전에 읽고, 연결을 지운 뒤 카카오 앱 연결을 끊는다. 다른 제공자면 부르지 않는다. */
class RiderWithdrawalsTest {

  final RiderAuthStore store = mock(RiderAuthStore.class);
  final SessionBlocks blocks = mock(SessionBlocks.class);
  final KakaoUnlinkClient kakao = mock(KakaoUnlinkClient.class);
  final RiderWithdrawals withdrawals =
      new RiderWithdrawals(
          store, blocks, Clock.fixed(Instant.parse("2026-11-22T00:00:00Z"), ZoneOffset.UTC), kakao);

  EventEnvelope withdrawn(String riderId) {
    EventEnvelope e = mock(EventEnvelope.class);
    when(e.payload())
        .thenReturn(JsonMapper.builder().build().readTree("{\"riderId\":\"" + riderId + "\"}"));
    return e;
  }

  @Test
  void unlinksKakaoAfterRemovingLinkOutsideTransaction() {
    when(store.subjects("RD-1", "KAKAO")).thenReturn(List.of("4001"));
    withdrawals.on(withdrawn("RD-1"));
    InOrder order = inOrder(store, kakao);
    order.verify(store).subjects("RD-1", "KAKAO");
    order.verify(store).disableAndUnlink("RD-1");
    order.verify(kakao).unlink("4001");
  }

  @Test
  void otherProviderDoesNotCallKakao() {
    when(store.subjects("RD-2", "KAKAO")).thenReturn(List.of());
    withdrawals.on(withdrawn("RD-2"));
    verify(store).disableAndUnlink("RD-2");
    verify(kakao, never()).unlink(anyString());
  }

  @Test
  void kakaoFailureDoesNotUndoWithdrawal() {
    when(store.subjects("RD-3", "KAKAO")).thenReturn(List.of("4003"));
    when(kakao.unlink("4003")).thenReturn(false);
    withdrawals.on(withdrawn("RD-3"));
    verify(blocks).block(any(), any(), any(), any());
    verify(store).disableAndUnlink("RD-3");
  }
}
