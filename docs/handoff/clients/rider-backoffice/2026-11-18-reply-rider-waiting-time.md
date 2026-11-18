# 회신 — 라이더 백오피스 관제 "라이더 운행 시작 시각·대기 시간"

보낸 영역: delivery
- 작성: pmart-backend PC 5(delivery), 2026-11-18
- 대상: pmart-clients `web/rider-backoffice` — 요청 `docs/handoff/rider-backoffice/2026-11-18-backend-rider-waiting-time.md`(PR #123)
- 사용자 2026-11-18 결정:
  - **대기 = 운행 중 + 신규배차 켬 + 맡은 배송 0건**(신규배차를 꺼 둔 시간은 대기가 아님)
  - **운행을 켜 둔 전체 시간은 "접속 시간"으로 따로** — 기존 "운행시간"(배차 수락 ~ 전달 완료)은 그대로
- 상태: 백엔드 PR(PC 1 검토·배포 전) — `gen:api` 다시

## 답한 질문
1. `OperatingState.changedAt` 은 **신규배차 켜고 끄기에도 바뀐다**(상태 칸 하나라도 바뀌면) — "운행 시작"으로 쓰지 마세요. 대신 아래 `onDutySince`.
2. "대기"는 원문에 정의가 없어 사용자가 위처럼 정함.

## 관제 목록 — delivery-tracking **0.9.0** `GET /rider-locations` `ControlRider`
| 값 | 뜻 |
|---|---|
| `onDutySince` | 지금 운행 구간 시작(rider). 신규배차 켜고 끄기로는 안 바뀜 → "운행 시작 HH:MM · N시간째" |
| `waitingSince` | 대기 시작 — 신규배차 켜 있고 진행 중 배송 0건일 때만: (신규배차 켠 시각, 이 라이더의 마지막 배송이 끝난 시각) 중 늦은 때. 배송 중이거나 신규배차를 껐으면 null → "대기 N분째" |
- 계정 제한은 따지지 않는다(제한이면 신규배차가 꺼져 null).
- 배포 전(rider V9 전)부터 운행 중이던 라이더는 그 라이더의 마지막 상태 변경 시각부터로 잡힘(기록이 없어서 — 근사).

## 오늘 합계 — delivery-execution **0.25.0** 직원용 `GET /rider-operating-time?riderId=&from=&to=`
- 응답에 `connectedSeconds`(접속 — 운행을 켜 둔 시간 합)·`waitingSeconds`(대기 합), `days[]` 에도 날짜별 같은 두 값. 기존 `totalSeconds`·`seconds`(운행시간 — 수락 ~ 완료)는 그대로.
- 대기 합 = 신규배차 켠 운행 구간 − 배송을 맡은 구간(배정 ~ 완료·실패·취소, 진행 중이면 지금).
- rider-service 를 못 부르면 두 값은 null(운행시간은 그대로) — "불러오지 못함"으로.
- 권한은 기존과 같음(RIDER_OPS·OPERATOR_ADMIN·CS_AGENT). **라이더 본인 조회(`/riders/me/operating-time`)에는 이 두 값이 없음**.
- 화면 문구: "오늘 운행시간"(수락 ~ 완료)·"접속 시간"(운행 켬)·"대기 시간" 세 가지를 섞지 않게.
