-- 교대 인수인계(2026-11-16, 계약 0.9.0 — 사용자 "진행해 줘", 업무 원문 BO-27 최대한 그대로)
-- 전임이 집은 접수 건을 건마다 "다음 조치"와 함께 넘기고, 후임이 건별로 수락하면 담당이 바뀐다. 빠진 건은 미인수(관리자 목록).
CREATE TABLE shift_handover (
    handover_id      varchar(64)  PRIMARY KEY,
    group_code       varchar(40)  NOT NULL,   -- 업무(팀): DELIVERY_OPS·ORDER_OPS
    center_id        varchar(64),             -- 선택(본사 팀은 없음)
    from_operator_id varchar(100) NOT NULL,
    to_operator_id   varchar(100) NOT NULL,
    created_by       varchar(100) NOT NULL,   -- 전임 본인 또는 관리자(전임 부재)
    state            varchar(20)  NOT NULL,   -- PENDING·COMPLETED·CANCELLED
    note             varchar(2000),           -- 직원 메모 — 로그 금지
    team_snapshot    jsonb        NOT NULL,   -- 작성 시점 그 팀의 열린 접수 건·후속 처리 요약(CloseSnapshot)
    version          integer      NOT NULL,
    created_at       timestamptz  NOT NULL,
    completed_at     timestamptz,
    cancelled_at     timestamptz,
    cancelled_by     varchar(100)
);
CREATE INDEX ix_shift_handover_state ON shift_handover (state, created_at);
CREATE INDEX ix_shift_handover_to ON shift_handover (to_operator_id, state);
CREATE INDEX ix_shift_handover_from ON shift_handover (from_operator_id, state);

CREATE TABLE shift_handover_item (
    handover_id          varchar(64)  NOT NULL REFERENCES shift_handover (handover_id),
    case_id              varchar(64)  NOT NULL REFERENCES case_record (case_id),
    next_action          varchar(500) NOT NULL,  -- 다음 조치(필수 — 단순 담당자 이름 교체로 끝내지 않는다)
    check_due_at         timestamptz,            -- 확인 기한(선택 — 원문 "기한근거")
    case_state_at        varchar(20)  NOT NULL,  -- 작성 시점 상태
    priority_at          varchar(10),
    open_obligations_at  integer      NOT NULL,
    outcome              varchar(30)  NOT NULL,  -- PENDING·ACCEPTED·EXCLUDED·SKIPPED_CHANGED·SKIPPED_NOT_ALLOWED·CANCELLED
    resolved_at          timestamptz,
    PRIMARY KEY (handover_id, case_id)
);
-- 한 접수 건은 진행 중(PENDING) 인계서 하나에만
CREATE UNIQUE INDEX ux_shift_handover_item_pending ON shift_handover_item (case_id) WHERE outcome = 'PENDING';
