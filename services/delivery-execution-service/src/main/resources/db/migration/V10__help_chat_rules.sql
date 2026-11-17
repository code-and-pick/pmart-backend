-- 도움요청 대화 보완(DE-2, 2026-11-16 사용자 "진행해" — PC 2 추천대로, 백오피스 요청).
-- help_request.case_group: 접수 건의 지금 팀(case CaseTransferred toGroup). NULL = 지원센터(DELIVERY_OPS). ORDER_OPS 면 관리자만 답장.
-- help_message 관리자 가림: 글을 지우고(body NULL) 누가·언제·왜는 남긴다(case 메모 가림과 같게, ADR-0062). 사유는 로그 금지.
ALTER TABLE help_request ADD COLUMN case_group varchar(20);
ALTER TABLE help_message ALTER COLUMN body DROP NOT NULL;
ALTER TABLE help_message ADD COLUMN masked_at timestamptz;
ALTER TABLE help_message ADD COLUMN masked_by varchar(128);
ALTER TABLE help_message ADD COLUMN mask_reason varchar(500);
