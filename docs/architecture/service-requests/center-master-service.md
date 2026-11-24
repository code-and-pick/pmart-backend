# center-master-service 에 대한 요청

## CM-1. 내부 센터 조회 (2026-11-13, delivery DD-1 — **구현해 PR 에 넣음, platform 검토 필요**)
- **요청한 곳**: delivery-dispatch(MFC 좌표 = 라이더 직선 반경의 중심·도로 거리 출발점 — 사용자 결정 2026-11-13 반경은 직선).
- **필요한 것**: `GET /internal/centers/{centerId}`(토큰 없는 내부 호출) — 공개 `GET /centers/{centerId}`(운영자 토큰)와 같은 응답(현재 버전).
- **한 것**: delivery 영역 PC 5 가 계약 0.1.1·`InternalController.getCenterInternal`·`CenterFlowIT` 확인을 PR 에 넣었다(`pc-split.md` §1 "급하면 PR 을 열되 그 영역 담당이 리뷰"). 표·사건 변경 없음.

## CM-2. 지점 전화번호 (2026-11-17, 보낸 영역 delivery — 라이더 앱 "가게 전화")
- 라이더 앱 제안·수행 화면에 "가게(지점) 전화" 버튼이 있는데(라이더 앱 요청 §2), center-master 에 전화번호 칸이 없어 목으로만 동작한다. dispatch 주문 요약(`mfcName`·`mfcAddress`)과 라이더 픽업지 목록(`GET /mfc-pickup-points`, #70)은 이름·주소·좌표만 준다.
- 요청(PC 1): 센터에 대표 전화번호(라이더·고객센터가 거는 번호 — 개인 번호 아님) 칸, 내부 센터 조회(CM-1)·픽업지 목록에 싣기. 들어오면 PC 5 가 dispatch 주문 요약·라이더 앱 회신에 붙인다.
- **CM-2 정정(2026-11-17 PC 5)**: 잘못된 요청이었다 — center-master 에는 이미 지점 전화(`profile.phone` — 승인 반영 BO-06 UPDATE_PROFILE)가 있고 픽업지 목록·내부 센터 조회가 준다(#70). 빈 곳은 delivery 쪽(주문 요약·배송 조회에 안 실음)이라 PC 5 가 고침(브랜치 `delivery/mfc-phone-in-summary`). **PC 1 할 것 없음** — 다만 LAN 센터에 전화가 등록돼 있어야 화면에 나온다.

## CM-3. 칠곡 DC 센터 등록 `CDC01` (2026-11-24, 보낸 영역 cdc — CDC 시뮬레이터 R-01)
- **요청한 곳**: CDC 시뮬레이터(`pmart-cdc-simulation` R-01)·cdc-location(위치 코드 첫 구간 = centerId).
- **필요한 것**: center-master 에 CDC 센터 하나 등록(LAN·local 시드) — `centerId` **`CDC01`**, type `CDC`, 이름 "칠곡 중앙물류센터(칠곡 DC)", 주소 "경상북도 칠곡군 지천면 산단터널로 240", 사용 층 5~7(`floors` 는 3), 취급 온도대 상온·냉장·냉동. 면적 8,600평·보관 520만 유닛은 center-master 에 칸이 없으면 넣지 않는다(시뮬레이터 표시값). 좌표는 등록 때 주소로 찾는다.
- **지금(임시 방식)**: 저장소 어디에도 CDC 센터가 없다. 시뮬레이터는 `CDC01` 을 상수로 쓴다. cdc-inventory 는 centerId 를 검증하지 않는다.
- **요청한 쪽에서 바꿀 것**: 없음. 등록되면 cdc 쪽에서 센터 조회로 바꿀 곳을 찾는다.
- **사용자 결정 필요**: 없음(코드·고정값은 사용자 2026-11-24 결정 — `docs/simulator/cdc/chilgok-dc.md` CDC-1·§1). 등록은 BO-06 승인 흐름이면 승인자 확인 필요.
