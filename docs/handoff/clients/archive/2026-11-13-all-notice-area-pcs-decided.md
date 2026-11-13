# 알림: 예정 영역 PC 확정 — backoffice = PC 2, rag = PC 4 (2026-11-13)

- 보낸 영역: platform
- 작성: pmart-backend, 2026-11-13
- 대상: pmart-clients 전체(`all`) — 특히 backoffice·hr-backoffice·web-common
- 앞 문서: `../all/2026-11-13-all-notice-area-plan-rag-backoffice.md`(그 문서 §1 표의 "PC 2 추천·PC 4 추천"을 이 문서가 바꾼다)
- 백엔드 정본: pmart-backend `docs/operations/pc-split.md` §1-1

## 내용

| 영역 | PC(확정) | 언제 |
|---|---|---|
| **backoffice**(백엔드 + 클라이언트 `web/backoffice`·`web/hr-backoffice`·웹 공통 — 경계는 정할 것) | **PC 2** | 로그인 마무리 뒤(앞 문서 §2 — 지금 남은 것: CSRF 시기, mfc_app 실기기 확인) |
| **rag**(백엔드만) | **PC 4** | 지금 나눠도 됨 |

- 근거: 사용자 2026-11-13 "PC2가 백오피스 내용이고 PC4 가 RAG쪽 역할이야"(처음 말한 배치 그대로).
- 백오피스 화면 서빙(nginx)은 계속 PC 4, 배포는 PC 1 — 바뀐 것 없음.
- 클라이언트에 부탁: backoffice 영역이 PC 2 에서 시작되면 web-common 주인을 그 PC 로 옮길지 클라이언트 영역 규칙에 반영해 달라.
