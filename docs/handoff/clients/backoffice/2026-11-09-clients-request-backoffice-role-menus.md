# [pmart-backend → pmart-clients] 백오피스 로그인 역할별 메뉴 + MFC 메뉴 (2026-11-09)

- 결정: 사용자 2026-11-09 — **백오피스 웹 하나에서 로그인 권한(역할)에 따라 메뉴가 바뀌게** 한다. MFC 화면도 같은 백오피스 안에.
- 백엔드 정본: `pmart-backend/docs/architecture/backoffice-role-menus.md`(역할 → 메뉴 표, MFC 메뉴 목록·API).
- 백엔드 변경: 없음(역할·센터는 이미 로그인 응답과 `GET /api/v1/auth/sessions/current` 에 있음). 근무 기본값만 mfc-capacity 0.9.3(아래 4).

## 1. 요청
1. 지금 고정 메뉴 목록(`src/app/App.tsx` 의 `nav`)을 **세션의 `roles` 로 거르는** 방식으로 바꿔 주세요. 대응표는 백엔드 문서 2장(임시 기본값).
2. 여러 역할이면 메뉴를 합쳐 보여 주세요.
3. `MFC_SUPERVISOR`(센터 관리자) 메뉴(문서 3장): 작업자 · 배정 · 작업 현황 · 이동·보충 · 입고 · 재고 · 품질 · 위치(칸) · 운영 제한. `centers` 가 둘 이상이면 MFC 메뉴 위에 센터 선택.
4. `MFC_WORKER` 는 백오피스 메뉴 없음(현장은 PDA 앱).

## 2. 지켜 주세요
- 메뉴 숨김은 편의입니다. 권한은 서버가 행위마다 판정합니다(403 을 사용자 문구로). `docs/apps/backoffice-web.md` 의 업무 도우미 "메뉴는 숨기지 않는다" 문장은 이 결정에 맞춰 고쳐 주세요(도우미는 모든 운영자에게 보이므로 결과는 같음).
- 역할 이름은 백엔드 실제 이름(`OPERATOR_ADMIN`, `MD`, `*_APPROVER`, `MFC_SUPERVISOR`, `MFC_WORKER`)으로 거르세요. 문서의 담당 그룹(CS / MFC_OPS …)은 역할 이름이 아닙니다.

## 3. MFC 화면 우선순위 제안
작업자(근무 포함) → 배정·배정 추천 → 작업 현황(확인 필요 포장) → 재고 조회 → 이동·보충 → 입고 → 품질 → 위치 → 운영 제한. 각 API 는 백엔드 문서 3장, 기존 요청서(11-07 입고 B1~B4·피킹·포장, 11-08 PDA 응답)와 같습니다.

## 4. 근무 기본값 (mfc-capacity 0.9.3)
- 근무를 등록하지 않은 작업자는 **센터 직원 근무 시간표(STAFF)** 를 근무로 봅니다. 시간표 밖이면 다음 작업 받기 409 `OFF_SHIFT`, 시간표가 없으면 422 `CONFIG_REQUIRED`. 배정 추천 결과의 `availabilityUnverifiedWorkers` 는 이제 "근무 미등록 — 센터 시간표로 계산한 작업자" 뜻입니다.
- 작업자 메뉴의 근무 등록(`POST /mfcs/{id}/workers/{workerId}/shifts`)으로 넣은 근무가 있으면 그것이 우선입니다.
