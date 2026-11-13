-- 센터 관리자 ≠ 작업자(사용자 2026-11-13, PR #3 검토): 인사 등록만으로 작업자를 만들지 않는다.
-- 작업자는 그 센터 MFC_WORKER 권한이 반영될 때(employee-auth GrantApplied, platform.auth.v1) 만든다 — 인사 등록 사건에는 역할이 없다(ADR-0056 결정 4).
-- 센터 이동도 권한으로 따라간다(BO-01: 소속만 바꾸고 A 종료·B 시작 권한을 따로 요청 — 인사 소속 변경 사건은 필요 없다).

-- 인사 사본(직원 사건): 작업자를 만들 때 쓸 고용 형태·사번과 재직 상태. 권한이 인사 등록보다 먼저 와도 기다렸다가 만든다.
CREATE TABLE hr_employee (
    operator_id      varchar(64)  NOT NULL,
    employee_no      varchar(20),
    employment_type  varchar(12),
    status           varchar(10)  NOT NULL,
    hr_version       bigint       NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT pk_hr_employee PRIMARY KEY (operator_id),
    CONSTRAINT ck_hr_employee_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LEFT'))
);

-- 그 센터 MFC_WORKER 권한(GrantApplied 행마다, 기간 [valid_from, valid_to)). 행이 하나라도 있는 작업자 = 권한으로 만든 작업자 —
-- 지금 유효한 권한이 없으면 배정되지 않는다(SUSPENDED). 권한 회수 사건은 아직 없다(employee-auth 요청).
CREATE TABLE mfc_worker_grant (
    grant_id     varchar(100) NOT NULL,
    mfc_id       varchar(32)  NOT NULL,
    operator_id  varchar(64)  NOT NULL,
    valid_from   timestamptz  NOT NULL,
    valid_to     timestamptz,
    applied_at   timestamptz  NOT NULL,
    CONSTRAINT pk_mfc_worker_grant PRIMARY KEY (grant_id, mfc_id)
);
CREATE INDEX ix_mfc_worker_grant ON mfc_worker_grant (mfc_id, operator_id);

-- V14 mfc_worker.hr_version 은 더 쓰지 않는다(사건 버전은 hr_employee.hr_version 한 곳). 기존 값은 그대로 둔다.
COMMENT ON COLUMN mfc_worker.hr_version IS 'V15 부터 쓰지 않음 — hr_employee.hr_version';
