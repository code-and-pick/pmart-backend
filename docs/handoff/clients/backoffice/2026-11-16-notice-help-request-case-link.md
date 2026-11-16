# [pmart-backend → pmart-clients] 알림: 도움요청이 사건함 사건을 따라간다 (2026-11-16)

- 보낸 영역: delivery
- 받는 앱: backoffice (지원센터·라이더 운영 — 도움요청·사건함 화면)
- 근거: service-requests DE-CASE, 계약 `delivery-execution-service.yaml` 0.5.1

## 바뀐 것
- 라이더 도움요청으로 사건함 사건(RIDER_HELP·DELIVERY_INCIDENT)이 열리면 execution 도움요청에 `caseId` 가 붙고 상태가 IN_PROGRESS 가 된다. 사건을 닫으면 도움요청도 RESOLVED(`resolvedBy` = `case:<caseId>`, `resolutionNote` = "사건함에서 닫음(<결과>)"), 다시 열면 IN_PROGRESS.
- 사건이 붙은 도움요청은 `POST /api/v1/deliveries/{id}/help-requests/{requestId}/resolutions` 가 **409 `HELP_REQUEST_HAS_CASE`** — 사건함에서 닫아 주세요(앞서 권한 대로 도움요청 대기 메뉴는 사건함 `GET /cases?kind=RIDER_HELP` 로 대신).
- 사건이 없는 예전 도움요청은 그 경로로 그대로 닫을 수 있다.
- 라이더 앱은 바뀐 것 없음(도움요청 조회의 상태가 사건함을 따라간다).
