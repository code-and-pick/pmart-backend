# 라이더 앱 — 계정 제한(RS-1) 때 앱에서 보이는 것

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-14
- 대상: pmart-clients `apps/rider_app`(rider-app 영역 — PC 5)
- 계약: `contracts/openapi/rider-service.yaml` 0.3.0 — 라이더 앱이 부르는 경로·필드는 **변화 없음**(새 경로 `/riders/{riderId}/restrictions` 는 운영자용). `gen:api` 는 다시 해도 되고 안 해도 된다
- 상태: **백엔드 PR 단계, LAN 미배포** — PC 1 이 병합·배포하면 이 파일에 날짜를 적는다

## 무엇이 바뀌나
백오피스 운영자가 라이더 계정을 제한할 수 있게 됐다(신규배차 제한·일시 중지·계약 종료, 사용자 결정 2026-11-14). 제한이 걸리면:

| 무엇 | 서버 동작 | 앱에서 |
|---|---|---|
| 내 정보 `GET /riders/me` | `restriction.state` 가 제한 값, `restriction.until`(기한, 없으면 무기한), `operating.newDispatchEnabled=false`. **운행 상태(status)는 그대로** | 제한 안내 띠(단계·기한). 사유는 앱에 보여 주지 않는다(운영자 기록) |
| 신규배차 켜기·운행 시작 `PUT /riders/me/state` | 신규배차 제한이면 신규배차 ON 403, 일시 중지·계약 종료면 운행 시작 403(전과 같음) | 403 이면 "계정 제한으로 배차를 받을 수 없음" |
| 응답 전 제안 | 서버가 닫는다 — 수락하면 `outcome: LATE`, `lateReason: INELIGIBLE` | 이미 처리하는 LATE 안내 그대로 |
| **픽업 전 배정된 배송** | **배정이 풀린다**(다른 라이더에게 재배차) — 배송 배차 조회에서 내 배정이 사라지고, 가게 인계(QR)는 409 NOT_ASSIGNED | 진행 목록에서 빼고 "배정이 취소되었습니다" 안내. 가게로 가는 중이면 멈추게 |
| **픽업한 배송** | **그대로** — 끝까지 전달·완료한다(사용자 결정) | 평소처럼 전달 완료까지. 위치 업로드를 멈추지 않는다 |

- 제한이 풀리면(운영자) `restriction.state` 가 `NORMAL` — 신규배차는 라이더가 다시 켠다(자동으로 켜지지 않음).
- 기한(`until`)이 지나면 서버가 제한 없음으로 본다(`GET /riders/me` 도 NORMAL).
- 배정이 풀린 것을 바로 알리는 푸시는 아직 없다 — 앱이 진행 중 배송을 다시 조회할 때 안다(알림은 notification 쪽 나중).
