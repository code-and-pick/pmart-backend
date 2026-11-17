-- 문의 사진(2026-11-17 사용자 "리뷰 방식 그대로" — 업무 원문 Commerce §14.1: 사진은 그 문의에 붙이고 본인·처리 담당만 본다,
-- 첨부 실패는 원래 문의에 보완). 본문은 S3 호환 버킷(pmart-case), 여기에는 메타만. 문의에 붙지 않은 사진은 하루 뒤 정리.
CREATE TABLE inquiry_photo (
    photo_id       varchar(64)  NOT NULL,
    owner_id       varchar(64)  NOT NULL, -- 올린 사람(고객 accountId 또는 라이더 ID) — 문의의 customer_id 와 같아야 붙는다
    object_key     varchar(200) NOT NULL,
    content_type   varchar(32)  NOT NULL,
    size_bytes     bigint       NOT NULL,
    sha256         varchar(64)  NOT NULL,
    uploaded_at    timestamptz  NOT NULL,
    inquiry_id     varchar(64),
    sort_order     integer,
    CONSTRAINT pk_inquiry_photo PRIMARY KEY (photo_id),
    CONSTRAINT fk_inquiry_photo_inquiry FOREIGN KEY (inquiry_id) REFERENCES cs_inquiry (inquiry_id)
);
CREATE INDEX ix_inquiry_photo_inquiry ON inquiry_photo (inquiry_id, sort_order);
CREATE INDEX ix_inquiry_photo_orphan ON inquiry_photo (uploaded_at) WHERE inquiry_id IS NULL;
