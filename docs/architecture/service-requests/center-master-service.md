# center-master-service 에 대한 요청

## CM-1. 내부 센터 조회 (2026-11-13, delivery DD-1 — **구현해 PR 에 넣음, platform 검토 필요**)
- **요청한 곳**: delivery-dispatch(MFC 좌표 = 라이더 직선 반경의 중심·도로 거리 출발점 — 사용자 결정 2026-11-13 반경은 직선).
- **필요한 것**: `GET /internal/centers/{centerId}`(토큰 없는 내부 호출) — 공개 `GET /centers/{centerId}`(운영자 토큰)와 같은 응답(현재 버전).
- **한 것**: delivery 영역 PC 5 가 계약 0.1.1·`InternalController.getCenterInternal`·`CenterFlowIT` 확인을 PR 에 넣었다(`pc-split.md` §1 "급하면 PR 을 열되 그 영역 담당이 리뷰"). 표·사건 변경 없음.

## CM-2. 지점 전화번호 (2026-11-17, 보낸 영역 delivery — 라이더 앱 "가게 전화")
- 라이더 앱 제안·수행 화면에 "가게(지점) 전화" 버튼이 있는데(라이더 앱 요청 §2), center-master 에 전화번호 칸이 없어 목으로만 동작한다. dispatch 주문 요약(`mfcName`·`mfcAddress`)과 라이더 픽업지 목록(`GET /mfc-pickup-points`, #70)은 이름·주소·좌표만 준다.
- 요청(PC 1): 센터에 대표 전화번호(라이더·고객센터가 거는 번호 — 개인 번호 아님) 칸, 내부 센터 조회(CM-1)·픽업지 목록에 싣기. 들어오면 PC 5 가 dispatch 주문 요약·라이더 앱 회신에 붙인다.
- **CM-2 정정(2026-11-17 PC 5)**: 잘못된 요청이었다 — center-master 에는 이미 지점 전화(`profile.phone` — 승인 반영 BO-06 UPDATE_PROFILE)가 있고 픽업지 목록·내부 센터 조회가 준다(#70). 빈 곳은 delivery 쪽(주문 요약·배송 조회에 안 실음)이라 PC 5 가 고침(브랜치 `delivery/mfc-phone-in-summary`). **PC 1 할 것 없음** — 다만 LAN 센터에 전화가 등록돼 있어야 화면에 나온다.
