# 요청 — 사건 의무("후속 처리") 화면 (case-service 0.8.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(사건함 상세·고객센터 — 지원센터·상담원·관리자)
상태: case PR 대기(병합·배포는 PC 1). PR #34 → #35 → 이 PR 순서.

## 1. 정한 것 (사용자 2026-11-16 "고친 안으로 진행해 줘")
- 사건 안의 "후속 처리"(회수·재배송·환불·고객 연락 …). **열린 의무가 있으면 사건을 닫을 수 없다**(409 OPEN_OBLIGATIONS).
- 종류 8개: 환불 REFUND·회수 RETURN_PICKUP·재배송 REDELIVERY·대사 RECONCILIATION·고객 연락 CUSTOMER_CONTACT·증빙 수집 EVIDENCE_COLLECTION·보상 COMPENSATION·기타 OTHER.
- 담당 팀: 지원센터 `DELIVERY_OPS`·관리자 팀 `ORDER_OPS`·고객센터 `CS`(비우면 사건 팀). 지원센터·고객센터는 배송 쪽 사건에만 맡길 수 있다.
- 등록: 사건을 다룰 수 있는 사람(지원센터·관리자). 해소: **담당 팀**과 관리자 — 고객센터 의무는 **상담원이** 해소한다(사건 자체는 지금처럼 보기만).
- 결과: 완료 `FULFILLED`(한 만큼 — 수량·금액 의무는 일부만 가능), 시도 기록 `ATTEMPTED`(상태 그대로, 메모 필수 — 예 "부재중 2회"), 면제 `WAIVED`·실패 `FAILED`는 **관리자만**(사유 필수).
- 근거(어느 시스템의 무엇 — 예 클레임 번호·인계 번호·통화 기록)는 필수지만 서버가 확인하지 않는다. 금액은 기록만(환불 실행은 기존 클레임·결제 메뉴).

## 2. API
- 등록 `POST /api/v1/cases/{caseId}/obligations` `{kind, owner?, targetRef?, qty?, amount?(원), dueAt?, note?}` → 201 의무. 403·404·409(닫힌 사건)·422(`UNSUPPORTED_KIND`·`OWNER_CANNOT_SEE_CASE`).
- 해소 `POST /api/v1/obligations/{id}/resolutions` `{outcome, resolvedQty?, resolvedAmount?, evidence:{system, ref, kind?}, note?}` → 200 의무(state·resolvedQty·resolvedAmount·resolutions[메모 포함]). 400(수량·금액·사유 빠짐) / 403 / 409(`INVALID_STATE` 끝남·`QTY_EXCEEDS_OBLIGATION`).
- 목록 `GET /api/v1/obligations?owner=&state=&caseId=` — 기본 열린 것, 기한·오래된 순. 상담원 "고객센터 후속 처리" = `owner=CS`.
- 사건 상세 `obligations[]`·`openObligationCount`, 사건 목록 `openObligationCount`(이제 실제 값). 사건 닫기 409 `OPEN_OBLIGATIONS`.

## 3. 화면에 바라는 것
- 사건 상세에 "후속 처리" 칸(등록·해소·시도 기록, 결과 이력과 메모). 열린 의무가 있으면 닫기 버튼 옆에 안내.
- 고객센터 메뉴 "고객센터 후속 처리"(owner=CS 열린 의무) — 상담원이 고객 연락 결과를 남긴다.
- 면제·실패 버튼은 관리자에게만(서버도 판정). 금액은 "기록" 문구로 — 이 화면에서 환불되는 것처럼 보이지 않게.

- 화면 이름(2026-11-16 사용자 결정, 클라이언트 알림): **"후속 처리"**("남은 할 일"은 쓰지 않음), 닫기 409 안내 "후속 처리가 남아 있어 닫을 수 없어요".

## 4. 계약
`contracts/openapi/case-service.yaml` 0.8.0 `/cases/{id}/obligations`·`/obligations`·`/obligations/{id}`·`/obligations/{id}/resolutions`, 닫기 설명.
