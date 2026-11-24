# 회신 — 재고 증감 실시간 반영: bff 중계 없음, mfc-inventory 전체 센터 스트림 하나

보낸 영역: backoffice (PC 2 백엔드)
받는 앱: backoffice (통합 백오피스 웹)
답하는 글: pmart-clients `docs/handoff/backoffice/2026-11-24-backend-live-stock-stream.md`

## 정한 것 (사용자 2026-11-24 "재고 서버가 전체 스트림을 직접 주는 1번으로")
- **backoffice-bff 는 재고 스트림을 중계하지 않는다** — `GET /views/stock-stream` 은 만들지 않는다.
- 대신 **mfc-inventory 가 한 경로로 센터 하나 또는 전체 센터를 내보내도록** PC 3 에 요청했다(요청서 `docs/architecture/service-requests/mfc-inventory-service.md` IV-7).
  - mfcId 를 주면 그 센터, 비우면 볼 수 있는 모든 센터 — "전체 센터"여도 **연결 하나**.
  - 권한은 지금 `GET /stock-summaries` 와 같다(본사 관리자 모든 센터, 센터 계정 자기 센터).
  - 이벤트 최소 `{eventId, mfcId, occurredAt}` — 화면 설계대로 받으면 그 센터 요약만 다시 읽으면 된다. SKU·수량·원인·버전, `Last-Event-ID` 는 있으면 좋다고만 적었다.
  - heartbeat 5초 안팎.
- 게이트웨이 라우트·시간 제한은 경로가 정해지면 PC 1(PC1 백엔드 회신 그대로).

## 화면이 할 것
- 머리줄 센터를 고르든 "전체 센터"든 **같은 mfc-inventory 스트림 경로**를 연다(전체면 mfcId 없이). bff 경로는 없다.
- 그 밖 동작(보일 때만 연결, 다시 연결, 끊김 표시, 60초 다시 읽기는 끊겼을 때만)은 보낸 설계 그대로.
- 경로·이벤트 이름은 PC 3 계약이 나오면 그대로 쓴다 — 그 전에는 화면 설계만.

## 상태
- 요청(PC 3). 계약이 나오면 이 영역에서 다시 알린다.
