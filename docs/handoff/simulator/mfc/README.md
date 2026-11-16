# handoff — 백엔드 → MFC 시뮬레이터 (`mfc/`)

- 상대: `../pmart-mfc-simulation`(MFC 현장을 화면으로 보여 주는 3D 시뮬레이터). 백엔드 작업에서는 그 저장소를 **읽기만** 한다.
- 시뮬레이터 → 백엔드 요청: 그 저장소 `docs/backend-requests.md`(R-01~). 회신은 여기에 R 번호를 그대로 써서 남긴다.
- 원칙(사용자 2026-11-09): 백엔드 계약이 정본, **시뮬레이터와 안 맞는 부분은 시뮬레이터가 맞춘다.** 백엔드가 시뮬레이터에 영향 주는 변경을 하면 여기에 공지한다.
- 2026-11-16 상위 `docs/handoff/simulator/` 에서 이 폴더로 옮김(라이더 시뮬레이터는 `../rider/`).
- 연결 설계·결정(SIM-1~): `docs/simulator/mfc/mfc-simulator-integration.md`.
- 파일 이름 `YYYY-MM-DD-simulator-<reply|notice|request>-<주제>.md`.
- 시뮬레이터 저장소에 쓰는 것은 **사용자가 요청할 때만**, 그 저장소의 진행 중 작업(작업 폴더·브랜치)은 건드리지 않고 따로 체크아웃한 `main` 에 문서만. 2026-11-09 사용자 요청으로 `pmart-mfc-simulation/docs/backend-handoff.md`(이 폴더·결정 문서·도면 위치 안내와 요약)를 넣었다(커밋 32bfe84).
- 2026-11-16 회신: [R-14 토큰 발급 경로 — 계속 사용](2026-11-16-simulator-reply-token-r14.md)
- 2026-11-16 공지(mfc, PC 3): [11-09 이후 바뀐 것 + R-05·R-08·R-09·R-11 다시 점검](2026-11-16-simulator-notice-recheck-and-changes.md) — R-13 이후는 요청서가 GitHub 에 올라온 뒤
