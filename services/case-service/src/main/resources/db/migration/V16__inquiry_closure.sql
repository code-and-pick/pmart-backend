-- 문의 닫기(2026-11-17 사용자 "그 표대로 진행해, 다시 열기는 빼고" — 업무 원문 Commerce §14.2-5 "상담 종료 선택은 확인 후 대화 종료로 기록",
-- 상담 종료는 거래 의무 종결이 아니다). 닫는 사람: 문의를 남긴 고객·라이더 본인, 또는 답변 역할 직원(고객 문의 = CS_AGENT, 라이더 문의 = RIDER_OPS —
-- 직원은 사유 필수, 사유는 직원에게만). 자동 닫기·다시 열기 없음. cs_inquiry.status 는 CLOSED 로, 이 표에는 누가·언제·왜.
CREATE TABLE inquiry_closure (
    inquiry_id     varchar(64)  NOT NULL,
    closed_by      varchar(64)  NOT NULL,
    closed_by_type varchar(10)  NOT NULL,
    reason         varchar(500),
    closed_at      timestamptz  NOT NULL,
    CONSTRAINT pk_inquiry_closure PRIMARY KEY (inquiry_id),
    CONSTRAINT fk_inquiry_closure_inquiry FOREIGN KEY (inquiry_id) REFERENCES cs_inquiry (inquiry_id),
    CONSTRAINT ck_inquiry_closure_type CHECK (closed_by_type IN ('CUSTOMER', 'RIDER', 'STAFF')),
    CONSTRAINT ck_inquiry_closure_reason CHECK (closed_by_type <> 'STAFF' OR reason IS NOT NULL)
);
