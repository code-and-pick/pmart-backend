-- 작업자 명단 ↔ 인사(hr-service) 연결(사용자 결정 2026-11-13 "추천대로", pc-split §1-2):
-- 인사 등록 때 MFC 작업자 자동 생성, 퇴사·정지 자동 반영, 고용 형태 정본 = 인사.
-- hr_version = 마지막으로 반영한 직원 사건의 aggregateVersion(늦게 온 옛 사건 버림). NULL = 인사 사건을 받은 적 없는 작업자(센터 관리자가 직접 등록).
ALTER TABLE mfc_worker ADD COLUMN hr_version bigint;
CREATE INDEX ix_mfc_worker_worker ON mfc_worker (worker_id);
