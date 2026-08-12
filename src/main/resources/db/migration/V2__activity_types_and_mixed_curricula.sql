ALTER TABLE curricula
    ADD COLUMN curriculum_type VARCHAR(30) NOT NULL DEFAULT 'EXERCISE' AFTER category;

CREATE TABLE curriculum_items (
    curriculum_item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    curriculum_id      BIGINT NOT NULL,
    week_number        INT NOT NULL,
    sort_order         INT NOT NULL,
    activity_type      VARCHAR(30) NOT NULL,
    title              VARCHAR(200) NOT NULL,
    description        TEXT,
    scheduled_time     TIME,
    duration_minutes   INT,
    details_json       JSON,
    media_url          VARCHAR(1000),
    created_at         DATETIME(6) NOT NULL,

    CONSTRAINT fk_curriculum_items_curriculum
        FOREIGN KEY (curriculum_id)
        REFERENCES curricula(market_item_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_curriculum_item_order
        UNIQUE (curriculum_id, week_number, sort_order),
    CONSTRAINT chk_curriculum_item_week
        CHECK (week_number > 0),
    CONSTRAINT chk_curriculum_item_order
        CHECK (sort_order > 0),
    CONSTRAINT chk_curriculum_item_duration
        CHECK (duration_minutes IS NULL OR duration_minutes > 0)
);

CREATE INDEX idx_curriculum_items_type
    ON curriculum_items(curriculum_id, activity_type);

CREATE INDEX idx_activity_records_user_type_performed
    ON activity_records(user_id, record_type, performed_at);
