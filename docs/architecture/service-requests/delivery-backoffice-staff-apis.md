# 백오피스 고객센터·라이더 운영 메뉴용 직원 API (delivery 영역에 요청)

- 보낸 영역: platform(PC 1), 2026-11-15
- 받는 서비스: **rider-service**, **delivery-execution-service**, **delivery-dispatch-service**(모두 delivery 영역 — PC 5)
- 왜: 사용자 결정(2026-11-15) — 기존 백오피스(`web/backoffice`)에 **고객센터**·**라이더 운영** 메뉴 묶음을 붙인다(MFC 는 따로 앱). PC 1 이 API 를 점검해 보니 처리 API(제한·검토·실패 확정·배차 취소)는 있지만 **화면 입구가 되는 목록·검색이 없다**(배송 ID·라이더 ID 를 미리 알아야만 쓸 수 있음).
- 같이 하는 일(PC 1, platform): employee-auth 역할 목록에 **`RIDER_OPS`** 추가(pmart-config), case-service R-5(상담원 라이더 컴플레인 기록 → `RiderComplaintRecorded`), order-service 직원용 주문 검색(O-6), 백오피스 화면 요청서. 시험 계정(`CS_AGENT`+`RIDER_OPS`)은 PC 1 이 LAN 에 만든다.
- 진행: 이 파일대로 구현해 PR(브랜치 예 `delivery/backoffice-staff-apis`). 계약 버전을 올리고 게이트웨이 허용 목록 생성 파일을 다시 만든다. 아래 경로·이름은 제안 — 기존 계약 관례와 다르면 관례를 따르고 PR 에 적는다.

## 역할 (사용자 결정 2026-11-15)

| 역할 | 뜻 |
|---|---|
| `RIDER_OPS`(새) | 라이더 운영 — "라이더 운영 전용 역할을 만든다"(사용자 "응"). 지금 OPERATOR_ADMIN 만 할 수 있는 라이더 운영 일을 이 역할도 한다 |
| `CS_AGENT` | 고객센터 상담원 — **배차 상태 조회를 연다**(사용자 "응"). 고객 문의("라이더 배정됐나요")에 답하려고 |
| `OPERATOR_ADMIN` | 지금처럼 전부 |

## BOD-1. 라이더 운영 API 에 `RIDER_OPS` 권한 (rider·execution·dispatch)

지금 코드 주석에 "지원센터·배송 역할이 생기면 더한다"고 적힌 곳에 `RIDER_OPS` 를 더한다.

| 서비스 | 곳 | 지금 | 바꿀 것 |
|---|---|---|---|
| rider | `RiderService` 제한 걸기·바꾸기·풀기·이력, 제한 검토 목록·결정(≈398–415행 `OPERATOR_ADMIN` 검사) | OPERATOR_ADMIN 만 | + `RIDER_OPS` |
| execution | `Access.operatorCanSee`(배송·도움요청 조회), `Access.operatorCanDecide`(전달 실패 확정·반려) | OPERATOR_ADMIN·CS_AGENT·그 센터(조회)/MFC_SUPERVISOR(결정) | + `RIDER_OPS` (둘 다) |
| dispatch | `DeliveryQueries`(배차 상태 조회), `CancellationService`(픽업 전 배차 취소·결과 조회) | 그 센터 직원·OPERATOR_ADMIN(취소 조회는 CS_AGENT 도) | + `RIDER_OPS`(조회·취소). 취소 사유가 OPERATOR 면 approvalRef 필수는 그대로 |

## BOD-2. `CS_AGENT` 배차 상태 조회 (dispatch)

- `GET /api/v1/deliveries/{id}/dispatch`(`DeliveryQueries`) 에 `CS_AGENT` 를 더한다(조회만 — 취소는 아님. ADR-0060 "고객센터 취소 못함"과 같은 방향).

## BOD-3. 직원용 라이더 목록·검색·상세 (rider)

