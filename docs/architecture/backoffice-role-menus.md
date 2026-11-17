# 백오피스 — 로그인 역할별 메뉴 (MFC 화면 포함)

> 2026-11-09 사용자 결정: **기존 백오피스(pmart-clients `web/backoffice`) 하나에서 로그인 권한에 따라 메뉴가 바뀌는 방식**. 화면 구현은 클라이언트 몫 — 요청서 `docs/handoff/clients/backoffice/2026-11-09-clients-request-backoffice-role-menus.md`.
> 아래 "임시 기본값"은 사용자 확인 전 내가 정한 것. 바뀌면 이 문서와 요청서를 고친다.
> 2026-11-17 갱신(PC 2): 고객센터·라이더 운영 줄에 11-16~17 메뉴(CS 접수·사건함 처리·도움요청 대화·라이더 문의·교대 인계·행위 요청·문의 사진·배송 신고), 본사 관리자 백오피스 줄(처리 대기·현황·만족도) 보충 — 서버 권한(`docs/architecture/auth/permissions.md`) 기준. **센터 관리자 `MFC_SUPERVISOR`** 는 MFC 백오피스 앱 외에 기본 백오피스의 실시간 배송 현황(자기 센터)·사건함 배차 실패(자기 센터)도 쓸 수 있다(bff 0.3.1·case 0.5.0). **확정은 미룸**(2026-11-17 사용자 "일단 미뤄둬도 괜찮을거같아") — LAN 웹 빌드가 올라와 역할별로 눌러 본 뒤 확정한다(메뉴 숨김은 편의일 뿐 권한은 서버 판정이라 급하지 않음).
> 2026-11-13 갱신: 직원 로그인은 employee-auth-service 하나(ADR-0056, auth-service 운영자 로그인·`/operator-accounts` 없어짐). 인사(직원 등록·상태)는 별도 앱 인사팀 백오피스(hr-backoffice, :8444).

## 1. 원칙
- 메뉴는 **로그인(티켓 교환) 응답의 `roles`·`centers`(새로고침 뒤에는 access token 클레임)** 로 정한다. `GET /api/v1/employee-auth/sessions/current` 에는 `roles`·`centers` 가 **없다**(2026-11-15 확인 — 이 줄이 전에는 있다고 잘못 적혀 있었다). 토큰 클레임에도 같은 값이 있다(ADR-0056 — 토큰 모양은 예전 운영자 토큰과 같음, `typ=OPERATOR`). 역할·센터 = 계정 값 ∪ 지금 유효한 승인 권한(grant)이라 로그인·갱신 때마다 바뀔 수 있다.
- **메뉴 숨김은 편의일 뿐, 권한은 서버가 행위마다 판정한다**(BO 문서·클라이언트 원칙 그대로). 숨긴 화면의 API 를 불러도 서버가 403 을 준다.
- 역할 이름은 백엔드가 실제로 쓰는 이름을 쓴다(클라이언트 문서의 담당 그룹 CS / MFC_OPS … 는 설명용 묶음 — 역할 이름이 아님).

> **2026-11-15 사용자 결정**: MFC 는 기본 백오피스에서 떼어 **따로 앱**으로(아래 3장 MFC 메뉴는 그 앱으로 옮긴다 — 앱 요청은 따로). 고객센터·라이더 운영 메뉴는 기본 백오피스에 붙이고, 메뉴는 **상위-하위 묶음**(상품 관리·승인·고객센터·라이더 운영·업무 도우미)으로. 요청서 `docs/handoff/clients/backoffice/2026-11-15-clients-request-backoffice-cs-rider-menus.md`.

## 2. 역할 → 메뉴 (임시 기본값, 2026-11-09)

