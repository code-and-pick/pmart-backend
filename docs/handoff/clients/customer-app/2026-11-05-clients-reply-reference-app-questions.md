# 백엔드 → 클라이언트 회신: 고객앱 레퍼런스 반영(A~E) 질문 (2026-11-05)

- 원 질문:
  - pmart-clients 작업 일지 `docs/worklog/2026-11-04.md`(미커밋)의 "고객앱 레퍼런스 반영 A~E"
  - 같은 작업 마무리 보고의 "백엔드에 물어볼 것"
  - 차이 목록 `docs/apps/customer-app-gap-2026-11-04.md` §20
- 계약 반영: notification-service 0.1.1(`Destination.ref` 설명), storefront-service 0.1.1(`IN_PROGRESS_ORDER` 설명) — 이 회신과 같은 커밋.
- 구분: **답함**(계약·코드로 확정) / **계약 보강**(이번에 설명을 고침) / **구현 필요**(일정·결정 대기)
- **우선순위(백엔드 사용자 결정, 2026-11-05)**: 2(진행 중 주문 섹션)·3(내가 쓴 리뷰)·5(안 읽은 알림 개수)·6(리뷰 서비스)은 **후순위**다. 지금 백엔드가 손대지 않는다. 클라이언트도 이 기능들은 목 상태로 두고, LAN 빌드에서는 진입점을 숨기는 것을 권장한다.

## 지금 클라이언트가 할 일 (한 가지)

- **1번만 연결해 달라**: 알림 이동 규칙(`noticeRoute()`)에 **REFUND → `/claims/{ref}`**(클레임 처리 내역 화면)를 더한다. `ref` 는 claimId 다(§1).
  - 백엔드는 계약 설명에 이미 적었으므로 따로 할 일이 없다. 알림 서버를 구현할 때 REFUND 알림을 claimId 로 만든다.
  - 알림 서버(notification-service)가 아직 501 이라, 지금은 **목 데이터에서만** 확인할 수 있다.
- 나머지:
  - 2·3·5·6 은 후순위
  - 4 는 다음 order-service 작업 때 백엔드가 넓히고 알린다

| 항목 | 지금 할 일 |
|---|---|
| 1 | 클라이언트: REFUND → `/claims/{ref}` 연결(목에서 확인). 백엔드: 없음(알림 서버 구현 때 규칙대로) |
| 2·3·5·6 | 후순위 — 할 일 없음 |
| 4 | 나중에 order-service 작업 때(백엔드) |

## 0. 먼저 알릴 것 — A~E 가 쓰는 서버 중 아직 없는 것

목 모드로만 확인했다고 하셨는데, LAN 서버는 아래 경로를 **아직 구현하지 않았다**.
- 계약은 있으므로 화면 코드는 그대로 둬도 된다.
- LAN 빌드에서는 오류나 빈 화면이 보인다. 그동안 숨길지, "준비 중"으로 보일지는 클라이언트·사용자 결정이다. 백엔드 권장은 **진입점을 숨기기**다.

| 클라이언트 묶음 | 쓰는 경로 | LAN 서버 상태 |
|---|---|---|
| B 알림 | `/me/notifications/**`, `/me/notification-settings`, `/me/notification-setting-changes/**` | notification-service **전부 501**(스캐폴딩). 게이트웨이 경로는 있다 |
| D 배송현황, C 진행 중 주문 남은 시간 | `GET /deliveries/{id}/eta`, `GET /deliveries/{id}/stream` | delivery-tracking-service **전부 501**. 배송 실행·배차(슬라이스 6~)도 아직이라 배송 사실 자체가 없다 |
| D 예약 시간 변경 | `POST /orders/{id}/reservation-time-changes` | order-service **501**(S4 사가 미구현) |
| C 홈 탭 | `GET /storefront/home?tab=RANKING·NEW`, `/storefront/tabs/{tabKey}` | 서버 `HomeLayout.tabs` 는 **`[HOME]` 하나**. 다른 탭은 404 `TAB_NOT_CONFIGURED` — 칩이 2개 이상일 때만 보이게 했으므로 LAN 에서는 안 보이는 게 맞다 |
| E 리뷰 | `/me/review-eligibilities`, `/reviews`, `/products/{id}/reviews` | review-service **없음**(서비스 지도상 2단계). 게이트웨이 경로도 없다 |

- 동작하는 것:
  - A 장바구니·주문: cart, order, `GET /payments/{orderId}`, `GET /orders?status=`
  - C 의 나머지: 배송 방식 카드(`GET /delivery-options`), 섹션 전체보기(`/storefront/sections/{id}/items`), 상품 카드 `+`(product-content)
  - 진행 중 주문 카드의 주문 조회(order)
- 이 경로들도 LAN 에서 실제로 확인해 주기를 부탁한다.

## 1. 환불 알림의 이동 대상 — 답함·계약 보강

- **`Destination.type = REFUND` 의 `ref` 는 `claimId` 다.**
- 환불은 클레임 아래에서 조회한다: claim-service `GET /claims/{claimId}/refunds`.
- 출고 전 주문 취소의 환불도 고객의 취소 요청(`POST /claims`)으로 만든 클레임에 붙는다.
- 그래서 REFUND 알림은 **클레임 처리 내역 화면(`/claims/{ref}`)** 으로 보내면 된다.
- 계약 `Destination.ref` 설명에 type 별 식별자를 적었다: ORDER = orderId, DELIVERY = deliveryId, CLAIM = claimId, REFUND = claimId.
- 단, 알림 서버는 아직 501 이다(§0). 이 규칙은 구현할 때 지킨다.

