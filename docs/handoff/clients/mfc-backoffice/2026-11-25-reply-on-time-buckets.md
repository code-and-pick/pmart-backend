# 회신: 정시 배송률 시간·주·달 칸(DD-6)

- 보낸 영역: delivery
- 작성: pmart-backend PC 5, 2026-11-25
- 답하는 것: pmart-clients `docs/handoff/mfc-backoffice/2026-11-21-backend-hourly-metrics.md` 의 정시 배송 부분(PC 3 회신 `2026-11-21-reply-hourly-and-order-metrics.md` §2 에서 DD-6 으로 넘긴 것)
- 상태: PR(브랜치 `delivery/dd6-on-time-buckets`, delivery-dispatch 0.18.0). LAN 반영은 PC 1 배포 뒤.

## 바뀐 것
- `GET /api/v1/metrics/on-time-deliveries?from&to&mfcId&bucket` — `bucket` = `HOUR`·`DAY`(기본, 예전 그대로)·`WEEK`·`MONTH`.
- 칸 = 약속 끝(`promisedAt` — 한집·알뜰은 확정 + 50분, 예약은 시간대 끝)을 Asia/Seoul 로 내린 값. 날짜 합계(DAY)와 같은 기준이다.
- 줄 `date` = 칸 시작 날짜(주는 월요일, 달은 1일), `hour` = HOUR 일 때만 칸 시작 시각(`2026-11-21T14:00:00+09:00`), 그 밖은 null. 0건 칸은 줄이 없다.
- 기간 상한: HOUR 7일, DAY 31일, WEEK·MONTH 366일 — 1년 보기를 한 번에 읽을 수 있다. 넘거나 모르는 단위는 400. 첫·끝 주·달은 from·to 로 잘린다.
- 권한·나머지 칸(due·onTime·late·unjudged·pending)은 그대로. 피킹 지표(mfc-fulfillment 0.34.0)와 같은 모양이라 같은 차트 코드를 쓸 수 있다.

## 화면에서 할 일
- 배포 뒤 정시 배송의 "시간" 단추를 켜고, 일·주·월은 31일씩 나눠 읽던 것을 `bucket=WEEK|MONTH` 한 번으로 바꿔도 된다.
