보낸 영역: cdc (PC 1 D 클론 cdc 세션)
받는 앱: cdc-backoffice (PC 5)
작성: 2026-11-27 — 요청 `pmart-clients docs/handoff/cdc-backoffice/2026-11-26-backend-display-numbers.md`(DN-1~6) + 덧붙임(시뮬레이터 기록에 번호 없음)

# 회신: 화면 번호 DN-1~7

| 번호 | 결과 | 판 |
|---|---|---|
| DN-1 결품 `GET /cdcs/{cdcId}/shortages` | **됨** — 줄에 `trfNumber`·`waveNumber`·`taskNumber`·`newTaskNumbers`(newTaskIds 순, 없으면 빈 문자열) | cdc-outbound 0.31.0 |
| DN-2 LPN `GET /cdcs/{cdcId}/lpns` | **됨** — 줄에 `trfNumber`·`waveNumber`·`departureNumber`(출발했을 때만) | cdc-outbound 0.31.0 |
| DN-3 운행 trips·`/trips/{id}` | **미룸** — transport-execution 은 지금 PC1 이 TE-1(StopArrived)로 고치는 중이라(사용자 결정) 그 뒤에 넣습니다 | — |
| DN-4 배차 transport-orders | **됨** — `trfNumbers`(trfIds 순)·`stops[].trfNumbers`(그 정차 trfIds 순). 생성·목록·상세·옮기기·계획완료·결행·취소 응답 모두 | transport-planning 0.4.0 |
| DN-5 리콜 상세 출발 줄 | **다음** — 번호는 cdc-outbound 에만 있어 cdc-quality 가 cdc-outbound 를 새로 불러야 합니다(설정 PC1). 따로 PR | — |
| DN-6 center-master `GET /centers?type=MFC` | **바꿀 것 없음** — 계약 `x-pmart-user-types: [OPERATOR]`, 코드에 역할 검사가 없어 CDC_SUPERVISOR 도 읽습니다. 403 이 나면 그 토큰·요청을 알려 주세요 | — |
| DN-7 시뮬레이터 기록 번호 없음 | **서버는 번호가 있음** — 아래 | — |

## DN-7 — 출발 DEP-c5b97d11-…-4f57 은 번호가 있습니다
- 번호 열은 DB 기본값이라 경로(직원·`/internal`·시뮬레이터)와 상관없이 모든 TRF·Wave·작업·출발에 붙고 비울 수 없습니다(NOT NULL). 빈 행이 생길 수 없어 채울 것도 없습니다.
- LAN cdc-outbound 에서 직접 확인(2026-11-27 09시대, 읽기만): `departureNumber` **S420933**, `transfers[0].trfNumber` **T697148**(TRF-SIM-MWXA84EQ).
- 시험: `OutboundFlowIT` 에 SIMULATED 출발 목록의 `departureNumber`·`transfers[].trfNumber`, LPN 목록 번호 확인을 넣었고 통과합니다.
- 그러니 화면이 번호 없는 값을 받았다면 **cdc-outbound 의 `/cdcs/{cdcId}/departures` 가 아닌 다른 응답**일 가능성이 큽니다. 운행(transport-execution trips 의 `departureId`·`transfers[].trfId`)이 그런 모양입니다 — DN-3 로 넣습니다. 그 화면이 어느 주소를 부르는지 알려 주시면 맞춰 보겠습니다.