| 역할(실제 이름) | 보이는 메뉴 | 근거 API(정본) |
|---|---|---|
| (모든 운영자) | 로그인·내 세션, 업무 도우미 | employee-auth, ai-assistant |
| `OPERATOR_ADMIN` | 직원 계정 보정(역할·센터·상태 — 화면 요청 아직 없음). 평소 역할 부여는 권한 요청(approval `GRANT`), 직원 등록·상태는 인사팀 백오피스 | employee-auth `PUT /employee-auth/employees/{operatorId}` |
| `MD`, `OPERATOR_ADMIN`(2026-11-15 사용자 — 판매 상태 변경은 MD 만) | 카테고리, 상품 등록·일괄·조회, 콘텐츠 등록·일괄, 가격·가격 일괄, 센터 취급 | product·product-content·pricing·mfc-inventory `sku-handlings` |
| `PRODUCT_APPROVER`·`DISPLAY_APPROVER`·`PRICE_APPROVER`·`SERVICEABILITY_APPROVER`·`CENTER_MASTER_APPROVER`·`INVENTORY_APPROVER` | 승인함·승인 상세(자기 유형) | approval `/approvals*` |
| **`MFC_SUPERVISOR`(센터 관리자)** | **MFC 메뉴 전체**(아래 3장) — 자기 `centers` 의 센터만 | mfc-* 서비스 |
| `MFC_WORKER`(작업자) | 백오피스 메뉴 없음(내 세션만) — 현장 작업은 PDA 앱 | — |
| `CS_AGENT`(고객센터 상담원, 2026-11-09), `OPERATOR_ADMIN`(2026-11-15 메뉴 요청 — 문의 답변은 **보기만**, 답변은 CS_AGENT: 2026-11-16 사용자 (가), case 0.4.2) | **고객센터** 묶음: 주문 조회, 문의 답변(고객 문의 — 사진 보기 0.14.0·배송 신고 표시 0.16.0), **전화·채팅 접수**(CS 접수 0.12.0 — 본인 확인 방법·대리 표시, 기록만), 라이더 컴플레인(기록·조회), 클레임 조회, 배송·배차 확인, 전달 실패 확정·반려(2026-11-15), 오배송 기록, 사건함 배송 사건 **보기·메모**(집기·처리는 지원센터) | order `/orders/lookups`, case `/inquiries`·`/inquiry-photos/{id}`·`/cs-intakes`·`/rider-complaints`·`/cases`(배송 사건 보기·`notes`·MISDELIVERY 만들기), claim `/claims`, execution `/deliveries/{id}`·`failure-decisions`, dispatch `/deliveries/{id}/dispatch` |
| `RIDER_OPS`(라이더 운영·지원센터, 2026-11-15 새 역할 — 지원센터 역할을 겸함) | **라이더 운영** 묶음: 라이더 찾기·계정 제한, 제한 검토, 전달 실패·도움요청 대기, 배송·배차 확인·픽업 전 배차 취소, 라이더 컴플레인 조회, 주문 조회, **사건함 배송 사건 처리**(집기·처리 끝내기·다시 처리하기·넘기기·후속 처리·메모·행위 요청 기록 0.17.0), **도움요청 대화 답장**(execution DE-2 — 관리자 팀으로 넘긴 건은 관리자만), **라이더 문의 답변**(0.13.0), **교대 인계**(지원센터 팀 DELIVERY_OPS — 0.13.1) | rider `/riders*`, execution·dispatch `/deliveries/{id}*`·`/help-requests*`, case `/cases*`·`/obligations*`·`/shift-handovers*`·`/inquiries?requesterType=RIDER`·`/rider-complaints`(조회), order `/orders/lookups`, bff `/views/case-inbox` |
| `OPERATOR_ADMIN`(본사 관리자 — 백오피스 운영, 2026-11-16~17) | 위 고객센터·라이더 운영 묶음의 **보기**(문의 답변·라이더 문의 답변은 보기만) + **처리 대기**(멈춘 사가 다시 확인·처리 완료, 이행 확인 필요, MFC 중지·인계 재시도 — F-4 뒤), **사건함 전체**(주문 사건 — 정산 불일치·결제 미확정 포함, 남에게 배정, 관리자 팀 ORDER_OPS 교대 인계, 후속 처리 면제·실패, 메모 가리기), **현황**(접수 건 현황 건수 0.18.0, 문의 만족도 모아 보기 0.15.0 — 상담원별 숫자 없음), 개인정보 열람 기록, 승인함 | bff `/views/pending-work`·`/commands/*`·`/views/case-inbox`, case `/cases/metrics`·`/inquiries/satisfaction-summary`·`/cases*`·`/shift-handovers*`, audit `/pii-access-logs`, approval `/approvals*` |

