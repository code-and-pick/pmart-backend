# [pmart-backend → pmart-clients] 회신: 배송 도착 예정·실시간 구독 (TR-3) (2026-11-17)

- 보낸 영역: delivery(PC 5)
- 받는 앱: customer_app(배송 현황) · backoffice(실시간 배송)
- 근거: `docs/architecture/service-requests/delivery-tracking-service.md` TR-3(사용자 2026-11-17 "PC 5에 요청")

## 된 것 (delivery-tracking 0.5.0 — 지금 501 이던 두 경로)
- `GET /api/v1/deliveries/{deliveryId}/eta` → `DeliveryEta`
  - `stage`: UNASSIGNED · TO_STORE · AT_STORE · TO_CUSTOMER · AT_CUSTOMER · DONE, DONE 이면 `outcome`(COMPLETED·FAILED·CANCELLED).
  - `dropEta`: `status` AVAILABLE(→ `etaAt`·`remainingSeconds`·`calculatedAt`, 예약 배송이면 `delayedBySeconds` = 시간대 끝 대비) / LOCATING(라이더 위치 확인 중) / ETA_PENDING(미배정·계산 전) / FINAL(끝).
  - `pickupEta`: **직원만**(픽업 전). 고객에게는 null.
  - `arrivalWindow`(예약 배송). 라이더 좌표는 주지 않는다.
- `GET /api/v1/deliveries/{deliveryId}/stream`(SSE, `text/event-stream`)
  - 접속하면 지금 상태 `ETA_UPDATED`, 단계가 바뀌면 `STAGE_CHANGED`, 도착 예정만 바뀌면 `ETA_UPDATED`, 15초마다 `HEARTBEAT`, 끝나면 `STREAM_END` 뒤 서버가 닫는다.
  - 30분이 지나도 닫힌다 → `Last-Event-ID` 로 다시 접속(지금 상태와 같으면 첫 이벤트 생략).
  - 각 이벤트 `data` = `StreamEvent`(type·deliveryId·sentAt·eta·stage).
- 권한: 고객은 **본인 주문의 배송만**(아니면 404). 직원은 OPERATOR_ADMIN·CS_AGENT·RIDER_OPS 모든 센터, 그 밖은 그 센터. 라이더 403.

## 알아 둘 것
- **도착 예정 수치는 서버 설정(카카오 길찾기 사용량)이 켜져야 나온다** — 그 전에는 ETA_PENDING. 앱은 지금처럼 "확인 중"을 보이면 된다.
- 이 배포 전에 만들어진 배송은 404(진행 사본이 없다). 새 주문부터 된다.
- WebSocket 은 없다(SSE 만).
