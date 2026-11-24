# 회신 — 문의 목록 센터 거르기 (R-17, case-service 0.40.0)

보낸 영역: backoffice
받는 앱: backoffice
원 문서: `pmart-clients docs/handoff/backoffice/2026-11-24-backend-inquiry-center-filter.md`
작성: pmart-backend PC 2 백엔드, 2026-11-24 — 사용자 "b 로 하고 라이더는 운행 센터로"·"어 진행해"
상태: **PR 대기**(case-service 0.40.0 — #422 R-16 다음에 병합) — 병합·LAN 배포는 PC 1

## 1. 경로
`GET /api/v1/inquiries?centerId=` — 직원만(고객·라이더 토큰은 무시), 보기 거르기(권한 그대로). 다른 거르기(status·requesterType·deliveryIssue·customerId·riderId·orderId)와 같이 쓴다.
- 결과 = **그 센터 문의 + 센터가 없는 문의(늘 함께 — 결정 b)**.
- 목록 줄 새 칸 `centerId`(string|null — null 이면 "센터 없음" 꼬리표). 직원에게만 실린다.

## 2. 센터는 무엇인가
| 문의 | centerId |
|---|---|
| 고객 문의 · 주문 있음 | 그 주문의 이행 센터(order `mfcId`). 받을 때 저장. **0.40.0 전에 받은 문의**는 서비스가 뜰 때 order 원장으로 한 번 채운다 |
| 고객 문의 · 주문 없음(일반 질문 등) | null — 늘 함께 |
| 라이더 문의 | **접수 때** 라이더 운행 센터(rider `GET /riders/me` 의 operating.mfcId). 나중에 운행 센터를 옮겨도 그 문의는 접수 때 센터 |
| 라이더 문의 · 운행 센터 없음·읽지 못함 | null — 늘 함께 |
- **0.40.0 전에 받은 라이더 문의**는 운행 센터를 채울 길이 없어(라이더 토큰으로만 읽힘) null — 모든 센터에 함께 보인다.

## 3. 범위 밖
- 문의에는 수 세기 경로가 없어 바꿀 것 없음. 상세·답변·만족도 집계는 그대로(모든 센터).
