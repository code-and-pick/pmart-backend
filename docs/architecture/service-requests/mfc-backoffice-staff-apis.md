# MFC 백오피스(센터 관리자 웹)용 API — mfc 영역에 요청 (2026-11-15)

- 보낸 영역: platform(PC 1), 2026-11-15
- 받는 서비스: **mfc-capacity·mfc-fulfillment·mfc-inbound·mfc-inventory·mfc-quality**(mfc 영역 — PC 3)
- 왜: 사용자 2026-11-15 — MFC 백오피스를 별도 웹 앱으로("백오피스 mfc는 떼어내고"), 인계 전에 로그인과 메뉴를 먼저 만든다("api가 마련되지않더라도"). PC 1 이 로그인·서빙을 준비했고(c2e52ba1, `https://192.168.0.40:8445`), 클라이언트 요청서 `docs/handoff/clients/mfc-backoffice/2026-11-15-request-mfc-backoffice.md` §2-1 에 메뉴별 API 상태를 적었다. 아래는 그 점검에서 **없거나 계약만인 것** — 클라이언트는 이 메뉴를 "준비 중" 자리로 둔다.
- PC 1 은 mfc 서비스를 직접 고치지 않는다(같은 파일·DB 변경 번호가 PC 3 작업과 겹친다 — `pc-split.md`). 구현해 PR(브랜치 예 `mfc/backoffice-lists`), 계약 버전 올리고 게이트웨이 허용 목록 다시 생성. 경로·이름은 제안 — 관례와 다르면 관례대로 하고 PR 에 적는다.
- 권한은 기존과 같게: 조회 = 그 센터 권한(`canAccessCenter`), 관리 = `MFC_SUPERVISOR`.

## MB-1. 목록 API (단건 조회는 있는데 목록이 없음)

| 서비스 | 제안 | 화면 |
|---|---|---|
| capacity | `GET /api/v1/assignment-plans?mfcId=&date=&state=` | 배정 > 추천 계획 목록 |
| inventory | `GET /api/v1/mfcs/{mfcId}/count-sessions?state=` | 재고 > 순환 실사 세션 목록 |
| inventory | `GET /api/v1/mfcs/{mfcId}/stock` 을 `sku` 없이도(센터 전체, 페이지·sku/위치/LOT 필터) — 또는 별도 목록 경로 | 재고 > 센터 재고 |
| inbound | `GET /api/v1/supply-requests?mfcId=&state=` | 입고 > 공급 요청 목록 |
| inbound | `GET /api/v1/return-receipts?mfcId=&from=&to=` | 입고 > 회수품 반입 목록 |
| inbound | `GET /api/v1/initial-stock-loads?mfcId=&state=`(지금은 `approvalId` 필수) | 입고 > 수령 대기 |
| quality | `GET /api/v1/quality-decisions?mfcId=&state=`(해제 승인 대기), `GET /api/v1/disposal-approvals?mfcId=&state=`, `GET /api/v1/return-inspections?mfcId=&state=` | 품질 > 대기 목록들 |

- 공통: 페이지(`size`·`cursor`, `_common.yaml`), 최근 순 또는 대기 큐면 오래된 순, 목록 응답은 단건 응답의 요약.

## MB-2. 계약만 있는 것(지금 501)

- inbound `/api/v1/inbound-promises*`(입고 예정 TRF — 목록·상세·도착·수령): `InboundPromisesController` 빈 클래스. 간선(TMS·CDC) 쪽 입고 예정이 생겨야 의미가 있으니 **순서는 PC 3 판단**(지금은 자리만).
- quality `/api/v1/quality-incidents*`: `IncidentsController` 빈 클래스.

## MB-3. 권한 — **PC 3 가 정한다**(사용자 2026-11-15 "이건 pc3한테 맡기고") — 정한 내용과 이유를 PR 에 적는다

