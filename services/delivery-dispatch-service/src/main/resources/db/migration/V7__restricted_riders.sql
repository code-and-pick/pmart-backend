-- 계정 제한 라이더 사본(RS-1, 2026-11-14). rider RiderRestricted 를 받을 때 라이더 잠금 아래 기록한다(풀기 NORMAL 이면 지움).
-- 수락(제안 응답·배달리스트)은 같은 라이더 잠금 아래 이 표를 다시 본다 — rider 조회는 트랜잭션 밖이라
-- 제한 커밋 직전 조회 + 제한 처리 뒤 수락이 겹쳐도 배정되지 않게(사용자 2026-11-14 "픽업 전이면 배차 취소").
CREATE TABLE restricted_rider (
    rider_id     varchar(64)  NOT NULL,
    restriction  varchar(24)  NOT NULL,
    until_at     timestamptz,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT pk_restricted_rider PRIMARY KEY (rider_id)
);