- 지금: OPERATOR 용 `/riders`, `/riders/{id}` 없음(내부용만) → 라이더 운영 화면에서 riderId 를 찾을 수 없다.
- 제안: `GET /api/v1/riders`(직원) — 필터 `phone`(**정확히 일치만**, 부분 검색 금지), `name`(앞부분 일치 정도는 정할 것), `restriction`(NORMAL·DISPATCH_RESTRICTED·SUSPENDED·TERMINATED), `operatingStatus`, `operatingMfcId`, `vehicleType`, 페이지(최대 50), 정렬 최근 가입·최근 상태 변경. 목록 응답의 전화번호는 가린다(예 `010-****-1234`).
- `GET /api/v1/riders/{riderId}`(직원) — 기본 정보(이름·전화 전체·수단·운행 상태·신규 배차·운행 MFC·제한 상태·사유·기한·가입일·마지막 heartbeat). **계좌·거주지는 내보내지 않는다**(정산 화면이 생길 때 따로 정함).
- 권한: `RIDER_OPS`·`OPERATOR_ADMIN`·`CS_AGENT`(상담원이 컴플레인 기록 때 라이더 확인). 개인정보 로그 금지.
- 경로가 라이더 본인 API(`/riders/me` 등)와 겹치지 않게 — 게이트웨이 허용 목록은 사용자 종류별이라 같은 경로에 RIDER·OPERATOR 가 섞이면 정리해서 적는다.

## BOD-4. 전달 실패·도움요청 대기 목록 (execution)

- 지금: 단건 조회만(`GET /deliveries/{id}`, `GET /deliveries/{id}/help-requests/{rid}`) → 지원센터가 무엇을 처리해야 하는지 볼 곳이 없다(case-service R-4 사건함은 아직 — 1차에서 뺌, PC 1 추천 2026-11-15. 사건함을 넣게 되면 이 목록은 그 사건이 정본).
- 제안
  - `GET /api/v1/delivery-help-requests`(직원 — **`/api/v1/help-requests/**` 는 게이트웨이에서 이미 case-service 로 간다**(계약만 있는 case 도움요청 사건), 그래서 다른 이름. 새 앞머리라 PC 1 이 pmart-config `api-gateway.yml` 라우트에 더한다 — PR 에 적어 달라) — 필터 `state`(OPEN·IN_PROGRESS·RESOLVED, 기본 OPEN), `category`(DELIVERY_FAILURE 포함), `mfcId`, `riderId`, `from`/`to`, 페이지(최대 50), 오래된 것 먼저(대기 큐). 행: requestId·deliveryId·orderId·mfcId·riderId·category·stage·state·createdAt·사진 수. `detail`(라이더 글)은 목록에서 빼고 단건에서만.
  - `GET /api/v1/delivery-failures?state=REPORTED`(또는 위 목록의 `category=DELIVERY_FAILURE` 로 대신 — 하나면 충분, PC 5 가 고름) — 운영자 확정·반려 대기(FAILURE_REPORTED) 배송.
  - 지금 case 가 없으니 **처리 상태를 execution 이 갖는다**: 운영자가 도움요청을 "처리함"으로 닫는 `POST /api/v1/delivery-help-requests/{deliveryId}:{requestId}/resolutions`(또는 `/api/v1/deliveries/{id}/help-requests/{rid}/resolutions` — 기존 단건 조회 경로 아래, 라우트 추가 없음)(메모 선택) 정도 — R-4 로 case 가 생기면 그 사건이 정본이 되고 이 API 는 정리(지금 state 칸 OPEN·IN_PROGRESS·RESOLVED 가 있음). **필요 없다고 보면 PR 에 이유를 적는다**.
  - 권한: 조회 `Access.operatorCanSee`, 닫기·실패 결정 `operatorCanDecide`(BOD-1 반영 뒤).

## 백오피스 화면 쪽

