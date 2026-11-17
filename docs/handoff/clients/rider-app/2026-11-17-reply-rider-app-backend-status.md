# 회신 — 라이더 앱 코드의 "계약에 없음" 표시 정리 + 기록 화면 API

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app`(브랜치 `rider-app/reference-ui` 기준으로 읽음)
- 상태: 아래 "이미 있음"은 LAN 배포됨. "새로 만듦"은 백엔드 PR 단계(PC 1 병합·배포 뒤 날짜를 적는다)

## 이미 있음 — 앱이 목에서 실서버로 바꿀 수 있는 것
앱 주석에 "계약에 없음 / 백엔드 요청 중"으로 남아 있지만 이미 계약·LAN 에 있다. `gen:api` 다시.

| 앱 위치 | 서버 |
|---|---|
| `trip_repository.dart`·`offer_detail_screen.dart`·`trip_widgets.dart` 주문 요약·가게 정보 | 제안 `GET /offers` 의 `order`(mfcName·mfcAddress·items·itemAmount·deliveryNote — 수락 전부터), 배송 `GET /deliveries/{id}`(execution) 의 `orderSummary`(orderNumber 포함) — 회신 `2026-11-16-reply-rider-order-summary.md` |
| `rider_map.dart` 좌표 | 제안 요약 `pickupPoint`·`dropAreaPoint`(대략)·`dropEtaSeconds`, 정확한 전달지는 픽업 뒤 `drop-info` |
| `signup.dart` 전기자전거·킥보드·면허 | rider 계약 `E_BIKE`·`KICKBOARD`, 가입 `licenseNumber`·`POST /riders/license-checks` — 회신 `2026-11-16-reply-trip-info-and-signup.md` |
| `signup.dart`·`mfc_directory.dart` 지점 목록 | `GET /mfc-pickup-points`(라이더 토큰, 운영 중 MFC 이름·주소·좌표) — 회신 `2026-11-16-reply-mfc-pickup-points.md` |
| `delivery_repository.dart`·`support_thread_screen.dart` 지원센터 대화 | `GET/POST /deliveries/{id}/help-requests/{requestId}/messages` — 회신 `2026-11-16-reply-help-request-messages.md` |
| `delivery_controller.dart` 내 도움요청 목록 | `GET /deliveries/{id}/help-requests` |
| 배송 밖 문의·알림함 | `POST/GET /inquiries`(분류 SETTLEMENT 등), `GET /me/notifications` — 회신 `2026-11-16-reply-rider-inquiries.md`, `2026-11-17-reply-rider-notifications.md` |
| `providers.dart` "BR-02 로그인 API 없음" | 라이더 로그인 rider-auth `POST /rider-auth/social-logins`(PC 5) — 연결은 앱 차례 |

## 새로 만듦(이번 PR — delivery-execution 0.11.0)
| 앱 위치 | 서버 |
|---|---|
| `records.dart` 운행시간("확인 불가") | **`GET /riders/me/operating-time?from=&to=`**(날짜, 기본 오늘·최대 31일) → `{from, to, totalSeconds, days:[{date, seconds}]}`. 사용자 결정 2026-11-17 **"운행시간은 배차 수락하고 전달완료까지의 시간"** — 끝낸 배송(전달 완료·회수 반납)마다 수락 ~ 완료, 여러 건을 함께 들고 있던 시간은 한 번만. 날짜는 한국 시간 자정 기준. 오늘 = from 생략, 이번 주 = from 주 시작·to 주 끝 |
| 기록 "배달현황"(원문 §8.1 주문번호·픽업지 마트 이름) | 내 배달 내역 `GET /riders/me/deliveries` 각 항목에 `orderNumber`·`mfcName`(이번 배포 뒤 배정부터). 배달료·거리는 `earning`, 소요시간은 `assignedAt`~`completedAt` |
| `delivery_repository.dart` "계약 값에 일반 전화가 없어 비운다" | 실패 등록 연락 기록 `method` 에 **`PHONE_CALL`·`SMS`** 추가(고객 전화번호 그대로 — 안심번호 없음, 사용자 2026-11-14). 일반 전화는 PHONE_CALL 로 |

## 다른 영역에 요청함(아직)
- 포장 라벨 숫자로 인계(§5, `qr_pickup_screen.dart`) — mfc PC 3 요청서 `mfc-fulfillment-service.md` F-8.
- 가게(지점) 전화번호 — platform PC 1 요청서 `center-master-service.md` CM-2. 들어오면 주문 요약·픽업지 목록에 붙여 다시 알린다.
- 반품 회수 화면 — `2026-11-17-request-claim-return-pickup.md`(앱 차례).
