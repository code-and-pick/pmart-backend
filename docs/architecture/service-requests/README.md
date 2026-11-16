# 서비스 간 요청 모음

한 서비스가 다른 서비스에 **아직 없는 것**(API·사건 발행·사건 구독·화면)을 필요로 할 때 여기에 적는다. **받는(구현해야 할) 서비스마다 파일 하나**. 그 서비스를 구현·수정하기 전에 해당 파일을 먼저 읽는다.

**배송 백엔드를 시작한다면 먼저** [delivery-kickoff-from-mfc.md](delivery-kickoff-from-mfc.md)(2026-11-10) — MFC 가 주는 것·받으려는 것·결정된 규칙·시작 전 결정할 것을 한 장에.

## 쓰는 법
- 파일: `<받는 서비스>.md`. 여러 서비스가 같은 일을 해야 하면 주제 파일 하나(예: `approval-receipt-publishers.md`)에 받는 서비스를 나열한다.
- 항목마다: 번호 · **요청한 곳** · **필요한 것**(API·사건 이름까지) · **지금(임시 방식)** · **요청한 쪽에서 바꿀 것** · **사용자 결정 필요 여부** · 근거(파일 경로).
- 문서에 없는 업무 규칙은 적지 않는다 — "사용자 결정 필요" 로 남긴다.
- 끝나면 지우지 말고 아래 표의 상태를 `완료(커밋)` 로 바꾸고, 항목 제목에 완료 날짜를 붙인다. 필요 없어지면 `취소(사유)`.
- 클라이언트(pmart-clients)와의 요청은 여기가 아니라 `docs/handoff/clients/`.

