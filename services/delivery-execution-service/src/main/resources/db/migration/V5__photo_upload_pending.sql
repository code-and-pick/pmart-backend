-- 증빙 사진 올리는 중 기록(2026-11-14, PC 1 PR #12 검토 후속, 사용자 결정 "5시간이 지나면 삭제").
-- 저장소에 올리기 전에 남기고, 사진 참조(evidence_photo)를 저장하는 트랜잭션에서 지운다. 실패한 요청은 객체를 바로 지우지 않는다 —
-- 같은 요청이 동시에 와 다른 쪽이 같은 객체 키로 성공했을 수 있어서(바로 지우면 참조는 있는데 파일이 없는 사진이 된다).
-- 시작 뒤 orphan-photo-after(5시간)가 지나도 남은 기록은 PhotoSweeper 가 참조가 없을 때만 객체를 지운다.
CREATE TABLE photo_upload (
    object_key   VARCHAR(256) PRIMARY KEY,
    delivery_id  VARCHAR(64)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX photo_upload_started ON photo_upload (started_at);
CREATE INDEX evidence_photo_object_key ON evidence_photo (object_key);