- PC 1 이 화면 요청서(`docs/handoff/clients/backoffice/`)에 이 API 들을 적는다. 경로·필드가 위 제안과 달라지면 PR 설명에 적어 주면 요청서를 맞춘다.
- 정할 것(사용자 결정 필요 시 PC 5 가 사용자에게): 라이더 이름 검색 방식, 도움요청 "처리함" 닫기 API 를 둘지.

## BOD-5. 주문 ID 로 배송 찾기 (2026-11-15 추가 — 클라이언트 BR-56)
- 지금: order 는 deliveryId 를 저장하지 않고, dispatch·execution·tracking 계약에 orderId 로 찾는 직원 조회가 없다 → 상담원·라이더 운영이 주문 조회 뒤 배송 번호를 직접 넣는다.
- 제안: dispatch `GET /api/v1/deliveries?orderId=`(직원 — 배송 요약 목록: deliveryId·유형·상태·배정 라이더·mfcId). dispatch `delivery` 표에 order_id 가 이미 있다. 권한은 BOD-1·2 와 같게(`OPERATOR_ADMIN`·`CS_AGENT`·`RIDER_OPS`·그 센터).

## BOD-6. 라이더 version 을 조회 응답에 (2026-11-15 추가 — 클라이언트 질문)
- `POST /riders/{riderId}/restrictions` 의 `X-Expected-Version` 은 라이더 행 version 과 비교하는데, 화면이 그 값을 얻을 곳이 없다(`GET …/restrictions` 의 이력 version 은 프로필·운행 변경으로 낡을 수 있다). `GET /riders/{riderId}/restrictions` 응답(또는 BOD-3 직원 상세)에 지금 라이더 `version` 을 싣는다.

## 회신(PC 5, 2026-11-15 — 브랜치 `delivery/backoffice-staff-apis`)

구현함. 제안과 다른 곳은 굵게.

| 번호 | 한 것 | 계약 |
|---|---|---|
| BOD-1 | `RIDER_OPS` 추가 — rider 계정 제한·이력·제한 검토(목록·결정), execution 배송·도움요청 조회(`Access.operatorCanSee`)·전달 실패 결정·도움요청 닫기(`operatorCanDecide`), dispatch 배차 조회·배차 취소·취소 결과 조회. 사유 OPERATOR 면 approvalRef 필수는 그대로 | rider 0.4.0, execution 0.4.0, dispatch 0.3.2 |
| BOD-2 | dispatch `GET /deliveries/{id}/dispatch` 에 `CS_AGENT`(조회만 — 배차 취소는 403, 시험으로 확인) | dispatch 0.3.2 |
| BOD-3 | `GET /riders`(찾기)·`GET /riders/{riderId}`(상세) — 권한 OPERATOR_ADMIN·RIDER_OPS·CS_AGENT. **이름 검색은 없다**(사용자 2026-11-15 "아이디로 찾는 게 낫지 않아? 이름은 동명이인이 있을 수도") — `riderId`·`phone` **완전 일치**(숫자만, 하이픈 무시) + restriction(지금 유효한 제한)·operatingStatus·operatingMfcId·vehicleType, 정렬 JOINED_DESC·STATE_CHANGED_DESC, page·size(≤50)·hasNext. **목록·상세 모두 이름·전화 가림**(`nameMasked` 첫·끝 글자, `phoneMasked`) — 상세에 전화 전체를 내지 않는다: ADR-0062 "적용 범위"가 라이더 상세(BOD-3)도 같은 방식이라고 정해 둠 → **`POST /riders/{riderId}/pii-reveals`**(`{reason 2~200자, fields?: [NAME, PHONE]}`, audit-service `subjectType RIDER`·`caseRef RIDER:<riderId>` 동기 기록 뒤에만 원문, audit 실패면 503). 계좌·거주지는 내보내지 않음 | rider 0.4.0, V5(목록 색인) |
| BOD-4 | **`GET /delivery-help-requests`**(state 기본 OPEN·category·mfcId·riderId·from/to, 오래된 것 먼저, cursor = 건너뛸 수, size ≤50, 행에 detail 없음 — 단건에서만) + **닫기 `POST /deliveries/{id}/help-requests/{requestId}/resolutions`**(`{note?}` → RESOLVED, 권한 = 실패 결정과 같음, 배송 상태·버전·사건 없음, 다시 닫으면 409). 단건 조회 응답에 detail·evidenceRefs·resolvedAt(+ 직원에게만 resolvedBy·resolutionNote). 그 센터 직원(MFC_SUPERVISOR 등)은 목록에 mfcId 필수. **전달 실패 대기는 따로 만들지 않고 BOD-5 목록 `GET /deliveries?state=FAILURE_REPORTED&sort=OLDEST`** — 실패 등록은 도움요청 표가 아니라 배송 상태라서 | execution 0.4.0, V6 |
| BOD-5 | **dispatch 가 아니라 execution `GET /deliveries`**(orderId·riderId·mfcId·state·type, NEWEST·OLDEST, cursor·size ≤50) — execution 도 DeliveryCreated 부터 모든 배송(배정 전 UNASSIGNED)을 갖고, 게이트웨이 `/api/v1/deliveries/**` 가 이미 execution 으로 가서 라우트 추가가 필요 없다(`/api/v1/deliveries` 도 이 패턴에 맞음 — 게이트웨이 라우팅 시험 추가). 행은 ID·상태·시각만 | execution 0.4.0 |
| BOD-6 | `GET /riders/{riderId}/restrictions` 응답에 `version`(지금 라이더 행 버전), 상세에도 `version` | rider 0.4.0 |

