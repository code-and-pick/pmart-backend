-- MFC 반납 인증번호(2026-11-18 사용자 "직원이나 어딘가에 뜨는 인증코드(번호)를 입력해야 전달완료가 되도록").
-- 회수·반납 배송마다 4자리, 반납까지 유효, 5번 틀리면 잠김(직원이 새로 내면 풀림). 라이더 반납 중 행 잠금과 엉키지 않게 외래키는 두지 않는다.
CREATE TABLE return_handover_code (
    delivery_id      varchar(64)  PRIMARY KEY,
    code             char(4)      NOT NULL,
    issued_at        timestamptz  NOT NULL,
    failed_attempts  integer      NOT NULL DEFAULT 0,
    locked_at        timestamptz,
    used_at          timestamptz
);
