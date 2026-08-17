-- Additional chatbot data contracts. Previous Flyway files are intentionally unchanged.
ALTER TABLE routine_items ADD COLUMN intensity VARCHAR(20) NULL;
ALTER TABLE personalized_routines ADD COLUMN paused_until DATE NULL;

CREATE TABLE credit_transactions (
    credit_transaction_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    amount INT NOT NULL,
    balance_after INT NOT NULL,
    reason VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_credit_transaction_user
        FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE INDEX idx_credit_transactions_user_created ON credit_transactions(user_id, created_at);

CREATE TABLE user_notification_settings (
    user_id BIGINT PRIMARY KEY,
    routine_reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    routine_reminder_time TIME NULL,
    marketing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_notification_setting_user
        FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- V13 deliberately exposed section_type and section_title. This forward migration also
-- exposes the new intensity field; older migrations remain untouched for Flyway safety.
CREATE OR REPLACE VIEW ai_routine_item_view AS
SELECT r.user_id,
       r.personalized_routine_id AS routine_id,
       r.title AS routine_title,
       r.goal AS routine_goal,
       r.status AS routine_status,
       r.ai_adjustment_allowed,
       r.paused_until,
       i.routine_item_id,
       i.section_id,
       i.section_type,
       i.section_title,
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
       i.intensity,
       i.status AS item_status
FROM personalized_routines r
JOIN routine_items i
  ON i.personalized_routine_id = r.personalized_routine_id
WHERE r.deleted_at IS NULL
  AND i.deleted_at IS NULL;

CREATE OR REPLACE VIEW ai_health_measurement_view AS
SELECT health_measurement_id, user_id, document_id, category, metric_code, label,
       body_part, body_side, numeric_value, text_value, unit, reference_min,
       reference_max, measured_at, confidence
FROM health_measurements;

CREATE OR REPLACE VIEW ai_health_document_view AS
SELECT document_id, user_id, document_type, measured_at, processing_status,
       extracted_at, created_at
FROM health_documents
WHERE deleted_at IS NULL;

CREATE OR REPLACE VIEW ai_analysis_history_view AS
SELECT analysis_id, user_id, analysis_type, summary, status, progress,
       completed_at, created_at
FROM analyses;

CREATE OR REPLACE VIEW ai_chat_history_view AS
SELECT c.user_id, c.chat_conversation_id, c.title AS conversation_title,
       m.chat_message_id, m.sender_role, m.content, m.response_type, m.created_at
FROM chat_conversations c
JOIN chat_messages m ON m.chat_conversation_id = c.chat_conversation_id
WHERE c.deleted_at IS NULL;

CREATE OR REPLACE VIEW ai_credit_history_view AS
SELECT credit_transaction_id, user_id, amount, balance_after, reason, created_at
FROM credit_transactions;

CREATE OR REPLACE VIEW ai_notification_setting_view AS
SELECT user_id, routine_reminder_enabled, routine_reminder_time, marketing_enabled, updated_at
FROM user_notification_settings;