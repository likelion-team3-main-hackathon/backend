-- 사용자 종속 설정과 루틴 표시 계층을 JSON/속성으로 통합한다.
-- 이 마이그레이션은 기존 데이터를 먼저 복사한 뒤 불필요한 테이블을 제거한다.

ALTER TABLE user_health_profiles ADD COLUMN goals JSON NULL;
ALTER TABLE user_health_profiles ADD COLUMN injuries JSON NULL;

UPDATE user_health_profiles profile
SET profile.goals = COALESCE(
        (SELECT JSON_ARRAYAGG(goal.goal_type)
         FROM user_goals goal
         WHERE goal.user_id = profile.user_id),
        JSON_ARRAY()),
    profile.injuries = COALESCE(
        (SELECT JSON_ARRAYAGG(
                    JSON_OBJECT(
                        'bodyPart', injury.body_part,
                        'description', injury.description))
         FROM user_injuries injury
         WHERE injury.user_id = profile.user_id),
        JSON_ARRAY());

-- MySQL과 H2(MySQL mode)가 공통으로 지원하는 CHECK로 배열 존재를 보장한다.
ALTER TABLE user_health_profiles
    ADD CONSTRAINT chk_health_profile_json_present
    CHECK (goals IS NOT NULL AND injuries IS NOT NULL);

DROP TABLE user_goals;
DROP TABLE user_injuries;

ALTER TABLE analyses
    ADD COLUMN source_document_ids JSON NULL;

UPDATE analyses analysis
SET analysis.source_document_ids = COALESCE(
        (SELECT JSON_ARRAYAGG(link.document_id)
         FROM analysis_documents link
         WHERE link.analysis_id = analysis.analysis_id),
        JSON_ARRAY());

ALTER TABLE analyses
    ADD CONSTRAINT chk_analysis_source_documents_present
    CHECK (source_document_ids IS NOT NULL);

DROP TABLE analysis_documents;

-- 날짜와 구간은 독립 생명주기가 없으므로 routine_items에 평탄화한다.
ALTER TABLE routine_items ADD COLUMN week_number INT NOT NULL DEFAULT 1;
ALTER TABLE routine_items ADD COLUMN day_of_week VARCHAR(20) NULL;
ALTER TABLE routine_items ADD COLUMN scheduled_date DATE NULL;
ALTER TABLE routine_items ADD COLUMN estimated_minutes INT NULL;
ALTER TABLE routine_items ADD COLUMN section_type VARCHAR(40) NULL;
ALTER TABLE routine_items ADD COLUMN section_title VARCHAR(100) NULL;
ALTER TABLE routine_items ADD COLUMN section_order INT NOT NULL DEFAULT 1;

UPDATE routine_items ri
SET week_number = (
        SELECT rd.week_number
        FROM routine_sections rs
        JOIN routine_days rd ON rd.routine_day_id = rs.routine_day_id
        WHERE rs.section_id = ri.section_id),
    day_of_week = (
        SELECT rd.day_of_week
        FROM routine_sections rs
        JOIN routine_days rd ON rd.routine_day_id = rs.routine_day_id
        WHERE rs.section_id = ri.section_id),
    scheduled_date = (
        SELECT rd.scheduled_date
        FROM routine_sections rs
        JOIN routine_days rd ON rd.routine_day_id = rs.routine_day_id
        WHERE rs.section_id = ri.section_id),
    estimated_minutes = (
        SELECT rd.estimated_minutes
        FROM routine_sections rs
        JOIN routine_days rd ON rd.routine_day_id = rs.routine_day_id
        WHERE rs.section_id = ri.section_id),
    section_type = (
        SELECT rs.section_type
        FROM routine_sections rs
        WHERE rs.section_id = ri.section_id),
    section_title = (
        SELECT rs.title
        FROM routine_sections rs
        WHERE rs.section_id = ri.section_id),
    section_order = (
        SELECT rs.sort_order
        FROM routine_sections rs
        WHERE rs.section_id = ri.section_id)
WHERE ri.section_id IS NOT NULL;

ALTER TABLE routine_items DROP FOREIGN KEY fk_item_section;
DROP TABLE routine_sections;
DROP TABLE routine_days;

ALTER TABLE routine_items
    ADD CONSTRAINT chk_routine_item_schedule_present
    CHECK (
        day_of_week IS NOT NULL
        AND scheduled_date IS NOT NULL
        AND section_type IS NOT NULL
        AND section_title IS NOT NULL
    );

CREATE INDEX idx_items_routine_schedule
    ON routine_items(personalized_routine_id, scheduled_date, section_order, sequence);
CREATE INDEX idx_items_section_order
    ON routine_items(personalized_routine_id, section_id, sequence);
-- 새 인덱스가 personalized_routine_id FK를 먼저 받도록 한 뒤 기존 인덱스를 제거한다.
DROP INDEX idx_items_routine_sequence ON routine_items;

-- 현재 인증 모델은 Google 단일 Provider이며 users.google_user_id가 원본 식별자다.
-- 코드에서도 사용하지 않는 중복 테이블을 제거한다.
DROP TABLE social_accounts;
