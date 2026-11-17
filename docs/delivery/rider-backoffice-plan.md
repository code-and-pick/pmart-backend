# 라이더 백오피스(관제 포함) 계획 — 2026-11-17

보낸 영역: platform(PC 1). 받는 영역: delivery(PC 5). 상태: **뼈대·로그인 끝(PC 1), 화면·관제는 PC 5**.

- 사용자 2026-11-17: "라이더 백오피스 새로 만들어야 할 것 같아서, 관제까지 넣을 거야. 로그인 기능 똑같이" → "관제 화면 같은 건 내 쪽에서 만들지 말고 앱 뼈대랑 로그인만" → "라이더 백오피스는 PC2에서 PC5로".
- 화면과 화면이 부르는 배송·라이더 API 가 같은 PC(PC 5)에 있다 — MFC 백오피스(PC 3)와 같은 방식.

## 1. PC 1 이 한 것 (2026-11-17)

| 무엇 | 어디 |
|---|---|
| employee-auth 앱 `rider-backoffice`(SSO 켬, 무활동 12시간, 로그아웃 뒤 `/login`, LAN refresh 쿠키 켬), 복귀 주소 개발 `http://localhost:5176`·LAN `https://192.168.0.40:8446`·`https://d603-16:8446` | pmart-config `employee-auth-service*.yml`, 시험 사본 |
| Keycloak `employee-auth-service` 로그아웃 허용 주소(5176·8446) | `deploy/lan/keycloak/pmart-staff-realm.json` + 운영 중 Keycloak 은 배포 때 관리 API 로 |
| 게이트웨이 CORS(8446·5176) | pmart-config `api-gateway-lan.yml` |
| PC 4 서빙 `rider-backoffice-web`(nginx, https 8446, http 8084 는 넘기기만), 빌드 없으면 안내 페이지 | `placement.conf`·`render.sh`·`backoffice/nginx.conf`·`rider-placeholder.html`, `watch.sh` 점검 |
| 빌드 올리기 `lan.sh publish-rider-backoffice <dist>` | `deploy/lan/lan.sh` |
| 웹 앱 뼈대 `web/rider-backoffice` — 직원 로그인·조용한 로그인 확인·세션·메뉴 자리 4개(모두 "준비 중"), 목 계정 RIDER_OPS·OPERATOR_ADMIN·CS_AGENT, 시험 11개 | pmart-clients, 앱 문서 `docs/apps/rider-backoffice-web.md` |

- **어디서 띄우나 — PC 4**(다른 백오피스 셋과 같은 곳). 11-17 확인: PC 4 CPU 20%·남은 메모리 5.9GB, nginx 하나는 수 MB 라 부담 없음. PC 4 에는 이미 HTTPS 인증서(사설 CA)·같은 nginx 설정·게이트웨이가 있어, PC 5 로 두면 인증서·CORS·복귀 주소를 따로 늘려야 하고 직원이 백오피스마다 다른 호스트를 기억해야 한다. **PC 5 는 개발(화면·API)만, 서빙은 PC 4·배포는 PC 1.**
- 배포 때 다른 컨테이너는 건드리지 않는다: `render.sh` 의 `LATE` 목록으로 자동 보조 컨테이너(log-shipper·api-docs 등 7개) **뒤** 번호(172.28.0.37)를 받는다(사용자 2026-11-17 "보조 컨테이너 안 건드리고 싶은데"). 렌더 비교로 PC 4 는 새 블록만 더해지고 PC 1·2·3·5 는 그대로임을 확인.

## 2. PC 5 가 할 것 (추천 순서)

