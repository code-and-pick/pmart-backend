# 알림 — 오배송 사건에 회수 진행이 보입니다 (case-service 0.8.1, backoffice-bff 0.5.1)

> 화면 용어(2026-11-16 사용자 결정, pmart-clients PR #28): 사건(case) = **"접수 건"**(지원센터 › 접수 건), 상태 OPEN 접수됨 · CLOSED 처리 끝남 · REOPENED 다시 처리 중, 닫기 = 처리 끝내기, 재개 = 다시 처리하기, 종결 = 처리 결과, 의무 = 후속 처리. 아래 본문의 "사건"은 백엔드 이름 — 화면에서는 이 용어로.

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(사건함 — 지원센터·상담원·관리자)
상태: PR 대기(병합·배포는 PC 1). API 모양 변경 없음 — 값만 늘어남.

## 1. 바뀐 것
- 오배송 확인 사건(MISDELIVERY)에 회수 배송(PC 5 DD-2)이 생기면 사건 `refs` 에 **`recoveryDeliveryId`**(회수 배송 번호)가 붙는다.
- 사건 이력에 `RECOVERY_CREATED`(회수 배송 생김) → `RECOVERY_PICKED_UP`(회수함) → `RECOVERY_RETURNED`(매장 반납, note 에 MFC 번호).
- 사건 상태는 바뀌지 않는다 — **반납돼도 자동으로 닫히지 않는다.** 후속 처리(회수 의무)도 자동으로 해소되지 않는다. 사람이 이력을 보고 처리한다.
- bff `GET /views/case-inbox` 의 `linked` 에 회수 배송이 DELIVERY 로 하나 더 나온다(지금 상태 — 예 RETURN_IN_PROGRESS). 실시간 배송 현황에서도 회수 배송 줄에 그 사건이 붙는다.

## 2. 화면에 바라는 것(선택)
- 사건 상세의 참조에서 `recoveryDeliveryId` 를 "회수 배송"으로 이름 붙여 보여 주기, 이력 세 가지의 문구(회수 배송 생김·회수함·매장 반납).
- 사건함 줄에서 연결 배송이 둘이면(원래 배송·회수 배송) 구분해 보이기 — 원래 배송 = subjectRef, 회수 배송 = refs recoveryDeliveryId.
