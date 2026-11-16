# 알림 — 오배송 접수 건에 재발송 배송도 보입니다 (case-service 0.10.2, backoffice-bff 0.5.2)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(접수 건 — 지원센터·상담원·관리자)
상태: PR 대기(병합·배포는 PC 1). API 모양 변경 없음 — 값만 늘어남.

> 화면 용어(2026-11-16 사용자 결정): 사건(case) = "접수 건". 아래 API 이름은 백엔드 이름 그대로.

## 1. 바뀐 것
- 오배송이 확인되면 PC 5·PC 1 쪽이 같은 상품을 원래 주소로 **재발송**한다(#39~#41, 재고 없는 품목만 환불). 그 재발송 배송이 생기면 오배송 접수 건 `refs` 에 **`reshipmentDeliveryId`**(재발송 배송 번호)가 붙고, 이력에 `RESHIPMENT_CREATED`(note "재발송 배송 …")가 남는다.
- 접수 건 상태는 바뀌지 않는다 — 재발송돼도 자동으로 처리 끝나지 않는다.
- bff `GET /views/case-inbox` 의 `linked` 에 재발송 배송이 DELIVERY 로 하나 더(지금 상태). 실시간 배송 현황에서도 재발송 배송 줄에 그 접수 건이 붙는다.
- 회수 배송(`recoveryDeliveryId`, 0.8.1)과 함께 — 오배송 접수 건 하나에 원래 배송(subjectRef)·회수 배송·재발송 배송이 같이 보인다.

## 2. 화면에 바라는 것(선택)
- 참조의 `reshipmentDeliveryId` 를 "재발송 배송"으로 이름 붙이기, 이력 `RESHIPMENT_CREATED` 문구("재발송 배송 생김").
- 연결 배송 셋을 구분해 보이기 — 원래 배송 = subjectRef, 회수 = recoveryDeliveryId, 재발송 = reshipmentDeliveryId.
