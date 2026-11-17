# 요청 — 문의 만족도 (case-service 0.15.0)

보낸 영역: backoffice
받는 곳: pmart-clients 고객앱(고객센터 문의 상세)·backoffice 웹(관리자 — 모아 보기)
상태: case PR 대기(병합·배포는 PC 1). 문의 사진(0.14.0, PR #79) 뒤 번호.

## 정한 것 (사용자 2026-11-17 "답변 뒤 1~5점 + 한 줄" — 업무 원문 Commerce §14.2)
- 답변을 받은 문의에 남긴 고객이 **한 번만** 1~5점과 의견 한 줄(200자, 선택). 고치기 없음.
- 만족도는 **상담 평가일 뿐** — 반품·환불 종결이나 라이더 평가가 아니다. 남겨도 문의 상태는 그대로.
- **상담원 평가·제재에 쓰지 않는다**: 상담원·지원센터 화면에는 보이지 않고, 본사 관리자만 모아 본다.
- 항목·문구는 참고 업체 것을 그대로 쓰지 않는다(원문).

## API
- 남기기 `POST /api/v1/inquiries/{inquiryId}/satisfaction` `{ score: 1~5, comment? }` (고객 토큰, Idempotency-Key·X-Source-System) → 200 문의 상세(`satisfaction: { score, comment, ratedAt }`).
  - 409 `INQUIRY_NOT_ANSWERED`(답변 전) · 409 `ALREADY_RATED` · 404(남의 문의) · 400(점수·200자).
- 문의 상세 `satisfaction` — 남긴 본인과 OPERATOR_ADMIN 에게만 온다(없으면 아직 안 남김).
- 모아 보기(관리자) `GET /api/v1/inquiries/satisfaction-summary?answeredFrom=&answeredTo=` (답변 시각 기준, 92일까지)
  → `{ answeredCount(분모), ratedCount(분자), scoreSum, averageScore(남긴 건 0 이면 null), byScore: {"1":n…"5":n} }`.

## 화면 제안
- 고객앱 문의 상세: 답변이 달린 뒤 "답변이 도움이 되었나요?" 별 1~5 + 한 줄(선택). 남긴 뒤에는 내가 남긴 점수만 보여 주고 다시 남기기 없음. 409 ALREADY_RATED 면 다시 불러와 남긴 값을 보여 준다.
- 백오피스(관리자만): 고객센터 메뉴 "문의 만족도" — 기간 고르기(최대 92일), "답변 N건 중 M건 응답(응답률 M/N)", 평균(없으면 "—"), 1~5 막대. **상담원별 순위·개별 상담원 이름은 보여 주지 않는다.** 문의 상세에서 관리자에게만 고객 점수 표시.