1. **메뉴·화면 틀 정하기** — 뼈대 메뉴(관제 / 라이더 찾기 / 배송 찾기 / 접수 건)는 자리만. 이름·묶음은 바꿔도 된다(`src/app/nav.ts`, `src/shared/messages.ts`).
2. **관제 백엔드**(delivery 영역):
   - delivery-tracking-service 에 **직원용 라이더 위치 목록**(지금은 라이더 본인용·배송 한 건 ETA/stream·`/internal/rider-locations` 만 있다). 센터·권역 필터, 갱신 시각·끊김 표시. 1차는 화면이 5~10초마다 불러오기, SSE 는 다음.
   - 라이더 상태(rider-service: 운행 상태·제한)·진행 중 배송(delivery-dispatch·delivery-execution)·열린 사건(case)을 합치는 곳 — 추천은 **backoffice-bff 새 view**(가칭 `/views/rider-control`, 원천 실패 시 그 칸만 불명). bff 는 PC 2 영역이라 `docs/architecture/service-requests/backoffice-bff.md` 로 요청하거나, 화면이 원천 API 를 직접 여러 번 부르는 1차안도 가능 — PC 5 가 정해서 적는다.
   - 기존 bff `/views/trip-control`(501)은 **간선 트럭(CDC→MFC) 운행 관제**라 라이더 관제와 다르다 — 섞지 않는다.
3. **관제 화면** — 지도에 라이더(상태별 색)·MFC, 라이더를 누르면 진행 중 배송·ETA, 옆에 경고(위치 끊김 `RiderLocationStale`·배차 실패·지연·도움요청). 카카오 지도 JavaScript 키는 카카오 개발자 콘솔에 LAN 주소(`https://192.168.0.40:8446`)를 웹 도메인으로 등록해야 한다 — 키 값은 문서·커밋에 적지 않는다(PC 1 에 알리면 설정으로).
4. LAN 반영: PC 5 는 pmart-clients 에 PR 만 올린다. **빌드·올리기는 PC 1** — 병합한 main 에서 `npm run build:lan -w web/rider-backoffice` → `lan.sh publish-rider-backoffice <dist>`(다른 백오피스와 같은 방식). PC 5 는 확인용으로 로컬에서만 빌드하고, PC 4 에 직접 복사하지 않는다.
5. 확인: 라이더 시뮬레이터(pmart-rider-simulation)로 위치를 움직여 관제 화면에서 본다.

## 3. 사용자 결정 — **정함(2026-11-17 "추천대로 전부 시작해" → ADR-0068)**

> 1 옮김(같은 화면이 올라간 뒤 기본 백오피스 메뉴를 내림, 상담원 조회는 남김) · 2 RIDER_OPS·OPERATOR_ADMIN 만(센터 관리자는 다음) · 3 운행 중 라이더의 현재 위치만, 이력 없음 · 4 10초 다시 불러오기(SSE 다음).
> 백엔드: tracking `GET /rider-locations?mfcId=`(0.8.0), rider `/internal/riders/on-duty`(0.8.0), execution `/rider-earnings`·`/rider-operating-time`·배송 찾기 `orderNumber`(0.16.0). 게이트웨이 경로는 PC 1.

아래는 정하기 전 원문(기록).

1. **기존 라이더 운영 화면을 이 앱으로 옮길지**(2026-11-15 결정 "고객센터·라이더 운영 메뉴는 기본 백오피스에"를 바꾸는 일 — 바꾸면 ADR) — 라이더 찾기·상세·원문 열람·계정 제한·제한 검토, 배송 찾기·배차 조회/취소, 전달 실패 확정, 지원센터 사건함(배송 쪽), 도움요청 대화, 라이더 문의, 수행료 조정. 추천: 옮기고, 상담원(CS_AGENT)이 보는 라이더·배송 조회는 기본 백오피스에 남김.
2. 관제를 볼 사람: RIDER_OPS·OPERATOR_ADMIN(모든 센터) + MFC_SUPERVISOR 자기 센터도?
3. 라이더 위치(개인정보) 노출 범위: 추천 — 관제에선 현재 위치만, 이동 이력은 안 보임, 이력을 볼 때만 사유 + 기록(ADR-0062 방식).
4. 실시간 방식: 추천 — 1차 5~10초 불러오기, SSE 는 다음.

## 4. 지키는 것

- 로그인·세션 코드(`src/features/session`, `src/api/employeeAuth.ts`·`sessionPersistence.ts`, `src/app/SessionContext.tsx`)는 **PC 1 만**(`docs/operations/pc-split.md` §1-3).
- 웹 공통(`web/core`·`web/ui`)은 **PC 2** 가 주인 — 고칠 일은 PC 2 에 요청(네 웹 앱이 같이 쓴다).
- 권한 판정은 서버. 메뉴 숨김은 편의.
