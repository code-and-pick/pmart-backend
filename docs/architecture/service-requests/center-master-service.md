# center-master-service 에 대한 요청

## CM-1. 내부 센터 조회 (2026-11-13, delivery DD-1 — **구현해 PR 에 넣음, platform 검토 필요**)
- **요청한 곳**: delivery-dispatch(MFC 좌표 = 라이더 직선 반경의 중심·도로 거리 출발점 — 사용자 결정 2026-11-13 반경은 직선).
- **필요한 것**: `GET /internal/centers/{centerId}`(토큰 없는 내부 호출) — 공개 `GET /centers/{centerId}`(운영자 토큰)와 같은 응답(현재 버전).
- **한 것**: delivery 영역 PC 5 가 계약 0.1.1·`InternalController.getCenterInternal`·`CenterFlowIT` 확인을 PR 에 넣었다(`pc-split.md` §1 "급하면 PR 을 열되 그 영역 담당이 리뷰"). 표·사건 변경 없음.
