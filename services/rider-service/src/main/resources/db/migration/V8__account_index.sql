-- 정산 계좌 중복 확인(2026-11-17 — 라이더 앱 요청 §15, 사용자 "중복 불가"). 유일 제약은 두지 않는다(이미 같은 계좌로 들어간 시험 행이 있을 수
-- 있다) — 가입·프로필 변경·가입 전 확인이 이 색인으로 검사한다.
CREATE INDEX ix_rider_bank_account ON rider (bank_code, bank_account_number) WHERE bank_account_number IS NOT NULL;
