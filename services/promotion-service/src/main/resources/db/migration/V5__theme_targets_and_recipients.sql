-- 0.3.0(2026-11-24 — 사용자 "행사 대상에 테마도 추가", "직원 지정 발급 … 고객 id 를 넣는 칸", 세부 "추천안대로 진행").

-- ① 진열 테마 사본: storefront /internal/storefront/themes 를 1분마다 받아 통째로 바꾼다(ThemeSync).
--    기간 할인 대상 THEME = 이 사본에서 그 테마에 든 상품의 모든 SKU(sku_product 사본으로) — 테마가 빠지면 할인도 멈춘다.
CREATE TABLE theme_copy (
    section_id      varchar(40)  NOT NULL,
    kind            varchar(16)  NOT NULL,      -- THEME / SEASON / WEEKLY_PICK / RANKING_AUTO
    title           varchar(100) NOT NULL,
    CONSTRAINT pk_theme_copy PRIMARY KEY (section_id)
);
CREATE TABLE theme_copy_product (
    section_id      varchar(40)  NOT NULL,
    product_id      varchar(40)  NOT NULL,
    CONSTRAINT pk_theme_copy_product PRIMARY KEY (section_id, product_id)
);
CREATE INDEX ix_theme_copy_product_product ON theme_copy_product (product_id);
-- 받아 둔 진열 버전(한 줄). 버전이 같으면 바꾸지 않는다.
CREATE TABLE theme_copy_state (
    id              smallint     NOT NULL,
    layout_version  integer      NOT NULL,
    synced_at       timestamptz  NOT NULL,
    CONSTRAINT pk_theme_copy_state PRIMARY KEY (id),
    CONSTRAINT ck_theme_copy_state_one CHECK (id = 1)
);
CREATE INDEX ix_promotion_target_theme ON promotion_target (target_id) WHERE target_type = 'THEME';

-- ② 지정 발급 쿠폰의 받을 고객(만들 때 정함). 승인 뒤 시작 시각이 되면 RecipientGrants 가 발급하고 결과를 남긴다.
ALTER TABLE promotion ADD COLUMN recipient_reason varchar(200);
CREATE TABLE promotion_recipient (
    promotion_id    varchar(40)  NOT NULL,
    account_id      varchar(64)  NOT NULL,
    seq             integer      NOT NULL,      -- 넣은 순서
    result          varchar(16),                -- NULL = 아직 / ISSUED / SKIPPED_LIMIT / SOLD_OUT
    coupon_id       varchar(40),
    processed_at    timestamptz,
    CONSTRAINT pk_promotion_recipient PRIMARY KEY (promotion_id, account_id),
    CONSTRAINT fk_promotion_recipient FOREIGN KEY (promotion_id) REFERENCES promotion (promotion_id),
    CONSTRAINT ck_promotion_recipient_result CHECK (result IS NULL OR result IN ('ISSUED', 'SKIPPED_LIMIT', 'SOLD_OUT'))
);
CREATE INDEX ix_promotion_recipient_pending ON promotion_recipient (promotion_id) WHERE result IS NULL;
