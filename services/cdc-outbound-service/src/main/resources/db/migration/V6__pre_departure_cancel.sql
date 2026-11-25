-- 미출발 취소·축소(0.7.0, 2026-11-25, WMS CDC-F-026) — 피킹 시작 전 범위. 적용된 파일은 수정하지 않는다.
-- TRF 줄에 취소(축소)한 승인량을 남긴다. 승인량 자체는 SCM 정본이라 바꾸지 않는다.
ALTER TABLE transfer_line ADD COLUMN cancelled_qty integer NOT NULL DEFAULT 0;
ALTER TABLE transfer_line ADD CONSTRAINT ck_transfer_line_cancelled CHECK (cancelled_qty >= 0 AND cancelled_qty <= approved_qty);

-- 취소 요청 하나 = 멱등 키 하나. state: PENDING(기록·재고 호출 전) / UNKNOWN(재고 응답 불명 — 같은 본문 재전송이 같은 키로 다시) / APPLIED / REJECTED
CREATE TABLE pre_departure_cancel (
    cancellation_id     varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    source_system       varchar(64)  NOT NULL,
    request_key         varchar(128) NOT NULL,
    body_hash           varchar(64)  NOT NULL,
    scope               varchar(8)   NOT NULL,          -- FULL / PARTIAL
    reason              varchar(32)  NOT NULL,
    source_ref          varchar(128),
    reduce_lines        varchar(4000),                  -- PARTIAL: "SKU=줄일 승인량;…"
    release_lines       varchar(4000),                  -- 재고에 풀 수량 "SKU=수량;…"(FULL 은 비움 = 남은 할당 전량)
    needs_release       boolean      NOT NULL,
    state               varchar(16)  NOT NULL,
    released_qty        integer      NOT NULL DEFAULT 0,
    release_commit_id   varchar(64),
    reject_reason       varchar(300),
    requested_by        varchar(128) NOT NULL,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_pre_departure_cancel PRIMARY KEY (cancellation_id),
    CONSTRAINT uq_pre_departure_cancel_key UNIQUE (trf_id, source_system, request_key)
);
CREATE INDEX ix_pre_departure_cancel_open ON pre_departure_cancel (trf_id) WHERE state IN ('PENDING', 'UNKNOWN');
