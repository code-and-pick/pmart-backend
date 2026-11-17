# ADR-0068 라이더 운영 화면은 라이더 백오피스로 — 관제는 라이더 운영·관리자만, 운행 중 라이더의 현재 위치만

- 상태: 승인 (2026-11-17, 사용자 "추천대로 전부 시작해" — `docs/delivery/rider-backoffice-plan.md` §3 결정 1~4 에 PC 5 추천)
- 대체: 2026-11-15 사용자 결정 "고객센터·라이더 운영 메뉴는 기본 백오피스에"(`docs/architecture/backoffice-role-menus.md` 머리말, `docs/handoff/clients/backoffice/2026-11-15-clients-request-backoffice-cs-rider-menus.md` 2번)의 **라이더 운영** 부분. 고객센터 메뉴는 기본 백오피스 그대로.
- 관련: 라이더 백오피스 웹 `web/rider-backoffice`(PC 5, 뼈대·로그인 PC 1), delivery-tracking `GET /rider-locations`(0.8.0), rider-service `/internal/riders/on-duty`(0.8.0), delivery-execution 직원용 `/rider-earnings`·`/rider-operating-time`·배송 찾기 `orderNumber`(0.16.0).

## 결정
1. **라이더 운영 화면을 라이더 백오피스로 옮긴다** — 라이더 찾기·상세·원문 열람·계정 제한·제한 검토, 배송 찾기·배차 조회/취소, 전달 실패 확정, 지원센터 사건함(배송 쪽), 도움요청 대화, 라이더 문의, 수행료 조정. 화면과 그 화면이 부르는 배송·라이더 API 를 같은 PC(PC 5)가 맡는다.
   - **한 번에 옮기지 않는다**: 라이더 백오피스에 같은 화면이 생겨 LAN 에 올라간 뒤에 기본 백오피스(PC 2)의 그 메뉴를 내린다(그 사이 두 곳에 있어도 API 권한은 서버가 판정하므로 안전).
   - **상담원(CS_AGENT)이 보는 라이더·배송 조회는 기본 백오피스에 남긴다** — 상담원이 고객 문의를 처리하며 배송을 확인하는 길.
2. **관제는 라이더 운영(RIDER_OPS)·관리자(OPERATOR_ADMIN)만**(모든 센터). 센터 관리자(MFC_SUPERVISOR) 자기 센터는 다음 단계 — tracking 은 라이더 ↔ 센터 연결을 모르므로 서버가 센터 범위를 따로 확인해야 하고, 위치는 개인정보라 처음에는 보는 사람을 좁힌다. 서버가 403 으로 막는다.
3. **운행 중인 라이더의 현재 위치만.** 라이더 목록은 매 요청 rider-service 가 정한다(운행 중·배달지역에 그 센터) — 운행을 끈 라이더는 위치가 남아 있어도 나오지 않는다. **이동 이력은 보이지 않는다** — 이력이 필요하면 나중에 사유 입력 + 열람 기록(ADR-0062 방식)으로 따로 만든다.
4. **갱신은 화면이 10초마다 다시 부른다**(라이더 앱 위치 전송 간격과 비슷, 서버 부담 작음). 실시간 구독(SSE)은 다음 — 배송 ETA 구독(`/deliveries/{id}/stream`) 구조를 그대로 쓸 수 있다.

## 결과
- 백엔드(PC 5): tracking `GET /rider-locations?mfcId=`(위치·끊김·진행 중 배송, no-store), rider `/internal/riders/on-duty`, execution 직원용 수행료·운행시간·주문번호 찾기. 게이트웨이 경로(`/api/v1/rider-locations` → tracking, `/api/v1/rider-earnings`·`/api/v1/rider-operating-time` → execution)는 PC 1(설정 저장소).
- 클라이언트: 라이더 백오피스 화면(PC 5) — 관제 지도(카카오 지도 JavaScript 키, 도메인 등록은 PC 1), 라이더 찾기·배송 찾기·접수 건. 기본 백오피스(PC 2)는 같은 화면이 올라간 뒤 라이더 운영 메뉴를 내린다(그때 PC 5 가 알림).
- 다음 단계로 남김: 센터 관리자 관제, 위치 이력 열람(사유·기록), 관제 실시간 구독.
