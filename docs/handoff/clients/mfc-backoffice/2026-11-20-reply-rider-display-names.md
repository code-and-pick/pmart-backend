# 회신 — 라이더 화면 표기(가린 이름 · 라이더 번호)를 MFC 직원에게 (RS-MFC)
보낸 영역: delivery (PC 5)
받는 앱: mfc-backoffice (PC 3), mfc-app 도 같이 쓸 수 있음
원 요청: `docs/architecture/service-requests/rider-service.md` RS-MFC, pmart-clients `docs/handoff/mfc-backoffice/2026-11-19-backend-display-labels.md`

## 구현 (rider-service 0.18.0)
- `GET /api/v1/riders/display-names?riderIds=RD-a,RD-b`(쉼표, 50개까지, 비우면 400)
- 권한: OPERATOR_ADMIN·RIDER_OPS·CS_AGENT·**MFC_SUPERVISOR·MFC_WORKER**. 라이더·고객 403.
- 응답: `{"items":[{"riderId":"RD-…","riderNumber":"482913","nameMasked":"김*수"}]}`
  - 없는 ID 는 빠진다(순서 보장 없음 — riderId 로 맞춘다).
  - 탈퇴한 라이더는 `nameMasked` null — 화면은 "라이더 · 482913" 처럼.
  - 전화·상태·제한 등 다른 칸은 없다.
- 라이더 찾기·상세(`GET /riders`, `/riders/{id}`)는 지금처럼 센터 직원에게 403.
- 화면 표기: `김*수 · 482913`(ADR-0070).
