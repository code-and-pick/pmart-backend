# 백오피스 — 로그인 역할별 메뉴 (MFC 화면 포함)

> 2026-11-09 사용자 결정: **기존 백오피스(pmart-clients `web/backoffice`) 하나에서 로그인 권한에 따라 메뉴가 바뀌는 방식**. 화면 구현은 클라이언트 몫 — 요청서 `docs/handoff/clients/backoffice/2026-11-09-clients-request-backoffice-role-menus.md`.
> 아래 "임시 기본값"은 사용자 확인 전 내가 정한 것. 바뀌면 이 문서와 요청서를 고친다.
> 2026-11-13 갱신: 직원 로그인은 employee-auth-service 하나(ADR-0056, auth-service 운영자 로그인·`/operator-accounts` 없어짐). 인사(직원 등록·상태)는 별도 앱 인사팀 백오피스(hr-backoffice, :8444).

## 1. 원칙
- 메뉴는 **로그인(티켓 교환) 응답·`GET /api/v1/employee-auth/sessions/current` 의 `roles`·`centers`** 로 정한다(백엔드 변경 없음). 토큰 클레임에도 같은 값이 있다(ADR-0056 — 토큰 모양은 예전 운영자 토큰과 같음, `typ=OPERATOR`). 역할·센터 = 계정 값 ∪ 지금 유효한 승인 권한(grant)이라 로그인·갱신 때마다 바뀔 수 있다.
- **메뉴 숨김은 편의일 뿐, 권한은 서버가 행위마다 판정한다**(BO 문서·클라이언트 원칙 그대로). 숨긴 화면의 API 를 불러도 서버가 403 을 준다.
- 역할 이름은 백엔드가 실제로 쓰는 이름을 쓴다(클라이언트 문서의 담당 그룹 CS / MFC_OPS … 는 설명용 묶음 — 역할 이름이 아님).

## 2. 역할 → 메뉴 (임시 기본값, 2026-11-09)

| 역할(실제 이름) | 보이는 메뉴 | 근거 API(정본) |
|---|---|---|
| (모든 운영자) | 로그인·내 세션, 업무 도우미 | employee-auth, ai-assistant |
| `OPERATOR_ADMIN` | 직원 계정 보정(역할·센터·상태 — 화면 요청 아직 없음). 평소 역할 부여는 권한 요청(approval `GRANT`), 직원 등록·상태는 인사팀 백오피스 | employee-auth `PUT /employee-auth/employees/{operatorId}` |
| `MD` | 카테고리, 상품 등록·일괄·조회, 콘텐츠 등록·일괄, 가격·가격 일괄, 센터 취급 | product·product-content·pricing·mfc-inventory `sku-handlings` |
| `PRODUCT_APPROVER`·`DISPLAY_APPROVER`·`PRICE_APPROVER`·`SERVICEABILITY_APPROVER`·`CENTER_MASTER_APPROVER`·`INVENTORY_APPROVER` | 승인함·승인 상세(자기 유형) | approval `/approvals*` |
| **`MFC_SUPERVISOR`(센터 관리자)** | **MFC 메뉴 전체**(아래 3장) — 자기 `centers` 의 센터만 | mfc-* 서비스 |
| `MFC_WORKER`(작업자) | 백오피스 메뉴 없음(내 세션만) — 현장 작업은 PDA 앱 | — |
| `CS_AGENT`(고객센터 상담원, 2026-11-09) | 고객센터 문의(목록·상세·답변) | case `/inquiries` |

- **여러 역할**: 메뉴를 합쳐 보인다(합집합).
- **여러 센터**(`centers` 가 둘 이상): MFC 메뉴 위에 센터 선택 — 고른 센터의 `mfcId` 로 부른다. 하나면 고르지 않는다.
- 역할이 하나도 맞지 않으면 내 세션만.

## 3. MFC 메뉴 (센터 관리자)

| 메뉴 | 내용 | API |
|---|---|---|
| 작업자 | 등록, 고용 형태·자격·업무, 상태(정지·퇴사), 근무 등록·취소, 휴게, 일일크루 그날 등록 | mfc-capacity `/mfcs/{id}/workers*` |
| 배정 | 작업 배정·재배정, 배정 추천(OR-Tools) 계산·확정, 배정 목록 | mfc-capacity `/assignments`, `/assignment-plans*` |
| 작업 현황 | 센터 작업 목록·상세, 확인 필요 포장 목록·확인 | mfc-fulfillment `/tasks?mfcId=&state=`, `/packages?mfcId=&checkRequired=true`, `/packages/{id}/handover-checks` |
| 이동·보충 | 이동 지시 만들기·취소, 목록 | mfc-inbound `/move-orders*` |
| 입고 | 입고 예정 수령, 품질검사(LOT 분리), 적치, 회수품 반입, 공급 요청 | mfc-inbound `/initial-stock-loads*`, `/inbound-tasks*`, `/return-receipts`, 공급 요청 |
| 재고 | 센터 재고 조회, 위치·LOT 정정, 순환 실사, 보충 기준, 출하 부적격 LOT, 상품 취급 | mfc-inventory `/mfcs/{id}/stock*`, `/stock-corrections`, `/count-sessions*`, `/replenishment-policies`, `/unshippable-lots`, `/sku-handlings*` |
| 품질 | 격리·해제(다른 관리자 승인), 폐기, 회수품 검수 | mfc-quality |
| 위치(칸) | 칸 등록·수정·닫기·폐지, 도면 좌표·치수 | mfc-location `/locations*` |
| 운영 제한 | 접수 중지·수용 축소 등록·해제 | mfc-capacity `/operation-restrictions*` |

- 화면 요청서는 이미 일부 있다: 입고 B1~B4(`all/2026-11-07-clients-request-mfc-inbound-screens.md`), 피킹·포장(`all/2026-11-07-clients-request-mfc-picking-packing-screens.md`), PDA 응답(`mfc-app/2026-11-08-clients-notice-pda-response-plan.md`).

## 4. 근무 기본값 (같은 날 결정, mfc-capacity 0.9.3)
- 근무를 따로 등록하지 않은 작업자 = **센터 직원 근무 시간표(center-master STAFF)** 를 근무로 본다(사용자 "풀타임 = 센터 운영시간"). 가짜 근무표는 작업자 메뉴의 근무 등록으로 넣는다(있으면 그것이 우선).
- LAN 시험 센터 STAFF 07:00~24:00(임시 구현값, 시드).
