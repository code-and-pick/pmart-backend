# 알림 — 라이더 위치를 고객앱 카카오맵에 실시간으로 (세 쪽 조율)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-19
- 사용자: "현재 PMart에서 라이더앱의 GPS 위치가 고객앱 카카오맵에 실시간으로 표시되도록 구현해야 해. 각 담당 세션(라이더앱, 백엔드, 고객앱)에 필요한 작업을 직접 요청하고 … 조율해줘."
- 받는 쪽: 라이더 앱(`apps/rider_app`), 고객 앱(`apps/customer_app`), 백엔드 delivery-tracking(PC 5)

## 지금 있는 것 (확인함 2026-11-19)
| 구간 | 지금 | 근거 |
|---|---|---|
| 라이더 앱 → 서버 | 5초마다 GPS 표본, **10초마다** `POST /riders/me/locations`(LocationBatch, 여러 점) — 앱 화면이 없어도 안드로이드 포그라운드 서비스(`RiderLocationService.kt`)가 이어서 올림 | rider_app `location.dart`·`location_reporter.dart` |
| 서버 저장·공개 | delivery-tracking 이 저장, `DeliveryEta.riderLocation`(lat·lng·recordedAt·staleSeconds) — **고객은 픽업 뒤(TO_CUSTOMER·AT_CUSTOMER)만**(사용자 2026-11-17 "픽업 뒤 정확한 위치"), 직원은 배정 뒤. 한 점만 | tracking 0.6.0 |
| 서버 → 고객 앱 | `GET /deliveries/{id}/eta` + SSE `GET /deliveries/{id}/stream` — **새 위치가 오면 3초 안에 ETA_UPDATED**(이미 구현, 계약 설명만 늦었음 → 0.11.1 고침) | `EtaStreams`·`EtaViews.fingerprint` |
| 고객 앱 지도 | 카카오맵 `tracking_map.dart` — 라이더 마커. 지금은 `GET /eta` 를 **10초마다** 다시 읽어 마커를 옮김(SSE 는 단계·ETA 에만 씀) | customer_app `orders_screens.dart:1193` |
| LAN | 배정된 배송 `/eta` 200, 위치 있음(품질 GOOD) — 다만 그 라이더 마지막 점이 762초 전(앱이 안 올리는 중) | 2026-11-19 조회(좌표는 안 봄) |

→ 지금 지연은 최악 약 20초(10초 올리기 + 10초 조회). 아래로 **5~8초**로 줄인다.

## 정한 것 (API·데이터·통신)
- **통신**: 라이더 앱 → 서버 HTTPS 묶음 올리기(그대로). 서버 → 고객 앱 **SSE**(`/deliveries/{id}/stream`, 기존). WebSocket·새 API 는 만들지 않는다.
- **데이터**: 고객이 보는 위치 = `StreamEvent.eta.riderLocation` = `{lat, lng, recordedAt, staleSeconds}` + `eta.riderLocationQuality`(GOOD·LOW_ACCURACY·…). 바뀌는 것 없음.
- **간격**: 라이더 앱은 **배송을 맡고 있는 동안(배정 ~ 전달 완료·반납) 5초마다 올림**(표본 5초 그대로), 맡은 배송이 없으면 지금처럼 10초. 서버 SSE 조회 3초. → 고객 화면 지연 5~8초.
- **공개 범위**: 그대로 — 고객은 내 상품 픽업 뒤만, 지금 한 점만, 끝나면 null. 픽업 전에는 MFC·내 집 마커와 ETA 만.
- **오래된 위치**: `staleSeconds` > 60 이면 고객 앱은 마커를 흐리게 + "라이더 위치를 확인하고 있어요". 서버는 오래된 점도 보낸다(지어내지 않음).

## 할 일
### 라이더 앱
1. 맡은 배송이 있을 때(배정 뒤 ~ 끝) 올리기 간격 5초(`locationFlushIntervalProvider`·포그라운드 서비스 둘 다). 없을 때 10초.
2. 앱을 닫아도 배송 중이면 포그라운드 서비스가 계속 올리는지 실기기 확인(LAN 에서 배정 라이더 마지막 점이 762초 전이었다 — 앱이 꺼져 있었는지 확인).
3. 위치 권한 "항상 허용"·배터리 최적화 제외 안내가 배송 시작 전에 보이는지.

### 고객 앱
1. 배송 현황 지도는 **SSE ETA_UPDATED 의 `eta.riderLocation`** 으로 마커를 옮긴다(부드럽게 이동). 10초 `GET /eta` 주기 조회는 SSE 가 끊겼을 때만.
2. `staleSeconds` > 60 이면 "라이더 위치를 확인하고 있어요"(마커 흐리게). `riderLocation` null 이면 마커 없음(픽업 전·끝남).
3. 화면을 떠나면 SSE 를 닫는다(배터리·연결 수).

### 백엔드(PC 5 — delivery-tracking)
1. 계약 0.11.1 — SSE 가 위치를 보낸다는 설명(코드는 이미 됨).
2. 배포 뒤 LAN 에서 실제 배송으로 SSE 이벤트 간격 확인(라이더 앱 5초 반영 뒤).
