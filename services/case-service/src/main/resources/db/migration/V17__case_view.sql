-- 접수 건 직원별 "마지막으로 본 시각"(2026-11-17 사용자 결정 ① (가) — 도움요청 새 메시지 점의 읽음을 탭이 아니라 서버에).
-- 직원이 접수 건을 열면 화면이 남기고(POST /cases/{id}/views), 목록·상세는 부른 직원의 것만 myLastSeenAt 으로 돌려준다. 사건 상태·이력과 무관.
CREATE TABLE case_view (
    case_id       varchar(64)  NOT NULL,
    operator_id   varchar(128) NOT NULL,
    last_seen_at  timestamptz  NOT NULL,
    CONSTRAINT pk_case_view PRIMARY KEY (case_id, operator_id),
    CONSTRAINT fk_case_view_case FOREIGN KEY (case_id) REFERENCES case_record (case_id)
);
