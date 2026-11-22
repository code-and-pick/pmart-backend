-- 네이버 가린 이메일 잠시 보관(2026-11-22 RA-1 ④ — 고객 BR-77 과 같은 흐름). 네이버 ID 토큰에는 이메일이 없어, 코드 교환 때 프로필 API 의
-- 이메일을 **가려서**(a***@n***) id_token sub 의 SHA-256 에 10분 묶어 두고, 그 sub 로 로그인할 때 rider_identity.email_masked 에 옮기고 지운다.
-- 원문 이메일·원 sub 는 두지 않는다.
CREATE TABLE naver_email_hold (
    subject_hash char(64) PRIMARY KEY,
    email_masked varchar(64) NOT NULL,
    expires_at   timestamptz NOT NULL
);
