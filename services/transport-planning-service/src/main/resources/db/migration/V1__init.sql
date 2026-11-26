-- transport-planning-service 스키마 초기화. 공통(outbox_event, inbox_event)은 common-messaging 의 V0_0_1 이 만든다.
-- 테이블은 업무 구현(4단계)에서 V2 이후로 추가한다. 이미 적용된 파일은 수정하지 않는다.
-- 서비스 간 DB JOIN·FK·직접 테이블 접근 금지 (service-architecture.md).
SELECT 1;
