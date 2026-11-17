# 회신 — 라이더 시뮬레이터 RR-03·RR-05·RR-07

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-18
- 대상: pmart-rider-simulation `docs/backend-requests.md`(열린 요청)
- 사용자 2026-11-18: "응 전부 진행해" — 시험 라이더 전용 정리 경로는 **두지 않음**(추천대로)

## RR-07 시뮬레이터 라이더 정리(탈퇴) — 구현됨, 정리 경로는 없음
- 탈퇴는 **구현·LAN 배포됨**(rider 0.6.0, 2026-11-17): `POST /riders/me/withdrawals` → 막는 것이 없으면 202 APPLIED·COMPLETED(이름·전화·계좌·거주지·면허를 지우고 로그인 세션을 끊음), `GET /riders/me/withdrawals/{requestId}`.
- 막는 것(409 INVALID_STATE, details `blockers`): 진행 중 배송, **확정 전 수행료**(완료 뒤 5시간 지나 확정되면 풀림 — 확정된 수행료는 막지 않음), 산정 불가 조정 대기, 열린 도움요청, 제한 검토 중.
  → 배송을 한 시뮬레이터 라이더도 **마지막 완료 5시간 뒤**면 탈퇴할 수 있다(전달 실패·자동 완료로 산정 불가가 생기면 지원센터 조정 뒤).
- 다시 가입: **같은 라이더 ID 로는 안 됨**(409 RIDER_WITHDRAWN). 새로 로그인해 생긴 새 주체로는 **같은 전화번호로 가입 가능**. 시뮬레이터처럼 정해진 ID(`RD-sim-01`)를 다시 쓰려면 탈퇴하지 말고 그대로 두거나, 새 ID(`RD-sim-03` …)를 쓴다.
- `RD-sim-` 전용 즉시 정리(미정산 무시) 경로는 **만들지 않는다**(사용자 결정). 막아 두려면 지금처럼 운영자 계정 제한 TERMINATED 또는 위 탈퇴.

## RR-03 포장 QR 값 — 그 방식으로 괜찮다
관찰용 직원 계정(읽기만)으로 `GET /shipments/{shipmentId}`(mfc-fulfillment)를 읽어 qrCode 를 얻는 것 = "라벨을 본다"는 가정으로 괜찮다. 백엔드 변경 없음. 쓰기(인계 허가·확정)는 라이더 토큰으로만.

## RR-05 ETA·실시간 구독 — 이제 있다
tracking `GET /deliveries/{id}/eta`·`/stream`(SSE)은 **구현·배포됨**(TR-3, 0.5.0~0.7.0 — 라이더 본인도 자기 배송은 볼 수 있음). 다만 **ETA 계산(카카오 길찾기)은 꺼져 있어** 값은 `ETA_PENDING` 으로 온다(켤지는 사용자 결정 대기). 단계·라이더 위치는 온다.