> **2026-11-16 클라이언트 묶음(사용자 결정 — pmart-clients PR #22, 알림 `pmart-clients docs/handoff/backoffice/2026-11-16-backend-menu-shared-lookup-group.md`)**: `CS_AGENT`·`RIDER_OPS`(그리고 `OPERATOR_ADMIN`)가 **함께 쓰는 조회 화면**(주문 조회·통합 조회·실시간 배송 현황·배송·배차 확인·라이더 컴플레인)은 클라이언트에서 **"조회·현황" 공통 묶음**으로 보여 준다. 위 표의 역할 → 볼 수 있는 화면은 바뀌지 않았다(묶는 방법만). 앞으로 두 역할이 함께 쓰는 조회 화면은 이 묶음, 한쪽만 쓰는 화면은 그 역할 묶음.

- **여러 역할**: 메뉴를 합쳐 보인다(합집합).
- **여러 센터**(`centers` 가 둘 이상): MFC 메뉴 위에 센터 선택 — 고른 센터의 `mfcId` 로 부른다. 하나면 고르지 않는다.
- 역할이 하나도 맞지 않으면 내 세션만.

## 3. MFC 메뉴 (센터 관리자)

> 2026-11-15: 메뉴별 API 상태(구현·없음·계약만)는 요청서 §2-1, 없는 것은 `service-requests/mfc-backoffice-staff-apis.md`(PC 3). 이 메뉴는 **별도 앱 MFC 백오피스**(`app=mfc-backoffice`, `https://192.168.0.40:8445`)로 — 요청서 `docs/handoff/clients/mfc-backoffice/2026-11-15-request-mfc-backoffice.md`.

| 메뉴 | 내용 | API |
|---|---|---|
| 작업자 | 등록, 고용 형태·자격·업무, 상태(정지·퇴사), 근무 등록·취소, 휴게, 일일크루 그날 등록 | mfc-capacity `/mfcs/{id}/workers*` |
| 배정 | 작업 배정·재배정, 배정 추천(OR-Tools) 계산·확정, 배정 목록 | mfc-capacity `/assignments`, `/assignment-plans*` |
| 작업 현황 | 센터 작업 목록·상세, 확인 필요 포장 목록·확인 | mfc-fulfillment `/tasks?mfcId=&state=`, `/packages?mfcId=&checkRequired=true`, `/packages/{id}/handover-checks` |
| 이동·보충 | 이동 지시 만들기·취소, 목록 | mfc-inbound `/move-orders*` |
| 입고 | 입고 예정 수령, 품질검사(LOT 분리), 적치, 회수품 반입, 공급 요청 | mfc-inbound `/initial-stock-loads*`, `/inbound-tasks*`, `/return-receipts`, `/supply-requests*` |
| 재고 | 센터 재고 조회, 위치·LOT 정정, 순환 실사, 보충 기준, 출하 부적격 LOT, 상품 취급 | mfc-inventory `/mfcs/{id}/stock*`, `/stock-corrections`, `/count-sessions*`, `/replenishment-policies`, `/unshippable-lots`, `/sku-handlings*` |
| 품질 | 격리·해제(다른 관리자 승인), 폐기, 회수품 검수 | mfc-quality |
| 위치(칸) | 칸 등록·수정·닫기·폐지, 도면 좌표·치수 | mfc-location `/locations*` |
| 운영 제한 | 접수 중지·수용 축소 등록·해제 | mfc-capacity `/operation-restrictions*` |

- 화면 요청서는 이미 일부 있다: 입고 B1~B4(`all/2026-11-07-clients-request-mfc-inbound-screens.md`), 피킹·포장(`all/2026-11-07-clients-request-mfc-picking-packing-screens.md`), PDA 응답(`mfc-app/2026-11-08-clients-notice-pda-response-plan.md`).

## 4. 근무 기본값 (같은 날 결정, mfc-capacity 0.9.3)
- 근무를 따로 등록하지 않은 작업자 = **센터 직원 근무 시간표(center-master STAFF)** 를 근무로 본다(사용자 "풀타임 = 센터 운영시간"). 가짜 근무표는 작업자 메뉴의 근무 등록으로 넣는다(있으면 그것이 우선).
- LAN 시험 센터 STAFF 07:00~24:00(임시 구현값, 시드).
