CREATE VIEW ai_health_summary_view AS
SELECT u.user_id,
       u.health_goal,
       p.height_cm,
       p.weight_kg,
       p.target_weight_kg,
       p.activity_level,
       p.available_exercise_minutes,
       p.exercise_days,
       p.dietary_preferences,
       p.allergies,
       p.disliked_foods,
       p.goals,
       p.injuries,
       p.updated_at
FROM users u
JOIN user_health_profiles p ON p.user_id = u.user_id
WHERE u.status = 'ACTIVE';

CREATE VIEW ai_analysis_summary_view AS
SELECT analysis_id,
       user_id,
       analysis_type,
       summary,
       details,
       completed_at
FROM analyses
WHERE status = 'COMPLETED';

CREATE VIEW ai_routine_item_view AS
SELECT r.user_id,
       r.personalized_routine_id AS routine_id,
       r.title AS routine_title,
       r.goal AS routine_goal,
       r.status AS routine_status,
       r.ai_adjustment_allowed,
       i.routine_item_id,
       i.section_id,
       i.item_type,
       i.title AS item_title,
       i.content,
       i.scheduled_date,
       i.scheduled_at,
       i.target_value,
       i.target_unit,
       i.sets_count,
       i.rest_seconds,
       i.memo,
       i.sequence,
       i.status AS item_status
FROM personalized_routines r
JOIN routine_items i
  ON i.personalized_routine_id = r.personalized_routine_id
WHERE r.deleted_at IS NULL
  AND i.deleted_at IS NULL;

CREATE VIEW ai_activity_record_view AS
SELECT activity_record_id,
       user_id,
       routine_item_id,
       record_type,
       actual_value,
       status,
       details,
       energy_level,
       pain_level,
       condition_memo,
       performed_at
FROM activity_records;

CREATE VIEW ai_health_metric_view AS
SELECT health_record_id,
       user_id,
       metric_type,
       metric_value,
       unit,
       input_source,
       measured_at
FROM health_records;

CREATE VIEW ai_active_curriculum_view AS
SELECT e.user_id,
       e.enrollment_id,
       e.content_id AS curriculum_id,
       e.progress_rate,
       e.started_at,
       m.name AS curriculum_title,
       c.category,
       c.curriculum_type,
       c.difficulty,
       c.duration_days,
       c.description
FROM enrollments e
JOIN curricula c ON c.market_item_id = e.content_id
JOIN market_items m ON m.market_item_id = e.content_id
WHERE e.status = 'ACTIVE';

CREATE VIEW ai_market_product_view AS
SELECT market_item_id,
       name,
       item_type,
       price,
       provider_name,
       image_url,
       purchase_url
FROM market_items
WHERE status IN ('ACTIVE', 'PUBLISHED');

CREATE TABLE pending_ai_actions (
  pending_ai_action_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  method_name VARCHAR(100) NOT NULL,
  arguments_json JSON NOT NULL,
  proposal_message TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  processed_at DATETIME(6),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_pending_ai_action_user
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT chk_pending_ai_action_status
    CHECK (status IN ('PENDING', 'EXECUTED', 'CANCELLED', 'EXPIRED')),
  CONSTRAINT chk_pending_ai_action_expiry
    CHECK (expires_at > created_at)
);

CREATE INDEX idx_pending_ai_actions_user_status
  ON pending_ai_actions(user_id, status, expires_at);
