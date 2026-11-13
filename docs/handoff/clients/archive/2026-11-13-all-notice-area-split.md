# 알림·제안: PC 를 나눠 작업하므로 일지·handoff 를 영역(클라이언트는 앱) 단위로 나눈다

- 보낸 영역: platform
- 작성: pmart-backend, 2026-11-13
- 대상: pmart-clients 전체(`all`)
- 근거: 사용자 결정 2026-11-13 — "PC 나눠서 작업하면 handoff 랑 worklog 도 꼬일 것 같은데 작업공간을 나눠서 해야 할 것 같아" → 백엔드는 영역 단위, "클라이언트 영역은 앱단위로".
- 백엔드 규칙 정본: pmart-backend `docs/operations/pc-split.md`.

## 1. 백엔드가 바꾼 것 (2026-11-14 부터)

- 백엔드 영역: **platform**(PC 1 — 인증·HR·승인·게이트웨이·배포·그 밖), **mfc**(PC 3 — mfc-*-service), **delivery**(PC 5 — delivery-*·rider-service·rider-auth-service).
- 백엔드 → 클라이언트 파일 이름: **`YYYY-MM-DD-<받는 앱 영역>-<종류>-<주제>.md`**, 첫머리 `보낸 영역: platform|mfc|delivery`. 받는 앱 영역은 아래 §2 이름(여럿이면 `all`). 2026-11-13 까지의 파일은 이름 그대로(`…-clients-…`). 이 문서가 새 이름의 첫 파일이다.
  - 그래서 클라이언트의 각 앱 담당은 `docs/handoff/clients/*-<자기 앱>-*` 와 `*-all-*` 만 보면 된다.
- 한 주제는 한 백엔드 영역이 맡는다. 인증·로그인·HR·배포·공통 계약은 platform, MFC 업무 API 는 mfc, 배송·라이더는 delivery.

## 2. 클라이언트에 제안 — 앱 단위 영역

| 영역 | 폴더 |
|---|---|
| customer-app | `apps/customer_app` |
| mfc-app | `apps/mfc_app` |
| rider-app | `apps/rider_app` |
| backoffice | `web/backoffice` |
| hr-backoffice | `web/hr-backoffice` |
| web-common | `web/core`·`web/ui`·`tools/web-tools`(두 웹앱 공통 — 한 주인) |

제안(정하는 것은 클라이언트):

1. **작업 일지**를 앱 영역별 폴더로 — 예 `docs/worklog/<앱 영역>/YYYY-MM-DD.md`. 같은 날 여러 PC 가 한 파일에 쓰면 합칠 때 충돌한다.
2. **클라이언트 → 백엔드 handoff** 파일 이름에 앱 영역 — `YYYY-MM-DD-<앱 영역>-backend-<주제>.md` 같은 식으로, 첫머리에 `보낸 앱:` 과 받는 백엔드 영역(platform·mfc·delivery)을 적어 주면 백엔드의 맞는 PC 가 읽는다.
3. 여러 앱이 고치는 공통 표·문서(`docs/backend-requests.md`, 계약 잠금 `contracts.lock` 등)는 한 곳(한 PC)이 합칠 때 몰아서 고친다.
4. 다른 PC 도 알아야 할 사실은 각 PC 의 Claude 메모가 아니라 저장소 문서에 둔다(백엔드는 `docs/operations/dev-on-other-pcs.md`·`pc-split.md`, 루트 `CLAUDE.md` 가 가리킨다).

정한 규칙(폴더·이름)을 회신해 주면 백엔드가 그대로 읽는다.
