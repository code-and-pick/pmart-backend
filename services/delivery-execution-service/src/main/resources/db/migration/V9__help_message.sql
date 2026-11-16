-- 도움요청 대화(2026-11-16, 계약 0.6.0 — pmart-clients 라이더 앱 요청 §11-3): 도움요청 한 건에 딸린 라이더 ↔ 지원센터 메시지.
-- sender_type RIDER / SUPPORT, sender_id = 라이더 ID 또는 직원 ID(라이더에게는 직원 ID 를 보이지 않는다). 내용은 로그에 남기지 않는다.
-- seq = 전체 순번(대화 안에서 오름차순) — 앱이 after 로 새 메시지만 받는다.
CREATE TABLE help_message (
    seq          BIGSERIAL     PRIMARY KEY,
    message_id   VARCHAR(64)   NOT NULL UNIQUE,
    delivery_id  VARCHAR(64)   NOT NULL,
    request_id   VARCHAR(128)  NOT NULL,
    sender_type  VARCHAR(8)    NOT NULL CHECK (sender_type IN ('RIDER', 'SUPPORT')),
    sender_id    VARCHAR(128)  NOT NULL,
    body         VARCHAR(1000) NOT NULL,
    photo_refs   JSONB         NOT NULL DEFAULT '[]',
    created_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_help_message_request FOREIGN KEY (delivery_id, request_id) REFERENCES help_request (delivery_id, request_id)
);
CREATE INDEX help_message_thread ON help_message (delivery_id, request_id, seq);
