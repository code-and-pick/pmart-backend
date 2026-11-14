-- 품질검사·적치 작업도 다음 작업 받기(mfc-capacity)로 배정한다(클라이언트 BR-52 R2, 사용자 결정 2026-11-14 "담당만 실행").
-- 담당은 AssignmentPublished 투영(더 새 배정 버전만) — 이동 지시(V8)와 같다. 담당이 있으면 담당만 실행.
ALTER TABLE inbound_task ADD COLUMN worker_id varchar(64);
ALTER TABLE inbound_task ADD COLUMN assignment_version integer NOT NULL DEFAULT 0;
