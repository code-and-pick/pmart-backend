-- 일반배차모드 새 배달 알림(2026-11-18 — 사용자 "보냄 — 묶어서"): 라이더마다 마지막으로 알린 때. 1분에 한 번까지, 그 뒤 새로 올라온 배송만 센다.
CREATE TABLE list_notice (
    rider_id     varchar(64)  PRIMARY KEY,
    notified_at  timestamptz  NOT NULL,
    notice_count integer      NOT NULL DEFAULT 0
);
