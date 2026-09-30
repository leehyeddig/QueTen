-- H2 (MODE=MySQL) 용 스키마. 기동할 때마다 실행되므로 모두 IF NOT EXISTS.
-- MySQL로 옮길 때는 schema-mysql.sql을 만들고 config의 db.schema를 바꾼다.

-- Meta Insights: 캠페인 x 일자 (광고계정 타임존 기준 날짜)
CREATE TABLE IF NOT EXISTS meta_campaign_daily (
    stat_date     DATE          NOT NULL,
    campaign_id   VARCHAR(32)   NOT NULL,
    campaign_name VARCHAR(255),
    currency      CHAR(3),
    spend         DECIMAL(15,2) NOT NULL DEFAULT 0,
    impressions   BIGINT        NOT NULL DEFAULT 0,
    reach         BIGINT        NOT NULL DEFAULT 0,
    clicks        BIGINT        NOT NULL DEFAULT 0,
    link_clicks   BIGINT        NOT NULL DEFAULT 0,
    collected_at  TIMESTAMP     NOT NULL,
    PRIMARY KEY (stat_date, campaign_id)
);

-- Qoo10 주문 (광고 상품 여부와 관계없이 전부 저장, 필터는 조회 시)
CREATE TABLE IF NOT EXISTS qoo10_order (
    order_no           VARCHAR(40)   NOT NULL PRIMARY KEY,
    order_date         TIMESTAMP     NOT NULL,
    order_ymd          DATE          NOT NULL,
    item_code          VARCHAR(40)   NOT NULL,
    quantity           INT           NOT NULL,
    amount             DECIMAL(14,2) NOT NULL,
    order_status       VARCHAR(50),
    first_collected_at TIMESTAMP     NOT NULL,
    last_collected_at  TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_qoo10_order_ymd_item ON qoo10_order (order_ymd, item_code);

-- 소스별 마지막 수집 상태 (시각은 app.zone 기준)
CREATE TABLE IF NOT EXISTS collect_status (
    source          VARCHAR(10)   NOT NULL PRIMARY KEY,
    last_success_at TIMESTAMP,
    last_attempt_at TIMESTAMP     NOT NULL,
    last_error      VARCHAR(1000)
);
