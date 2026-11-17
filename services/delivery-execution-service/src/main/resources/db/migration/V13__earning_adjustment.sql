-- 산정 불가 수행료 조정(2026-11-17 — 사용자: 라이더 운영·관리자가, 귀책에 따라 지급/미지급, 지급이면 거리별 금액(수락 당시 금액),
-- 바로 확정·조정일 + 3영업일 지급). 조정 한 번 = 한 줄. 사유는 직원만 본다(라이더에게는 결과만).
CREATE TABLE earning_adjustment (
    adjustment_id  varchar(64)  NOT NULL,
    earning_id     varchar(64)  NOT NULL,
    delivery_id    varchar(64)  NOT NULL,
    rider_id       varchar(64)  NOT NULL,
    fault          varchar(16)  NOT NULL,
    decision       varchar(16)  NOT NULL,
    amount         integer      NOT NULL,
    reason         varchar(300) NOT NULL,
    adjusted_by    varchar(64)  NOT NULL,
    adjusted_at    timestamptz  NOT NULL,
    CONSTRAINT pk_earning_adjustment PRIMARY KEY (adjustment_id),
    CONSTRAINT uq_earning_adjustment_earning UNIQUE (earning_id),
    CONSTRAINT ck_earning_adjustment_fault CHECK (fault IN ('RIDER', 'CUSTOMER', 'STORE', 'PMART')),
    CONSTRAINT ck_earning_adjustment_decision CHECK (decision IN ('PAY', 'NO_PAY'))
);
CREATE INDEX ix_rider_earning_needs_adjustment ON rider_earning (recorded_at)
    WHERE NOT calculable AND settlement_state NOT IN ('ADJUSTED', 'PAYING', 'PAID');
