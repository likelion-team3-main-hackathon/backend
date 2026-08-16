CREATE TABLE pose_analyses (
    pose_analysis_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    routine_item_id BIGINT NULL,
    exercise_name VARCHAR(200) NOT NULL,
    image_key VARCHAR(500) NOT NULL,
    pose_score INT NOT NULL,
    detected_issues JSON NOT NULL,
    feedback JSON NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    safety_warning VARCHAR(1000) NULL,
    model_version VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(100) NOT NULL,
    analyzed_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_pose_analysis_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_pose_analysis_routine_item FOREIGN KEY (routine_item_id) REFERENCES routine_items(routine_item_id) ON DELETE SET NULL,
    INDEX idx_pose_analyses_user_analyzed(user_id, analyzed_at)
);
