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

  /** 회수 연락처를 여는 상태 — 회수 진행 중. */
  public static final Set<String> RECOVERY_OPEN = Set.of("ASSIGNED", "RETURN_PICKED_UP");

  /** 원래 고객 전화(회수 라이더 — 전화만). */
  public record RecoveryContact(String originDeliveryId, String receiverPhone) {
    @Override
    public String toString() {
      return "RecoveryContact[가림]";
    }
  }

  /**
   * 오배송 회수 라이더에게 원래 고객 전화번호(사용자 2026-11-16 "원래 고객의 전화는 회수 라이더에게 보여야 해"). 회수 배송에 배정된 라이더 본인만, 회수 진행
   * 중(ASSIGNED·RETURN_PICKED_UP)만. 주소·출입 정보는 주지 않는다. 매번 order 에서 읽고 저장·로그하지 않는다.
   */
  public RecoveryContact recoveryContact(String deliveryId) {
    PmartPrincipal p =
        PmartPrincipal.current().orElseThrow(() -> ExecutionService.forbidden("라이더 토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.RIDER) {
      throw ExecutionService.forbidden("배정된 회수 라이더만 볼 수 있습니다.");
    }
    DeliveryRow d =
        store
            .delivery(deliveryId)
            .filter(x -> p.subject().equals(x.riderId()))
            .orElseThrow(() -> PmartException.notFound("DELIVERY_NOT_FOUND", "배송이 없습니다."));
    if (!d.recovery()) {
      throw conflict("NOT_RECOVERY", "오배송 회수 배송이 아닙니다.");
    }
    if (!RECOVERY_OPEN.contains(d.state())) {
      throw conflict("RECOVERY_CONTACT_CLOSED", "회수 중에만 볼 수 있습니다(지금 " + d.state() + ").");
    }
    if (d.orderId() == null) {
      throw PmartException.notFound("DROP_INFO_NOT_FOUND", "주문이 없는 배송입니다.");
    }
    String phone =
        order
            .dropInfo(d.orderId())
            .map(OrderClient.DropInfo::receiverPhone)
            .orElseThrow(
                () -> PmartException.notFound("DROP_INFO_NOT_FOUND", "주문 당시 배송지 사본이 없습니다."));
    return new RecoveryContact(d.originDeliveryId(), phone);
  }

  private static PmartException conflict(String code, String message) {
    return new PmartException(code, HttpStatus.CONFLICT, message, false, java.util.List.of());
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
          "오배송 회수 배송에는 고객 전달지가 없습니다. 회수지는 배차 조회의 recovery, 원래 고객 전화는 recovery-contact 를 보세요.",
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
    DropInfo original =
        order
            .dropInfo(d.orderId())
            .orElseThrow(
                () -> PmartException.notFound("DROP_INFO_NOT_FOUND", "주문 당시 배송지 사본이 없습니다."));
    if (!d.redelivery()) {
      return original;
    }
    // 오배송 다시 배송(ADR-0065): 주소는 상담원이 기록한 실제 집, 수령인·연락처·출입·요청사항은 주문 당시 그대로, 지도 핀 없음
    String[] home =
        d.redeliveryDestinationId() == null
            ? null
            : order.redeliveryAddress(d.orderId(), d.redeliveryDestinationId()).orElse(null);
    if (home == null) {
      throw PmartException.notFound("DROP_INFO_NOT_FOUND", "다시 배송 주소가 없습니다.");
    }
    return new DropInfo(
        home[0],
        home[1] == null ? "" : home[1],
        home[2],
        original.receiverName(),
        original.receiverPhone(),
        original.entranceMethod(),
        original.entranceNote(),
        original.deliveryNote(),
        null,
        null);
  }
}
