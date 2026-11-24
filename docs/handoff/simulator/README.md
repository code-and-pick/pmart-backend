# handoff — 백엔드 → 시뮬레이터

시뮬레이터 저장소는 백엔드 작업에서 **읽기만** 한다. 회신·공지는 여기에 시뮬레이터별 폴더로 남긴다(사용자 2026-11-16 "mfc 랑 rider 나눠줘").
원칙(사용자 2026-11-09): 백엔드 계약이 정본, **시뮬레이터와 안 맞는 부분은 시뮬레이터가 맞춘다.** 파일 이름 `YYYY-MM-DD-simulator-<reply|notice|request>-<주제>.md`.

| 폴더 | 상대 저장소 | 상대의 요청 목록 | 백엔드 쪽 설계·결정 |
|---|---|---|---|
| [mfc/](mfc/README.md) | `../pmart-mfc-simulation`(MFC 현장 3D) | 그 저장소 `docs/backend-requests.md`(R-xx) | `docs/simulator/mfc/` |
| [rider/](rider/README.md) | `../pmart-rider-simulation`(라스트마일 — 카카오 지도 위 라이더, 2026-11-16~) | 그 저장소 `docs/backend-requests.md`(RR-xx) | `docs/simulator/rider/` |
| [cdc/](cdc/README.md) | `pmart-cdc-simulation`(칠곡 DC 출고·피킹·상차, 2026-11-24~) | 그 저장소 `docs/backend-requests.md`(R-xx) | `docs/simulator/cdc/` |

- 2026-11-16 이전 MFC 회신·공지는 이 폴더 바로 아래에 있다가 `mfc/` 로 옮겼다 — 시뮬레이터 쪽 안내 문서의 옛 경로는 `mfc/` 를 붙여 읽는다.