## 2. 홈 섹션 `IN_PROGRESS_ORDER` 의 뜻 — 답함·계약 보강

- **지금 storefront 는 이 영역을 만들지 않는다.**
- 홈 진열 항목은 상품(`DisplayItem`)이다. 진행 중 주문은 주문 정보라 진열에 맞지 않는다.
- 클라이언트가 이번에 한 방식(order `GET /orders` + delivery-tracking ETA 로 카드 조합)이 맞다.
- 서버가 이 영역을 만들게 되면 항목 모양(상품이 아닌 주문 요약)을 먼저 계약으로 정하고 알린다.
- `IN_PROGRESS_ORDER` 영역이 와도 지금처럼 상품 진열로 그리면 된다(실제로 오지 않는다).

## 3. "내가 쓴 리뷰" 목록 경로 — 구현 필요

- 계약 `review-service.yaml` 에 **본인 리뷰 목록 경로가 없다.** 있는 것은 자격 목록·작성·단건 조회·상품별 목록뿐이다.
- review-service 를 구현할 때 `GET /me/reviews`(커서, 최근 작성 순)를 계약에 넣는 것으로 하겠다. 넣으면 다시 알린다.
- 그 전까지 지금 방식(자격 목록 → 작성된 리뷰마다 단건 조회)은 목에서만 의미가 있다.
- 리뷰 서버 자체가 없다(§0, §6).

## 4. 주문을 여러 상태로 한 번에 조회 — 구현 필요(작음, 진행 여부 확인 뒤)

- 무슨 문제인가:
  - 홈 "진행 중 주문" 카드는 아직 끝나지 않은 주문을 찾아야 한다. 대상은 CONFIRMED·PREPARING·PARTIALLY_HANDED_OVER·IN_DELIVERY·PARTIALLY_DELIVERED 중 하나다.
  - 그런데 `GET /orders` 는 상태를 하나만 받는다. 그래서 앱이 홈을 열 때마다 상태별로 5번 따로 묻는다.
  - 남은 시간 조회 등을 더하면 회원 홈 한 번에 약 8번이다.
- 결과는 지금도 맞다. 기능 문제가 아니라 요청 수 문제라 **급하지 않다**(지금 사용자 수에서는 체감 없음).
- 지금 `GET /orders` 의 `status` 는 하나만 받는다.
- 하위 호환으로 **반복 파라미터**(`status=CONFIRMED&status=PREPARING&…`, 상태끼리 OR)로 넓힐 수 있다.
  - 하나만 보내는 지금 호출은 그대로 동작한다.
  - 회원 홈의 진행 중 주문 조회 약 8번이 1번이 된다.
- order-service 계약·구현·시험이 필요한 작은 변경이다. 급하지 않으므로 **다른 order-service 작업 때 같이 넣는 것**으로 둔다. 하면 계약 버전과 함께 알린다.
- 그 전까지는 지금처럼 상태별 동시 조회를 써 달라.

## 5. 안 읽은 알림 개수 — 답함

- **이미 계약에 있다.** `GET /me/notifications` 응답의 `unreadCount`.
- 홈 종 아이콘 배지는 `GET /me/notifications?size=1` 로 개수만 받으면 된다. `unreadOnly` 와 관계없이 전체 안 읽은 수다.
- 읽음 처리 뒤에는 다시 부른다.
- 단, 알림 서버는 아직 501 이다(§0).

## 6. 리뷰 서비스 — 구현 필요(2단계)

- review-service 는 서비스 지도상 **2단계**다. 지금 1단계 핵심 판매 루프와 출고(슬라이스 6, 결정 대기)가 먼저다.
- 코드·게이트웨이 경로 모두 없다.
- 일정이 정해지면 계약 보강(§3 `GET /me/reviews`)과 함께 알린다.
- 그동안 LAN 빌드에서는 리뷰 진입점(상품 상세 리뷰 영역, 주문 상세 "리뷰 쓰기", 마이 "리뷰 관리")을 숨기기를 권장한다.

## 요약

| # | 질문 | 구분 | 답 |
|---|---|---|---|
| 1 | 환불 알림 이동 대상 | 답함·계약 보강 | `ref` = claimId → `/claims/{ref}` |
| 2 | `IN_PROGRESS_ORDER` 섹션 | 답함·계약 보강 · **후순위** | 서버가 만들지 않음. 클라이언트 조합 방식이 맞음 |
| 3 | 내가 쓴 리뷰 목록 | 구현 필요 · **후순위** | review-service 구현 때 `GET /me/reviews` 추가 |
| 4 | 여러 상태 주문 조회 | 구현 필요(작음, 급하지 않음) | `status` 반복 파라미터(OR)로 넓힘 — 다른 order-service 작업 때 같이 |
| 5 | 안 읽은 알림 개수 | 답함 · **후순위** | 이미 `unreadCount` 있음(알림 서버 구현이 후순위) |
| 6 | 리뷰 서비스 | 구현 필요(2단계) · **후순위** | 없음 — LAN 에서 진입점 숨김 권장 |
| 0 | A~E 가 쓰는 서버 | 알림 | 알림·배송 추적·예약 변경 501, 탭은 HOME 만, 리뷰 없음 |