PC 1 이 할 것(병합·배포 때):
- pmart-config `api-gateway.yml` — `delivery-execution-service-deliveries` 라우트 Path 에 `/api/v1/delivery-help-requests` 추가(시험 사본 두 곳은 이 PR 에서 고침). `/api/v1/deliveries`·`/api/v1/riders`·`/api/v1/riders/{id}/pii-reveals` 는 기존 라우트로 간다.
- pmart-config `rider-service-local.yml`(필요하면 `-lan`) — `pmart.rider.audit-base-url`(코드 기본값 `http://audit-service:8503` — order 와 같은 값, local 은 `http://localhost:8503`).
- 마이그레이션: rider V5(색인만), execution V6(help_request 닫기 칸 + 색인). 배포 순서 rider·delivery-execution·delivery-dispatch → api-gateway(허용 목록 6줄).
- 화면 요청서(`docs/handoff/clients/backoffice/`)를 위 경로·필드로 맞춤 — 특히 라이더 찾기에 이름 칸 없음, 상세 전화는 "보기"(사유 입력) → pii-reveals.

## BOD-7. 산정 불가 수행료 조정 (2026-11-17, PC 5 — 사용자 "산정 불가 수행료 조정하는 기능 만들자", 브랜치 `delivery/earning-adjustments`)
- 실패 확정·운영자 배차 취소·수락 금액 모름은 수행료가 "산정 불가(지원센터 조정)"로 남는데 조정 방법이 없었다 — 라이더 탈퇴도 막혔다(RS-4).
- 사용자 결정: 라이더 운영·관리자가, **귀책에 따라 지급/미지급**(라이더 귀책 미지급), **지급이면 거리별로 측정된 금액**(수락 당시 금액), **바로 확정·조정일 + 3영업일 지급**. 상담원은 목록 보기만.
- execution 0.13.0: `GET /rider-earnings/adjustment-queue`(riderId·size·cursor), `POST /rider-earnings/{earningId}/adjustments`(`{fault, reason}` → `{adjustmentId, decision, fault, earning}`). 라이더 알림함에 결과(사유 없음).
- **PC 1**: 게이트웨이 경로 `/api/v1/rider-earnings/**` → delivery-execution(pmart-config `api-gateway.yml`) + 허용 목록 다시 생성. **PC 2(백오피스)**: 라이더 운영 화면에 조정 대기·조정 — handoff `docs/handoff/clients/backoffice/2026-11-17-request-earning-adjustments.md`.
