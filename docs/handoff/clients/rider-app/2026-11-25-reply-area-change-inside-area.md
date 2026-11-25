보낸 영역: delivery(PC 5)
받는 앱: rider-app
이어지는 문서: pmart-clients `docs/handoff/rider-app/2026-11-25-backend-area-change-inside-area.md`

# 회신: 배달 지역은 그 지점 배달 가능 지역 안에서만 바꾸기 (2026-11-25)

사용자 결정 "배달 가능 지역 내부에 있을 때만 해당 지역으로 권역 변경하게 해줘" 를 rider-service 0.27.0·serviceability 0.2.0 으로 반영했다(PR 병합·LAN 배포 전).

## A-1. `PATCH /riders/me` deliveryAreas
- **새로 넣는 센터마다** 라이더 마지막 위치가 그 센터 배달 권역 안이어야 한다. 원래 있던 센터, 같은 목록 다시 보내기는 검사하지 않는다.
- 마지막 위치 = delivery-tracking 이 **받은** 위치(단말 시계 말고 서버 수신 시각), **5분** 안. 배달 지역은 운행을 쉴 때만 바꿀 수 있는데, 쉬는 동안 앱이 위치를 안 보내면 늘 LOCATION_UNKNOWN 이 된다 — **바꾸기 화면에 들어올 때(그리고 바꾸기 직전) `POST /riders/me/locations` 로 지금 위치를 한 점 보내라**(tracking 은 운행 여부와 무관하게 받는다).
- 권역이 겹친 곳이면 겹친 센터 모두 "안"으로 본다(담당 센터가 아니어도 그 센터 권역 안이면 바꿀 수 있다).
- 검사 순서와 코드:
  1. 422 `MFC_NOT_AVAILABLE` (그대로)
  2. 409 `INVALID_STATE` 진행 중 배송 (그대로)
  3. 409 `INVALID_STATE` 운행 중 (그대로 — 위치보다 먼저)
  4. 409 `LOCATION_UNKNOWN` — 받은 위치가 없거나 5분 넘음. "위치를 켜고 잠시 뒤 다시"
  5. 422 `OUTSIDE_DELIVERY_AREA` — details `[{field: "currentMfcId", reason: <지금 위치 담당 센터>}]`, 어느 권역에도 없으면 details 없음
- tracking·serviceability 를 못 부르면 503(결과 불명 — 지금처럼 내 정보를 다시 읽어 맞춘다).

## A-2. `GET /riders/me/location-area`
- 응답 `RiderLocationArea`: `locationState`(FRESH·STALE·UNKNOWN), `locatedAt`(받은 시각, UNKNOWN 이면 null), `mfcId`(지금 위치 담당 센터 — 권역 밖·겹침이 안 풀림·FRESH 아님이면 null), `mfcIds`(들어가는 권역의 센터 모두 — **이 목록의 센터로만 바꿀 수 있다**, FRESH 아니면 빈 목록). 좌표는 돌려주지 않는다.
- 흐리게 하기는 `mfcIds` 기준으로(담당 `mfcId` 하나만 보면 겹친 곳에서 고를 수 있는 센터를 막는다). 503 은 "권역 밖"으로 보이지 말 것. 정본은 A-1.
