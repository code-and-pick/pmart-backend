# backoffice 영역 (PC 2) — 작업 규칙

- **부모 CLAUDE.md**: 저장소 루트 `CLAUDE.md`(pmart-backend 전체 규칙 — 절대 규칙·PC 나눠 작업하기). 이 문서는 그 아래에서 **backoffice 영역(PC 2)에만** 더하는 규칙이고, 부모와 부딪히면 부모가 이긴다.
- **자식 CLAUDE.md**: `services/backoffice-bff/CLAUDE.md`, `services/case-service/CLAUDE.md`(서비스 안 규칙·버전별 상태).
- **지금 상태·결정 기록**은 여기 말고 `docs/backoffice/HANDOVER.md`(맡는 것·정해진 것·할 일). 이 문서는 "어떻게 일하는가"만 둔다.
- 2026-11-20 PC 2 백엔드 세션이 그동안의 작업(bff 0.13.0~0.22.0, case 0.20.0~0.29.0, 웹 앱 바꾸기 버튼 등)을 바탕으로 작성.

## 1. 세션과 역할
| 세션 | 하는 일 |
|---|---|
| **백엔드 백오피스**(이 문서를 읽는 PC 2 세션) | case-service, backoffice-bff, 서비스 요청·handoff 문서 |
| 프론트 백오피스(같은 PC, pmart-clients) | `web/backoffice`·`web/hr-backoffice`·웹 공통. 요청은 handoff + 세션 메시지 |
| PC 1 작업 세션 | 플랫폼 서비스 구현(customer·claim·product·product-content·storefront·search·인증·HR…) |
| PC 1 "PR 처리용" | 검토·병합·**배포만** |
| PC 3(mfc)·PC 5(delivery) | MFC·배송 서비스 — 그쪽 업무 규칙은 그쪽에 묻는다 |

- 다른 PC 에 재촉하지 않는다 — 요청을 보냈으면 기다리고, 사용자가 "깃 풀"하면 main 에 들어왔는지 확인한다.
- 다른 서비스에 필요한 것은 직접 만들지 않고 `docs/architecture/service-requests/<받는 서비스>.md` 에 요청 번호(예: CL-7, PD-4)로 적고 받는 PC 에 알린다. 웹에서 온 요청도 백엔드 요청서로 옮겨 적는다.
- 원천이 들어오면(병합·배포) 그 원천을 쓰는 화면 세션(프론트 백오피스)에 바로 알린다 — 경로·필드·버전을 적어서.

## 2. 하지 않는 것
- **로그인·세션·토큰 코드는 고치지 않는다(PC 1 만).** 웹 `web/core` 의 인증 파일·`src/features/session` 포함.
- **main 에 push 하지 않는다.** 포크(`whdrms971`)의 `backoffice/<주제>` 브랜치에서 PR. **배포는 PC 1 만.**
- 비밀번호·토큰을 출력하거나 커밋하지 않는다. 개인정보·사유·메모를 로그에 남기지 않는다(로그에는 예외 클래스 이름 정도만).
- MFC 업무 규칙은 추측하지 않는다. 문서에 없는 업무 규칙은 사용자·담당 PC 에 묻는다.
- README 표(`service-requests/README.md`)·PC 1 할 일 목록은 PC 1 만 고친다 → PR 본문에 "공용 표 갱신 필요".
- pmart-clients 는 기본 읽기만. 사용자가 명시적으로 허락한 작업만 하고, 그때도 프론트 세션이 쓰는 작업 트리를 건드리지 않게 **별도 worktree** 에서 한다.
- 백그라운드·포크 에이전트에 맡기지 않고 직접 한다(사용자 지시).

## 3. PR 순서 (매번)
```bash
git fetch origin && git switch main && git pull --ff-only origin main   # 먼저 최신화 (사용자 지시)
git switch -c backoffice/<주제>                                         # main 기준, PR 을 쌓지 않는다
# … 작업·시험 …
git fetch origin && git rebase origin/main
git merge-base --is-ancestor origin/main HEAD && git merge-tree --write-tree origin/main HEAD   # 충돌 없음 확인
git push fork backoffice/<주제>
"/c/Program Files/GitHub CLI/gh.exe" pr create --repo code-and-pick/pmart-backend --base main \
  --head whdrms971:backoffice/<주제> --body-file -
```
- PR 본문: 바꾼 것·실행한 시험(실제 돌린 것만, 숫자 포함)·영향 영역·공용 표 갱신 필요 여부.
- PC 1 은 cherry-pick/squash 로 넣는다 — PR 이 CLOSED 로 보여도 main 에 커밋이 있으면 들어간 것. 앞 PR 이 들어가면 뒤 PR 은 main 위로 다시 올린다.
- 같은 날 PR 여럿: 그날 일지(`docs/worklog/backoffice/YYYY-MM-DD.md`) 줄은 **한 PR 에만**.
- 계약 경로를 바꾸면 같은 PR 에서 게이트웨이 허용 목록을 다시 만든다:
  `PMART_WRITE_ALLOWLIST=1 ./gradlew :platform-services:api-gateway:test --tests '*UserTypeAllowlistFileTest'`
