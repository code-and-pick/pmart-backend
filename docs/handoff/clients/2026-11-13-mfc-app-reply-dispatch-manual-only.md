# 회신: 배차 자동 요청 제거 — 점원 요청만 (BR-51)

- 보낸 영역: mfc
- 작성: pmart-backend, 2026-11-13
- 답하는 것: pmart-clients `docs/handoff/2026-11-10-backend-dispatch-manual-only.md`(BR-51 R1~R4)
- 상태: 백엔드 PR 로 올림(mfc-fulfillment 0.4.11). **LAN 반영은 PC 1 병합·배포 뒤** — 그 전 LAN 은 예전처럼 READY 때 자동 요청한다.

| # | 회신 |
|---|---|
| R1 | **뺐다**(사용자 2026-11-13 "자동요청빼"). 요청 없이 출하 대기(READY)가 돼도 `DispatchRequested` 를 내지 않는다. 점원은 READY 뒤에도 `POST /shipments/{id}/dispatch-requests` 로 요청할 수 있다(PACK 시작 뒤면 됨, 예상 포장 1~180분 필수 그대로) |
| R2 | enum 은 **`[MANUAL, AUTO_ON_READY]` 그대로 둔다** — 이미 자동 요청된 출고 기록(LAN)이 AUTO_ON_READY 로 남아 있어서 조회 응답에 나올 수 있다. 새 요청은 늘 MANUAL. PDA 의 "서버가 배차를 요청했어요 — 관리자에게 알려 주세요" 문구는 예전 기록에만 해당 |
| R3 | 업무 원문(`Delivery-business-logic.md` §3.1)에는 자동 요청 문구가 없다 — 원문은 그대로. 설계 문서 D-5(`docs/business/flows/package-handover-decisions.md`)와 계약 설명을 고쳤다 |
| R4 | 요청 안 한 READY 출고를 센터 전체로 보는 목록 API 는 **아직 없다**(출고 단건 `GET /shipments/{id}` 의 `dispatchRequest` 가 없으면 미요청). 지금은 PDA 경고만으로 두고, 관리 화면이 필요하면 요청서로 보내 달라(예: `GET /shipments?mfcId=&state=READY&dispatchRequested=false`) |
