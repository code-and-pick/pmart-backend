# handoff — 다른 저장소와의 요청·회신

백엔드는 다른 저장소에 쓰지 않는다. 상대에게 할 말(회신·공지·요청)은 **상대별 폴더**에 쓰고, 상대가 보낸 것은 상대 저장소에서 읽기만 한다.

| 상대 | 백엔드 → 상대 (이 저장소) | 상대 → 백엔드 (읽기만) |
|---|---|---|
| 클라이언트 앱(`pmart-clients` — 고객 앱·PDA 앱·라이더 앱·백오피스 웹) | `docs/handoff/clients/<앱 영역>/`(2026-11-13 앱 영역 폴더) | `pmart-clients/docs/handoff/<앱 영역>/` |
| MFC 시뮬레이터(`pmart-mfc-simulation`) | `docs/handoff/simulator/` | `pmart-mfc-simulation/docs/backend-requests.md`(R-xx) |

- 2026-11-09 사용자 결정으로 폴더를 나눴다. 그 전 클라이언트 handoff 는 `docs/handoff/` 바로 아래에 있었고 `clients/` 로 옮겼다(파일 이름은 그대로).
- 파일 이름 `YYYY-MM-DD-<상대>-<종류>-<주제>.md`(종류: request·reply·notice). 규칙 세부는 각 폴더 README.
- **2026-11-14 부터 클라이언트 handoff 는 `<상대>` 자리에 받는 앱 영역**(customer-app·mfc-app·rider-app·backoffice·hr-backoffice·web-common·all)을 쓰고 첫머리에 `보낸 영역: platform|mfc|delivery` — PC 를 나눠 작업하므로(`docs/operations/pc-split.md`). 시뮬레이터 handoff 는 mfc 영역이 맡는다.