## 전체 목록
| 번호 | 받는 서비스 | 요청한 곳 | 내용 | 사용자 결정 | 상태 |
|---|---|---|---|---|---|
| [DD-1](delivery-dispatch-service.md) | delivery-dispatch | mfc-fulfillment, orchestration | `DispatchRequested` 받아 배송 생성·라이더 제안, 취소·조정 | 없음 | **구현·LAN 배포**(2026-11-14, PR #8 1fa5b32) |
| [DD-2](delivery-dispatch-service.md) | delivery-dispatch | 사용자 | 확인된 오배송 회수 라이더 자동 배차(인근 다른 라이더) | ADR-0052, 2026-11-15 오배송 확인은 상담원·지원센터가 기록(case R-7), 2026-11-16 회수 수행료·회수지·실패·반납(사용자 "추천대로") | **구현(PC 5, 2026-11-16 — PR 대기)** — dispatch 0.4.0·execution 0.5.0·tracking 0.4.0. 남은 것 사진 보기·MI-1·O-3 |
| [DD-확인](delivery-dispatch-service.md) | delivery-dispatch | backoffice(PC 2) | 확인 요청: 시험 배송 `DLV-74422b51…` 배차 실패·미배정 — 원인(라이더 없음/다른 오류)·다시 배차할 길 | 없음(배차 실패 → 사건함은 2026-11-16 결정·구현 — case 0.5.0) | 대기(PC 5, 2026-11-16) |
| [DE-1](delivery-execution-service.md) | delivery-execution | mfc-fulfillment | 픽업 완료 = 포장별 인계 확정, 모든 `PackageHandedOver` 뒤 PICKED_UP | 없음 | **구현·LAN 배포**(2026-11-14, PR #8) |
| [TR-1](delivery-tracking-service.md) | delivery-tracking | mfc-fulfillment, order | `DeliveryEtaUpdated` (라이더 ETA) | 없음 | 대기 |
| [TR-2](delivery-tracking-service.md) | delivery-tracking | dispatch·case | 위치 끊김(배달 중 3분)·위치 보존 30일 | 결정됨(2026-11-14; 2026-11-15 받는 쪽은 지원센터 알림만 — 자동 배정 해제 없음) | 구현·LAN 배포(#15, 2026-11-15) — 받는 쪽 case R-6 대기, dispatch 는 구독 안 함 |
| [RS-1](rider-service.md) | rider | delivery-dispatch | 라이더 단계 사건(제한·중지·종료) | 결정됨(2026-11-14: 모든 단계 픽업 전 배정 재배차·픽업분은 완료, 승인 화면 전 운영자 API; 2026-11-15 정지·계약 종료는 진행 중 배송이 끝난 뒤 로그인 끊기) | 구현·LAN 배포(#13, 2026-11-15) — 로그인 끊기는 rider-auth(PC 5 PR 대기) |
| [RS-3](rider-service.md) | rider | case·백오피스 | 컴플레인 누적 → 제한 검토 대상 자동 등록 | 결정됨(2026-11-14: 같은 종류(신고·오배송·파손) 7일 안 3건 이상, 운영자 확인) | 구현(rider — PR 대기), case 발행 R-5 대기 |
| [RS-2](rider-service.md) | rider | rider-auth | 라이더 토큰(`sub`=riderId)으로 가입 — 전화번호·지역·수단·약관 → 즉시 운행 승인(ADR-0054 G) | 결정됨(2026-11-10: 전화번호 중복 확인만, 면허·주민번호 받지 않음) | 대기 |
| [RS-확인](rider-service.md) | rider | backoffice(PC 2) | 확인 요청: LAN 라이더 찾기(`GET /riders`)가 0명 — 가입 라이더가 없는지, 목록이 비는 다른 이유인지 | 없음 | 대기(PC 5, 2026-11-16) — 급하지 않음 |
| [CP-1](delivery-capacity-service.md) | delivery-capacity | mfc-capacity | `CapacityReduced` 구독 → 영향 주문 처리 | 결정됨(2026-11-15: 주문 유지 + 지연 가능 안내, 고객이 원하면 시간대 변경·취소) | 대기(PC 5) |
| [O-1](order-service.md) | order | mfc-fulfillment | `PackageHandedOver` → 인계 수량·주문 상태 | 없음 | 대기 |
| [O-2](order-service.md) | order(·orchestration) | 사용자 | 고객 자율 취소는 상품준비 전까지 | ADR-0052 | 대기 |
| [O-3](order-service.md) | order(·orchestration, mfc) | 사용자 | 확인된 오배송 새 발송(원주소, 비용 0원) | ADR-0052 | 대기 |
| [F-1](mfc-fulfillment-service.md) | mfc-fulfillment | mfc-capacity | 운영 제한 사건 구독 → `OPERATION_RESTRICTED` 거절 | 진행 중 작업 처리 | 대기 |
| [BOD-1~4](delivery-backoffice-staff-apis.md) | rider·delivery-execution·delivery-dispatch | platform(백오피스) | 백오피스 고객센터·라이더 운영 직원 API — RIDER_OPS 권한, CS_AGENT 배차 조회, 라이더 목록·검색, 전달 실패·도움요청 대기 목록 | 결정됨(2026-11-15, 라이더 이름 검색 없음 — 사용자) | 구현(PC 5 `delivery/backoffice-staff-apis`, 병합 대기) — BOD-5 는 execution, 회신 절 |
| [MB-1~3](mfc-backoffice-staff-apis.md) | mfc-capacity·fulfillment·inbound·inventory·quality | platform(MFC 백오피스) | 센터 관리자 웹 목록 API·계약만(501)인 것·권한(확인 필요 포장, 정정·위치 역할) | MB-3 은 PC 3 가 정함(사용자 2026-11-15) | 대기(PC 3) |
| [CL-1](claim-service.md) | claim | mfc-inbound, mfc-quality | 반품 클레임·회수비·검수 뒤 환불 | **필요**(감액·면제 사유) | 대기 |
| [R-1](case-service.md) | case | orchestration | 결품 상신 | — | 취소(ADR-0049) |
| [R-2](case-service.md) | case | claim | 진행 중 주문 고객센터 취소 | ADR-0060 | **거둠**(2026-11-13) |
| [R-3](case-service.md) | case | orchestration, payment | 사가·결제 상신(`ReconciliationRequired` 등) 구독 | **필요** | 대기 |
| [R-4](case-service.md) | case | delivery-execution | 라이더 도움요청·전달 실패 등록(`DeliveryIncidentRaised`) → 지원센터 사건, `CaseOpened` 로 caseId 회신 | — (사용자 결정 1번, 2026-11-13) | **구현(2026-11-16, case 0.4.0 — PC 1)** — 사건 RIDER_HELP·DELIVERY_INCIDENT + `CaseOpened`(refs incidentId). execution 이 `CaseOpened`·`CaseClosed` 를 받아 도움요청에 caseId 를 붙이는 일은 delivery(PC 5) |
| [R-5](case-service.md) | case | rider | 라이더 컴플레인(신고·오배송·파손) 기록 → `RiderComplaintRecorded` 발행 | 결정됨(2026-11-14: 같은 종류 7일 안 3건 이상 → 검토 대상; 2026-11-15 상담원 백오피스 기록) | 구현(2026-11-15, case 0.3.0 — LAN 배포 전) — 상담원 기록만, 고객 앱 신고는 아직 |
| [R-6](case-service.md) | case | delivery-tracking | 위치 끊긴 라이더(`RiderLocationStale`) → 지원센터 알림 | 결정됨(2026-11-15: 알림만, 자동 배정 해제 없음) | **구현(2026-11-16, case 0.4.0)** — 사건 RIDER_LOCATION_STALE(같은 라이더·배송이면 재발 이력만) |
| [R-7](case-service.md) | case | delivery-dispatch | 오배송 확인 기록 → 회수 배차 사건(DD-2 앞단) | 결정됨(2026-11-15: 상담원·지원센터가 확인해 기록) | **구현(2026-11-16, case 0.4.0)** — `POST /cases`(kind MISDELIVERY) + 사건 `MisdeliveryConfirmed`(platform.case.v1). 구독·회수 배차는 delivery(PC 5, DD-2) |
| [N-1](notification-service.md) | notification | order, mfc | 결품 부분 취소·환불 고객 안내 | **필요**(보류 단계 안내) | 대기 |
| [N-2](notification-service.md) | notification(·case) | case | 고객센터 문의 답변 알림(`InquiryAnswered`) | C4 | 대기 |
| [AP-1](approval-service.md) | approval | mfc-inventory, mfc-quality, mfc-capacity | MFC 승인 번호 실제 검증(지금 RECORD_ONLY) | **필요**(Q-11) | 대기 — 운영 전 필수 |
| [AP-2](approval-service.md) | approval | hr, employee-auth (ADR-0056) | 직원 권한 요청 유형(BO-01)·등록 때 자동 제출·자동 승인 규칙(소속 센터 `MFC_WORKER`)·반영 대상 employee-auth | 결정됨(2026-11-12) | 구현(2026-11-13 5-4, LAN 배포 전) — 내부 API 대신 사건 GrantRequested |
| [EA-1](employee-auth-service.md) | employee-auth | mfc-capacity | 권한 조기 회수·종료 사건(기간 전 회수 때 MFC 작업자 정지) | 없음 | 대기 |
| [EA-2](employee-auth-service.md) | employee-auth | 클라이언트 hr-backoffice (BR-57) | 직원 역할 목록 API `GET /employee-auth/roles`(코드·이름·센터 지정·순서) | 없음(사용자 2026-11-15 "만들거야") | 구현(2026-11-15, employee-auth 0.5.0 — LAN 배포 전) |
| [RC-1](approval-receipt-publishers.md) | product, product-content, center-master, delivery-serviceability, mfc-inbound | approval | `ApplicationReceiptRecorded` 발행 | 없음 | 대기 |
| [BO-12](backoffice-bff.md) | backoffice-bff | orchestration, mfc-fulfillment | 운영자 수동 개입 화면(사가·인계·중지 재시도) | **필요** | 대기 |
| [CU-1](customer-service.md) | customer | auth | 약관 동의 → `require-consent` 켜기 | **필요**(시점) | 대기 |
| [SC-1](supply-planning.md) | replenishment, demand-planning(없음) | mfc-inbound, mfc-inventory, mfc-capacity | 예상량 기반 공급 요청 | **필요**(Q-15) | 보류 |
| [CO-1](cdc-outbound-service.md) | cdc-outbound | mfc-inbound | 송장(TRF) 라벨 스캔 값 `TRF:{trfId}:{version}`(클라이언트 BR-53 R10) | 없음 | 대기 |
| [MI-1](mfc-inbound-service.md) | mfc-inbound | delivery-execution | 오배송 회수품 MFC 반납 받기(`ReturnDelivered` purpose MISDELIVERY_RECOVERY) — 점원 수령 확인·검수(신선 폐기·상온 미개봉 재고 복구) | ADR-0052 결정 4(검수 규칙), 2026-11-16 그 전까지 MFC 도착 = 반납 | 대기(PC 3) |
| [CM-1](center-master-service.md) | center-master | delivery-dispatch | 내부 센터 조회(MFC 좌표) | 없음 | **구현·LAN 배포**(2026-11-14, PR #8) |
| [O-4](order-service.md) | order | delivery-dispatch | 배차용 배송지 조회 | 없음 | **구현·LAN 배포**(2026-11-14, PR #8) — 도로명주소 null 고침도 배포(#15, 2026-11-15) |
| [O-5](order-service.md) | order | delivery-execution | 라이더용 전달지(BR-24 — 고객 전화번호 그대로) | 결정됨(2026-11-14: 안심번호 대신 고객 전화번호) | 구현·LAN 배포(#15, 2026-11-15) |
| [O-6](order-service.md) | order | 백오피스(고객센터·라이더 운영) | 직원용 주문 검색·상세(연락처 완전 일치, 목록 가림) | 결정됨(2026-11-15: order-service 에 둔다) | 구현(커밋만, 미배포) |
| [F-2](mfc-fulfillment-service.md) | mfc-fulfillment | delivery-dispatch | 피킹 시작 전 포장 소요시간 입력 → 배차 요청(사용자 확인 "피킹 시작 전") | 결정됨(2026-11-13) | 대기(mfc, PC 3) |

## 참고 (요청 아님 — 2026-11-09 조사)
- 서비스끼리의 직접 호출(RestClient)은 모두 구현된 엔드포인트를 부른다. 남은 공백은 전부 **사건**(발행자 또는 구독자 없음) 쪽이다.
- 엔드포인트 전부 501: case, delivery-dispatch, delivery-execution, delivery-tracking, notification, rider, audit, fulfillment-routing.
- 오래된 문구(정리 대상, 기능 문제 아님): cart `CartService` "재고 서비스 없음", storefront "MFC 취급 사건 없음"(이제 `SkuHandlingChanged` 있음 — 구독만 붙이면 됨), orchestration `ShortageSagaService` "case 미구현", fulfillment `WorkOrderService` "운영 제한 원천 없음"(F-1).
- audit `AuditEntryRecorded` 는 발행자가 없지만 요청한 곳도 없어 목록에 넣지 않았다.
