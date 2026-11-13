# 라이더 앱 — 배차 API 동작 바뀐 것(PR #8 검토 반영)

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-13
- 대상: pmart-clients `apps/rider_app`(rider-app 영역 — PC 5)
- 앞 안내: `2026-11-13-notice-dispatch-apis.md`(배차), `2026-11-13-notice-execution-apis.md`(배송 수행) — 이 파일은 그 뒤 바뀐 동작만
- 계약: `contracts/openapi/delivery-dispatch-service.yaml` — **필드·경로 변화 없음**(응답에 값이 비는 경우·거절 이유만 달라짐). `gen:api` 다시 할 필요 없음
- 상태: **백엔드 PR #8 단계, LAN 미배포** — PC 1 이 병합·배포하면 이 파일에 날짜를 적는다

## 1. 배달리스트(일반 배차모드)
| 경우 | 전 | 지금 | 앱에서 |
|---|---|---|---|
| 본인이 방금 배차취소한 배송 | 목록에 다시 보이고 수락하면 다시 배정됨 | **목록에 안 보임**. 오래된 목록으로 수락하면 `outcome: LATE`, `lateReason: INELIGIBLE` | LATE 는 "이미 다른 라이더에게 가는 배송" 안내로 같이 처리, 목록 새로고침 |
| 위치 품질이 나쁨(`TIME_SKEW` — 단말 시각이 서버보다 많이 앞서거나 앞 점보다 이름, 그 밖 `UNKNOWN` 등) | 최근 위치면 목록이 보임 | **목록이 빔**(`items: []`, pollAfterSec 그대로), 수락도 INELIGIBLE | 빈 목록 + "위치 확인 중" 안내. 위치 업로드가 정상이 되면 다시 보임 |

위치 품질 기준은 AI 추천 배차와 같다 — `GOOD`·`LOW_ACCURACY` 만 쓴다(tracking 이 `POST /riders/me/locations` 의 정확도·단말 시각으로 정한다 — 단말 시계를 자동 시간으로 두게 안내). 위치가 2분 넘게 안 와도 목록이 비는 것은 전과 같다.

## 2. 배송 배차 조회 `GET /api/v1/deliveries/{id}/dispatch`
- **제안만 받았고 배정되지 않은 라이더**에게는 `assignment` 가 비어 온다(전에는 배정된 다른 라이더 ID 가 보였다). 제안 이력(`attempts[].offers`)은 전처럼 본인 것만.
- 배정된 본인에게는 그대로.
- 앱에서: 제안을 놓친 배송 화면은 "다른 라이더에게 배정됨" 정도로만 보여 주고 상대 정보는 기대하지 않는다.

## 3. 배차 취소 `POST /api/v1/deliveries/{id}/assignment-cancellations`
- 같은 `Idempotency-Key` 를 다른 요청에 다시 쓰면 409 `IDEMPOTENCY_CONFLICT`(전에는 경우에 따라 500). **요청마다 새 키, 같은 요청을 다시 보낼 때만 같은 키** — 앞 안내와 같은 규칙.

## 4. 그 밖
- 배송 약속: 한집·알뜰배달 = 주문 확정 + 50분, **예약배달 = 고객이 고른 도착 시간대 끝**(사용자 확정 2026-11-13). 라이더 앱에 따로 보이는 값은 아직 없다.
- 반복 배차취소: 제재는 없고, 최근 7일 본인 배차취소 1건마다 AI 추천 제안 순위가 뒤로·배달리스트에 새 배송이 1분 늦게 보인다(사용자 확정). 앱에 이 사실을 안내할지는 클라이언트가 정한다(API 로 건수를 주지는 않는다 — 필요하면 요청).
