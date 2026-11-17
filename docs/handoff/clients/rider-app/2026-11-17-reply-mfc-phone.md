# 회신 — 가게(지점) 전화번호

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app`(`mfc_directory.dart` 목 전화, 제안·배송 화면 "가게 전화")
- 계약: dispatch 0.5.2·execution 0.13.1 — `gen:api` 다시. 상태: 백엔드 PR

- 지점 전화는 center-master 에 이미 있다 — `GET /mfc-pickup-points` 의 `phone`(배포됨).
- 이번에 제안 `GET /offers` 의 `summary.order.mfcPhone`, 배송 조회 `GET /deliveries/{id}`·내 배달 내역의 `mfcPhone` 에도 싣는다(이 배포 뒤 배차부터).
- 지점에 전화가 등록돼 있지 않으면 null — 버튼을 숨긴다. 목 전화번호(`mfc_directory.dart`)는 쓰지 않는다.
