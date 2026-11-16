package com.pmart.delivery.execution.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.execution.infrastructure.ExecutionStore;
import com.pmart.delivery.execution.infrastructure.ExecutionStore.DeliveryRow;
import com.pmart.delivery.execution.infrastructure.OrderClient;
import com.pmart.delivery.execution.infrastructure.OrderClient.DropInfo;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 라이더용 전달지(클라이언트 BR-24, 2026-11-14). 원문 "픽업 후: 주소·상세주소·연락" — <b>배정 라이더 본인</b>에게 <b>픽업 뒤 ~ 전달 완료
 * 전</b>(PICKED_UP·ARRIVED_AT_CUSTOMER·FAILURE_REPORTED)만 연다. 연락처는 고객 전화번호 그대로(안심번호 없음 — 사용자 결정
 * 2026-11-14). 매번 order 에서 읽고 저장·로그하지 않는다.
 *
 * <p>배송 유형은 보지 않는다 — 반품 회수(RETURN)도 연다. 반품 물품이 약속한 장소에 없으면 라이더가 고객에게 연락해야 한다(사용자 2026-11-14).
 */
@Service
public class DropInfoService {

  /** 전달지를 보여 주는 상태 — 픽업 뒤, 아직 끝나지 않음(실패 등록은 확정 전이라 진행 중). */
  public static final Set<String> OPEN =
      Set.of("PICKED_UP", "ARRIVED_AT_CUSTOMER", "FAILURE_REPORTED");

  private final ExecutionStore store;
  private final OrderClient order;

  public DropInfoService(ExecutionStore store, OrderClient order) {
    this.store = store;
    this.order = order;
  }

  public DropInfo dropInfo(String deliveryId) {
    PmartPrincipal p =
        PmartPrincipal.current().orElseThrow(() -> ExecutionService.forbidden("라이더 토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.RIDER) {
      throw ExecutionService.forbidden("배정된 라이더만 전달지를 볼 수 있습니다.");
    }
    DeliveryRow d =
        store
            .delivery(deliveryId)
            .filter(x -> p.subject().equals(x.riderId()))
            .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
    if (d.recovery()) {
      // 오배송 회수(DD-2) — 원래 고객은 상품을 갖고 있지 않다. 원래 고객 주소·전화를 회수 라이더에게 열지 않는다.
      throw new PmartException(
          "NO_CUSTOMER_DROP",
          HttpStatus.CONFLICT,
          "오배송 회수 배송에는 고객 전달지가 없습니다. 회수지는 배차 조회의 recovery 를 보세요.",
          false,
          java.util.List.of());
    }
    if (!OPEN.contains(d.state())) {
      throw new PmartException(
          "DROP_INFO_CLOSED",
          HttpStatus.CONFLICT,
          "전달지는 픽업 뒤부터 전달 완료 전까지만 볼 수 있습니다(지금 " + d.state() + ").",
          false,
          java.util.List.of());
    }
    if (d.orderId() == null) {
      throw PmartException.notFound("DROP_INFO_NOT_FOUND", "주문이 없는 배송입니다.");
    }
    return order
        .dropInfo(d.orderId())
        .orElseThrow(() -> PmartException.notFound("DROP_INFO_NOT_FOUND", "주문 당시 배송지 사본이 없습니다."));
  }
}
