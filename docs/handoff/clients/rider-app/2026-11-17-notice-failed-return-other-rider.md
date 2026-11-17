# 알림 — 전달 실패 반납을 다른 라이더가 맡는 경우 + `returnPickupRequired`

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-17
- 대상: pmart-clients `apps/rider_app`(회수 화면 한 벌), `web/rider-backoffice`(전달 실패 확정 화면)
- 계약: execution 0.18.0, dispatch 0.7.0(백엔드 PR — PC 1 검토·배포 전)
- 사용자 2026-11-17: 원래 라이더가 더 갈 수 없을 때(사고 등) "다른 라이더에게 보낼 수 있게 함"

## 라이더 앱
- 전달 실패 반납(`FAILED_DELIVERY_RETURN`)이 **다른 라이더에게 제안으로** 갈 수 있다(일반 회수처럼 제안 → 수락). 회수지 = 원래 라이더의 마지막 위치(dispatch `recovery.pickup`, source `RIDER_LOCATION`).
- 그 라이더는 **받음(사진 → `returns/pickups`) → MFC 반납** — 반품 회수와 같은 단계. 같은 라이더 반납만 받음 없이 바로 반납.
- 앱이 판단할 값: 배송 조회 **`returnPickupRequired`**(RETURN 배송만) — true 면 받음 단계를 보이고, false 면 바로 "MFC 반납". purpose 로 추측하지 않아도 된다.
- 수행료: 다른 라이더는 잘못이 없으니 일반 표(회수지 → MFC) 그대로.

## 라이더 백오피스(전달 실패 확정)
- `POST /deliveries/{id}/failure-decisions` 에 `returnBy`(SAME_RIDER 기본·OTHER_RIDER) — custody RIDER 일 때만 의미.
- 라이더가 실패 등록을 못 한 경우: RIDER_OPS·OPERATOR_ADMIN 이 `reason`(RIDER_INCIDENT 등)을 넣어 **PICKED_UP·ARRIVED_AT_CUSTOMER 배송을 바로 확정**(등록 없이). 상담원은 403.
