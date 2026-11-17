-- 문의 만족도(2026-11-17 사용자 "답변 뒤 1~5점 + 한 줄" — 업무 원문 Commerce §14.2: 만족도는 상담 평가일 뿐 반품·환불 종결이나
-- 라이더 평가·제재가 아니다). 답변 받은 고객 문의에 남긴 고객이 한 번만. 상담원 평가에 쓰지 않는다 — 상담원에게는 보이지 않고 관리자만 모아 본다.
-- (V12 는 문의 사진 PR #79 — 이 파일은 그 뒤 번호)
CREATE TABLE inquiry_satisfaction (
    inquiry_id   varchar(64)  NOT NULL,
    customer_id  varchar(64)  NOT NULL,
    score        smallint     NOT NULL,
    comment      varchar(200),
    rated_at     timestamptz  NOT NULL,
    CONSTRAINT pk_inquiry_satisfaction PRIMARY KEY (inquiry_id),
    CONSTRAINT fk_inquiry_satisfaction_inquiry FOREIGN KEY (inquiry_id) REFERENCES cs_inquiry (inquiry_id),
    CONSTRAINT ck_inquiry_satisfaction_score CHECK (score BETWEEN 1 AND 5)
);
