# 라이더 앱 — 가입·운행·위치·배차(제안·배달리스트·묶음·배차취소) API 준비됨

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-13
- 대상: pmart-clients `apps/rider_app`(rider-app 영역 — PC 5)
- 계약 정본: `contracts/openapi/rider-service.yaml` 0.2.0, `delivery-tracking-service.yaml` 0.2.0, `delivery-dispatch-service.yaml` 0.3.1
- 상태: **백엔드 PR 단계(#7·후속 PR), LAN 미배포** — PC 1 이 병합·배포하면 이 파일에 날짜를 적는다. 그 전에는 화면·연결 코드만 준비(`gen:api`).
- 근거: 업무 원문 Delivery §1~§5, ADR-0054·0055(라이더 로그인), ADR-0058(AI 추천 배차·카카오 도로 거리·알뜰배달 묶음)

## 1. 로그인 뒤 가입·운행 (rider-service)
| 화면 | API | 메모 |
|---|---|---|
| 가입 여부 | `GET /api/v1/riders/me` | 404 = 미가입 → 가입 화면 |
| 가입 | `POST /api/v1/riders/signups` | **전화번호·배달 지역(mfcId)·배달 수단·약관**만(면허·주민번호 안 받음, 본인인증 없음 — 전화번호 중복만 409 `DUPLICATE_RIDER`). 즉시 운행 승인. 첫 배차 모드 AI 추천 |
| 운행 시작·종료, 신규배차 ON/OFF | `PUT /api/v1/riders/me/state` `{status: ON_DUTY|OFF_DUTY, newDispatchEnabled, mfcId?}` + `X-Expected-Version` | 계정 제한이면 403, 배달지역 밖 MFC 400, 버전 다르면 409 |
| 배차 설정 | `PUT /api/v1/riders/me/settings` `{oneAtATime, dispatchMode: NORMAL|AI_RECOMMENDED}` | 일반(배달리스트에서 골라 수락) / AI 추천(서버가 제안) |

## 2. 위치 (delivery-tracking-service)
- `POST /api/v1/riders/me/locations` `{points:[{lat,lng,accuracyM,recordedAt,...}]}` — 운행 중 주기적으로(예 10초마다 묶어서). **위치가 2분 넘게 안 오면 배차 후보에서 빠진다.**
- `GET /api/v1/riders/me/locations/latest` — 업로드 확인용.

## 3. AI 추천 배차 — 제안 (delivery-dispatch-service)
- `GET /api/v1/offers` — 응답 대기 중 제안. `pollAfterSec`(기본 5초) 간격으로 폴링, **null 이면 폴링 멈춤**(운행 종료·신규배차 OFF·제한). 남은 시간은 `remainingSec`.
- `GET /api/v1/offers/{offerId}` — 상세(**처음 연 시각을 기록** — 상세를 연 뒤 응답 없이 만료되면 거절 횟수에 들어간다, 원문 §4.4).
- `POST /api/v1/offers/{offerId}/responses` `{response: ACCEPT|REJECT, rejectReason?}` + `Idempotency-Key` → `outcome` ASSIGNED / REJECTED / LATE(`lateReason` EXPIRED·ALREADY_ASSIGNED·ORDER_CANCELLED·INELIGIBLE). 연속 거절 5회면 `rejectionNotice: true` → "신규배차를 꺼 주세요" 팝업.
- 표시(원문 §4.2): `summary` — 배송지 동 이름(`dropAreaLabel`), MFC → 고객 도로 거리(`distanceKm`), 라이더 → MFC 거리·시간(`pickupDistanceKm`·`pickupEtaSeconds`), 봉투·무게·주류, 예상 배달료(`estimatedFee`, 원문 §7.1 표), 한집/알뜰/예약(`deliveryMode`).
- **알뜰배달 묶음**: 같은 `bundleId` 의 제안 1~3개가 한 번에 온다(`bundleSize`, 고객 방문 순서 `stopSequence`). **한 화면으로 보여 주고 하나만 수락·거절해도 묶음 전체에 적용**된다.

## 4. 일반 배차모드 — 배달리스트
- `GET /api/v1/delivery-list` — 내 위치에서 반경 안(1→2→3km 단계) MFC 의 배차 대기 배송. 목록 선택 ≠ 확정(원문 §2.4).
- `POST /api/v1/delivery-list/{deliveryId}/acceptances` + `Idempotency-Key` — 상세 화면의 "수락" 버튼. 이미 배정이면 LATE·ALREADY_ASSIGNED("배차 요청 실패"), 최대 3건(하나씩 배달하기면 1건) 넘으면 INELIGIBLE.
- ⚠ 게이트웨이 경로 `/api/v1/delivery-list/**` 는 PC 1 이 추가해야 한다(백엔드 PR 에 적음).

## 5. 배정 뒤
- 배정 상태: `GET /api/v1/deliveries/{deliveryId}/dispatch`(본인 배정·제안 배송만).
- **배차취소(픽업 전)**: `POST /api/v1/deliveries/{deliveryId}/assignment-cancellations` `{requestedBy: RIDER, reason: RIDER_UNABLE, reasonDetail?}` + `Idempotency-Key` → 202, 상품은 점포에 남고 다른 라이더에게 재배차(원문 §5.4). **픽업 뒤는 422 `PICKED_UP_CASE_REQUIRED`** → 지원센터 연결 안내.
- 가게 도착·QR 인계: 기존 mfc-fulfillment `POST /packages/{id}/handover-authorizations`·`/handover-commits`(라이더 토큰) 그대로. 배송 수행 상태(가게 도착·고객 도착·전달 완료)는 delivery-execution 이 아직 없다(다음).

## 6. 아직 없는 것
- 수행 상태(execution), ETA·실시간 구독(SSE), 푸시(FCM — 별도 결정), 라이더 정지 뒤 배정 풀기, 탈퇴.
- 열린 요청 BR-23(포장 QR 원문)·BR-24(전달지 경로)는 이 안내와 별개로 따로 답한다.
