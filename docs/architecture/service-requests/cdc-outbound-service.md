# cdc-outbound-service 에 들어온 요청

## CO-1 송장(TRF) 라벨 스캔 값 `TRF:{trfId}:{version}`
- **요청한 곳**: mfc-inbound(클라이언트 BR-53 R10, 2026-11-13)
- **필요한 것**: 출발 센터가 이송 박스·팔레트에 붙이는 송장 라벨의 바코드(QR) 원문을 `TRF:{trfId}:{version}` 으로(라벨 스캔 형식 `{유형}:{id}:{버전}` — 포장 `PKG:{packageId}:{packageVersion}` 과 같은 규칙). trfId 는 MFC 입고 예정(`InboundPromise.trfId`)과 같은 값.
- **지금(임시 방식)**: CDC 출고·이송이 없어 라벨도 없다. MFC PDA 는 목에서 이 형식을 해석하고, 맞는 송장인지는 trfId 만 비교한다(버전은 인쇄 때 값).
- **요청한 쪽에서 바꿀 것**: 없음(계약 `mfc-inbound-service.yaml` 0.8.0 도착 등록 설명에 적음). 형식을 바꾸면 mfc-inbound 계약과 클라이언트에 알린다.
- **사용자 결정 필요**: 없음(라벨 형식은 기존 규칙을 따름). LPN 별 라벨을 따로 둘지는 cdc-outbound 설계 때.
- 근거: `docs/handoff/clients/mfc-app/2026-11-13-reply-br53-inbound-receiving.md` §3
