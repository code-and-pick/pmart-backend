# handoff — 백엔드 → CDC 시뮬레이터 (`cdc/`)

- 상대: `pmart-cdc-simulation`(칠곡 DC 출고·피킹·상차 시뮬레이터, 2026-11-24~). 백엔드 작업에서는 그 저장소를 **읽기만** 한다.
- 시뮬레이터 → 백엔드 요청: 그 저장소 `docs/backend-requests.md`(R-01~). 회신은 여기에 R 번호를 그대로 써서 남긴다.
- 원칙(사용자 2026-11-09): 백엔드 계약이 정본, **시뮬레이터와 안 맞는 부분은 시뮬레이터가 맞춘다.**
- 연결 설계·결정(CDC-1~): `docs/simulator/cdc/chilgok-dc.md`.
- 파일 이름 `YYYY-MM-DD-simulator-<reply|notice|request>-<주제>.md`.

- 2026-11-24 회신(cdc): [R-01~R-08 센터 코드·위치 코드·구역 글자·비랙 위치(지하 도크·승강기)·공급 MFC·도면·CORS](2026-11-24-simulator-reply-r01-r06.md)
