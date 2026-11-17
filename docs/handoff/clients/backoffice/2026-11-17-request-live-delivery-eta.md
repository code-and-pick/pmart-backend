# 요청 — 실시간 배송 현황의 도착 예정 (backoffice-bff 0.7.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(실시간 배송 현황 — 상담원·지원센터·관리자·센터 관리자)
상태: bff PR 대기(병합·배포는 PC 1). tracking TR-3(ETA 조회)은 LAN 배포됨.

## 바뀐 것
`GET /api/v1/backoffice/views/live-delivery` 항목마다:
- `latestEta` — tracking 의 고객 도착 예정(dropEta.etaAt). **산출됐을 때(AVAILABLE)만** 값, 그 밖 null.
- `etaStatus` — tracking 원천 값 그대로: `AVAILABLE` · `LOCATING`(위치 확인 중) · `ETA_PENDING`(확인 중 — 미배정·길찾기 실패·계산 꺼짐) · `FINAL`(끝남). tracking 에 없는 배송(배포 전 배송)이면 null.
- `delayedBySeconds` — 약속 대비 지연(양수 = 늦음, 음수 = 이름). 없으면 null.
- `etaSource` — tracking 출처 표시. 장애·403 이면 confidence UNKNOWN 이고 `unavailableSources` 에 `delivery-tracking-service`.

## 화면 제안
- 약속 시각(`promisedAt`) 옆에 "도착 예정 HH:mm"(latestEta), `delayedBySeconds > 0` 이면 "N분 늦음" 빨강.
- etaStatus 가 LOCATING·ETA_PENDING 이면 "확인 중", null 이면 "—". 숫자를 지어내지 않는다(원문 "불명은 확인 중").
