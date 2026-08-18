CREATE TABLE meal_analyses (
    meal_analysis_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    routine_item_id BIGINT NULL,
    image_key VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL,
    foods JSON NOT NULL,
    totals JSON NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    confirmed_record_id BIGINT NULL,
    recorded_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_meal_analysis_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_meal_analysis_routine_item FOREIGN KEY (routine_item_id) REFERENCES routine_items(routine_item_id) ON DELETE SET NULL,
    CONSTRAINT fk_meal_analysis_record FOREIGN KEY (confirmed_record_id) REFERENCES activity_records(activity_record_id) ON DELETE SET NULL,
    INDEX idx_meal_analyses_user_created(user_id, created_at)
);
