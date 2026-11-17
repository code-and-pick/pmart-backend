# 요청 — 접수 건 "행위 요청" 기록 (case-service 0.17.0)

보낸 영역: backoffice
받는 곳: pmart-clients backoffice 웹(접수 건 상세 — 관리자·지원센터)
상태: case PR 대기(병합·배포는 PC 1). 병합 순서 문의 만족도(#83, 0.15.0) → 배송 신고(PC 5 #82, 0.16.0) 뒤.

## 정한 것 (사용자 2026-11-17 "기록만 먼저" — 업무 원문 BackOffice §2 ActionRequest)
- 접수 건에서 다른 서비스에 한 요청(원결과 다시 조회·미전달 재전송·승인된 보상 실행·도메인 검토 요청)을 **기록**한다. **case 가 대신 실행하지 않는다** — 실제 명령은 지금처럼 처리 대기·각 메뉴에서 하고, 그 사실과 결과 ID 를 이 건에 남긴다.
- 남길 수 있는 사람 = 그 건을 다룰 수 있는 사람(집기·처리 끝내기와 같음 — 배송 건은 지원센터·관리자, 주문 건은 관리자). 처리 끝난 건에는 새 요청 없음.
- 승인된 보상 실행은 승인 번호 필수. "강제 성공·덮어쓰기" 같은 명령은 받지 않는다.

## API
- 남기기 `POST /api/v1/cases/{caseId}/action-requests` `{ actionType, targetService, command, targetRef?, idempotencyKey?, approvalRef?, payloadSummary? }`
  - actionType: `REQUERY_RESULT`(원결과 다시 조회) · `RESEND_UNDELIVERED`(미전달 재전송) · `EXECUTE_APPROVED_COMPENSATION`(승인된 보상 실행 — approvalRef 필수) · `REQUEST_DOMAIN_REVIEW`(담당 서비스 검토 요청)
  - → 201 `{ actionRequestId, state: REQUESTED, … }`. 403(그 건을 못 다룸) · 409(처리 끝난 건) · 422 `APPROVAL_REQUIRED`·`UNSUPPORTED_ACTION`.
- 결과 남기기 `POST /api/v1/cases/{caseId}/action-requests/{actionRequestId}/results` `{ state, resultSystem?, resultRef?, resultKind?, note? }`
  - state: `SENT`·`ACCEPTED`·`APPLIED`·`REJECTED`·`UNKNOWN_RESULT`. 결과 ID 는 resultSystem·resultRef 를 같이(하나만 400). `APPLIED`·`REJECTED` 뒤에는 409(다시 하려면 새 요청). `UNKNOWN_RESULT` 는 다시 확인 뒤 다시 남길 수 있다.
- 접수 건 상세 `actionRequests[]`(남긴 순서) — `resultRef: { system, ref, kind, asOf }`, `resultNote`, `requestedBy/At`, `resultBy/At`. 이력에 `ACTION_REQUESTED`·`ACTION_RESULT_RECORDED`.

## 화면 제안
- 접수 건 상세에 "행위 요청" 칸: 목록(종류·대상·상태·결과 ID), "요청 기록" 버튼(종류 고르기 → 대상 서비스·명령·대상 ID, 보상 실행이면 승인 번호 칸 필수), 각 줄에 "결과 기록"(상태 + 결과 ID + 메모).
- 처리 대기에서 "다시 확인"·"처리 완료"를 한 뒤 그 건으로 오면 "요청 기록"을 미리 채워 주면 좋다(선택).
- 문구: "기록만 합니다 — 실제 실행은 처리 대기·각 메뉴에서" 를 버튼 옆에.
