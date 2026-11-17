-- 행위 요청 기록(2026-11-17 사용자 "기록만 먼저" — 업무 원문 BackOffice §2 ActionRequest "상대 결과/commit/거래 ID 와 연결").
-- case-service 는 다른 서비스를 부르지 않는다: 직원이 처리 대기·각 메뉴에서 한 요청(원결과 재조회·미전달 재전송·승인된 보상 실행·도메인 검토 요청)과
-- 그 결과(상태·결과 ID)를 접수 건에 남긴다. (V12 문의 사진 #79, V13 만족도 #83, V14 배송 신고 #82 뒤 번호)
CREATE TABLE case_action_request (
    action_request_id varchar(64)  NOT NULL,
    case_id           varchar(64)  NOT NULL,
    action_type       varchar(40)  NOT NULL,
    target_service    varchar(64)  NOT NULL,
    command           varchar(100) NOT NULL,
    target_ref        varchar(100),
    idempotency_key   varchar(128),
    approval_ref      varchar(100),
    payload_summary   jsonb,
    state             varchar(20)  NOT NULL,
    result_system     varchar(64),
    result_ref        varchar(200),
    result_kind       varchar(20),
    result_note       varchar(500),
    requested_by      varchar(64)  NOT NULL,
    requested_at      timestamptz  NOT NULL,
    result_by         varchar(64),
    result_at         timestamptz,
    version           integer      NOT NULL DEFAULT 0,
    CONSTRAINT pk_case_action_request PRIMARY KEY (action_request_id),
    CONSTRAINT fk_case_action_request_case FOREIGN KEY (case_id) REFERENCES case_record (case_id),
    CONSTRAINT ck_case_action_request_state CHECK (state IN ('REQUESTED', 'SENT', 'ACCEPTED', 'APPLIED', 'REJECTED', 'UNKNOWN_RESULT'))
);
CREATE INDEX ix_case_action_request_case ON case_action_request (case_id, requested_at);
