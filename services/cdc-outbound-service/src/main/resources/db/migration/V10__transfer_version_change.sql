-- TRF 새 승인 버전 대체(0.13.0, 2026-11-25 — WMS CWC-03 TransferChanged, 사용자 "피킹 시작 전까지"). 적용된 파일은 수정하지 않는다.
-- 대체 중(CHANGING·CHANGE_HOLD)에는 예전 버전 할당을 여기 두고, 해제가 끝나면 비운 뒤 새 버전으로 다시 할당한다.
ALTER TABLE transfer ADD COLUMN superseded_allocation_id varchar(64);