1. **확인 필요 포장 목록·확인**(`GET /packages?mfcId=&checkRequired=true`, `POST /packages/{id}/handover-checks`)이 지금 `MFC_WORKER` 만(`HandoverService`). 메뉴 정본(`backoffice-role-menus.md` §3)은 센터 관리자 메뉴로 두었다 → (a) `MFC_SUPERVISOR` 도 허용 (b) PDA(작업자)에만 두고 백오피스 메뉴에서 뺀다.
2. 위치·LOT 정정(`stock-corrections`), 상품 취급(`sku-handlings*`), 위치(칸) 등록·수정·폐지(`/locations*`)는 **역할 검사 없이 센터 권한만** — 그 센터 `MFC_WORKER` 도 부를 수 있다. 관리자 전용(`MFC_SUPERVISOR`)으로 좁힐지. (상품 취급은 MD 도 같은 API 를 쓴다 — 좁히면 MD 를 남긴다.)

## 회신 (PC 3, 2026-11-18 — 브랜치 `mfc/backoffice-lists`)
- **MB-1 구현** 9개 전부: quality `GET /quality-decisions`·`/disposal-approvals`·`/return-inspections`(0.5.0), inventory `GET /mfcs/{mfcId}/count-sessions`·**새** `GET /mfcs/{mfcId}/stock-units`(0.26.0), inbound `GET /supply-requests`·`/return-receipts`·`/initial-stock-loads?mfcId=`(0.12.0), capacity `GET /assignment-plans`(0.18.0). 기본 상태·정렬·권한은 회신 `docs/handoff/clients/mfc-backoffice/2026-11-18-reply-lists-and-roles.md`.
- **MB-3 구현**(PC 3 결정 = clients 요청서 그대로): 확인 필요 포장 목록·확인 MFC_SUPERVISOR 도(fulfillment 0.17.0), 정정·칸 변경 MFC_SUPERVISOR 만(inventory 0.26.0·location 0.8.0), 상품 취급 그 센터의 MFC_SUPERVISOR·MD(inventory 0.26.0).
- **MB-2**: 입고 예정은 나중. 품질 사고는 **사용자 결정 대기** — 직접 등록 여부·원천·상태 기준·case 연결(회신 §3 질문 1~4).
- PC 1: 게이트웨이 `/api/v1/mfcs/*/stock-units` 라우트, 허용 목록 GET 8줄. **공용 표 갱신 필요**(MB-1·MB-3 구현, MB-2 결정 대기).

## 참고(문서만)

- `AssignmentsController` javadoc 의 "계획 501" 은 낡은 문구 — 실제로 구현돼 있다.
- 공급 요청은 계약 0.9.0 `/supply-requests` 로 구현돼 있다(메뉴 문서에 경로가 빠져 있었음 — PC 1 이 고침).

## 배차 지연 목록·배차 불가 취소 (2026-11-19, 보낸 영역 delivery — 사용자 "1,2,3 전부")
- 원문 Delivery §3.6 — MFC 관리자에게 배차 지연 안내, 배차 불가면 취소(전액 환불 + 2,000원 쿠폰).
- 데이터(PC 5 구현, dispatch 0.12.0): `GET /dispatch-delays?mfcId=` — 지연 안내가 난 픽업 전 배송(deliveryId·orderId·state·riderId·promisedAt·delayAlertedAt). 그 센터 운영자(mfcId 필수)·관리자·CS·RIDER_OPS.
- 요청(PC 3 MFC 백오피스): 대시보드·메뉴에 "배차 지연" 목록(주문번호·배송 번호 — ADR-0070, 약속 시각 대비 몇 분 지났는지, 배정 여부) + 행마다 **"배차 불가로 취소"** 버튼 → claim CL-5(사유 DISPATCH_UNAVAILABLE, MFC_SUPERVISOR). 확인 창에 "주문 전체 취소 · 전액 환불 · 고객에게 2,000원 쿠폰".
