-- Adds fields already selected by AiReadToolService.
-- Keep V5 unchanged to avoid a Flyway checksum mismatch in existing databases.
CREATE OR REPLACE VIEW ai_routine_item_view AS
SELECT r.user_id,
       r.personalized_routine_id AS routine_id,
       r.title AS routine_title,
       r.goal AS routine_goal,
       r.status AS routine_status,
       r.ai_adjustment_allowed,
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
       i.status AS item_status
FROM personalized_routines r
JOIN routine_items i
  ON i.personalized_routine_id = r.personalized_routine_id
WHERE r.deleted_at IS NULL
  AND i.deleted_at IS NULL;
