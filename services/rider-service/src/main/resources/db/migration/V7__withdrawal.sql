-- 라이더 탈퇴(2026-11-17 — 라이더 앱 요청 §17, 원문 §8.3). 사용자 결정: 확정 전 수행료·산정 불가·진행 중 배송·열린 도움요청·제한 검토가 있으면
-- 막고, 탈퇴하면 개인정보를 바로 지운다(이름·전화·계좌·거주지·면허 — 재가입 허용). 배송·수행료·제한 이력은 rider_id 로 남는다.
-- 탈퇴한 행은 rider 에 남지만(이력의 주체) 조회·검색·적격에서 빠진다(withdrawn_at IS NOT NULL).
ALTER TABLE rider ALTER COLUMN phone DROP NOT NULL;
ALTER TABLE rider ADD COLUMN withdrawn_at timestamptz;

-- 탈퇴 신청(Idempotency-Key = request_id). REJECTED 는 막은 것(blockers)과 함께 — 같은 키로 다시 내면 다시 검사한다.
CREATE TABLE rider_withdrawal (
    rider_id      varchar(64)  NOT NULL,
    request_id    varchar(128) NOT NULL,
    state         varchar(16)  NOT NULL,
    blockers      jsonb        NOT NULL DEFAULT '[]'::jsonb,
    reason        varchar(300),
    requested_at  timestamptz  NOT NULL,
    completed_at  timestamptz,
    CONSTRAINT pk_rider_withdrawal PRIMARY KEY (rider_id, request_id),
    CONSTRAINT ck_rider_withdrawal_state CHECK (state IN ('PENDING', 'COMPLETED', 'REJECTED'))
);
