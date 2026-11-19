-- 카카오·네이버(2026-11-19 — 라이더 앱 요청 R1·R3). 제공자 = GOOGLE·KAKAO·NAVER, 계정 하나 = 제공자 하나(사용자 결정 2026-11-19 "하나만").
-- 같은 휴대폰 번호를 다른 제공자로 가입하려 할 때 안내할 가린 이메일(예 a***@k***) — 원문은 두지 않는다.
ALTER TABLE rider_identity ADD COLUMN email_masked varchar(64);