- 계약 버전을 올리면 그 서비스 `CLAUDE.md` 상태 줄·버전 절, 요청서 상태, 일지를 같은 PR 에서 맞춘다.

## 4. bff 화면 조회(views) 만드는 법
- 계약 먼저: `contracts/openapi/backoffice-bff.yaml` 에 경로·스키마(`x-added-in`)·버전·변경 기록 한 줄 → `openApiGenerate` → `api/ViewsController` 가 `mapper.convertValue(…, Generated.class)`.
- 원천 호출은 `infrastructure/LookupClients.get(Source, 운영자 토큰, path, uriVars, query)` — 운영자 토큰을 그대로 넘긴다. 새 원천은 `Source` + `BffProperties` 주소(기본 `http://<서비스>:<포트>`).
- 실패 규칙:
  - 주 원천의 401·403·400 은 그대로 돌려준다. 404 는 NOT_FOUND.
  - 주 원천 장애(연결 실패·5xx) → 목록 화면은 200 + 빈 items + `unavailableSources`.
  - 곁 칸(cell)이 실패하면 그 칸만 `null` + `unavailableSources` — 화면 전체를 깨지 않는다.
- 직원 이름은 `OperatorLabels.lookup(clients, token, ids)` → `OperatorLabels.of(id, map)`(HR 을 실제로 불렀을 때만 원천 목록에 넣는다). 시각·글자는 `ViewSupport.stamp`·`text`.
- 개인정보: 원천이 준 **가린 값**만 넘긴다. 원문 열람은 ADR-0062(사유 + audit 기록 뒤) — bff 가 원문을 모으지 않는다. 자유 글(제목·요약·메모)은 상세 칸에 싣지 않는다.
- ID 연결: customer `customerId` = order `customerId` = case cs-intakes `customerRef`.

## 5. 시험·확인
- `./gradlew :services:backoffice-bff:check`, `./gradlew :services:case-service:check` — 단위 + 통합 + spotless. 실패 수까지 확인하고 보고한다.
- bff 통합 시험은 WireMock: `@EnableWireMock` + `@ConfigureWireMock(name=…, baseUrlProperties="pmart.bff.<x>-base-url")`. 토큰 전달·가린 값·칸 하나 장애를 꼭 시험한다.
- 실패가 내 변경 때문인지 모르면 `git stash` 로 전후를 비교한다.
- **LAN**: 읽기(GET)는 해도 된다 — `deploy/lan/checks/backoffice_views_check.py`, 토큰은 `staff_auth.staff_token("claude-dev")[0]`(튜플). 몰아서 부르지 않는다(게이트웨이 429).
  **LAN 쓰기는 남는 기록을 먼저 알리고 사용자의 분명한 "진행해" 뒤에만.**
- Windows: Python 은 `$LOCALAPPDATA/Programs/Python/Python312/python.exe -X utf8`. YAML·코드 끼워 넣기처럼 따옴표가 복잡한 스크립트는 scratchpad 에 파일로 써서 `node file.js` 로 — bash 한 줄 `node -e` 는 깨진다. CRLF 파일은 Edit 도구로 고친다.
- pmart-clients 작업 때: worktree 에서 `npm run gen:api -w web/<앱>` 뒤 typecheck, 끝나면 `git checkout -- contracts.lock`.

## 6. 보고
- 한국어로, 짧게: 무엇이 들어왔는지(커밋·PR 번호), 무엇을 기다리는지, 지금 할 수 있는 것이 있는지.
- 메시지가 모호하면("처리했어") main 을 확인하고, 보이지 않으면 그대로 말하고 묻는다 — 추측해서 완료라고 하지 않는다.
- 화면 용어는 HANDOVER §3(접수 건·후속 처리·완료 처리·맡기·다시 처리하기).
