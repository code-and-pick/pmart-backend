-- 피킹 부족 후속(0.11.0, 2026-11-25 — WMS CDC-F-018, 사용자 결정: 부족 칸은 새 할당에서 뺌·못 찾은 것은 집은 만큼만 보냄). 적용된 파일은 수정하지 않는다.
-- TRF 몫의 부족 하나 = 행 하나. 피킹 실적 트랜잭션에서 만들고, 트랜잭션 밖에서 재고 해제(재계수 대기) → 추가 할당 → 새 작업.
-- state: PENDING(해제 전·불명) → RELEASED(해제됨, 추가 할당 전·불명) → DONE(새 작업·못 찾음 확정) / FAILED(재고 거절 — reason)
CREATE TABLE pick_shortage_followup (
    followup_id         varchar(64)  NOT NULL,
    trf_id              varchar(64)  NOT NULL,
    wave_id             varchar(64)  NOT NULL,
    task_id             varchar(64)  NOT NULL,
    sku                 varchar(10)  NOT NULL,
    lot                 varchar(64)  NOT NULL,
    location_code       varchar(40)  NOT NULL,
    short_qty           integer      NOT NULL,
    state               varchar(16)  NOT NULL,
    reallocated_qty     integer      NOT NULL DEFAULT 0,
    unresolved_qty      integer      NOT NULL DEFAULT 0,
    new_task_ids        varchar(1000),
    reason              varchar(300),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    CONSTRAINT pk_pick_shortage_followup PRIMARY KEY (followup_id),
    CONSTRAINT ck_pick_shortage_followup_state CHECK (state IN ('PENDING', 'RELEASED', 'DONE', 'FAILED')),
    CONSTRAINT ck_pick_shortage_followup_qty CHECK (short_qty > 0 AND reallocated_qty >= 0 AND unresolved_qty >= 0
        AND reallocated_qty + unresolved_qty <= short_qty)
);
CREATE INDEX ix_pick_shortage_followup_open ON pick_shortage_followup (created_at) WHERE state IN ('PENDING', 'RELEASED');
CREATE INDEX ix_pick_shortage_followup_trf ON pick_shortage_followup (trf_id);
