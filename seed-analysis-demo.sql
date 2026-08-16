-- 로컬 분석실 전용 시드. 아래 google_user_id의 데모 사용자만 교체하므로 반복 실행할 수 있다.
SET @demo_google_id = 'mcc-analysis-demo';
SET @growth_google_id = 'mcc-analysis-growth-demo';
SET @decline_google_id = 'mcc-analysis-decline-demo';

DELETE FROM users
WHERE google_user_id IN (@demo_google_id, @growth_google_id, @decline_google_id);

INSERT INTO users(
    google_user_id, email, name, nickname, health_goal, credit_balance,
    role, status, onboarding_completed, created_at, updated_at)
VALUES(
    @demo_google_id, 'analysis-demo@mcc.local', '분석실 데모', '분석실데모',
    '체지방 감량과 근육량 유지', 0, 'USER', 'ACTIVE', TRUE,
    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

SET @demo_user_id = LAST_INSERT_ID();

INSERT INTO user_agreements(user_id, type, version, agreed, agreed_at) VALUES
(@demo_user_id, 'TERMS_OF_SERVICE', '1.0', TRUE, CURRENT_TIMESTAMP(6)),
(@demo_user_id, 'PRIVACY', '1.0', TRUE, CURRENT_TIMESTAMP(6)),
(@demo_user_id, 'SENSITIVE_HEALTH_DATA', '1.0', TRUE, CURRENT_TIMESTAMP(6));

INSERT INTO user_health_profiles(
    user_id, birth_date, gender, height_cm, weight_kg, target_weight_kg,
    activity_level, available_exercise_minutes, exercise_days,
    dietary_preferences, allergies, disliked_foods, updated_at, goals, injuries)
VALUES(
    @demo_user_id, '1995-04-12', 'FEMALE', 165.00, 62.40, 59.00,
    'MODERATE', 45, JSON_ARRAY('MONDAY', 'WEDNESDAY', 'SATURDAY'),
    JSON_ARRAY('HIGH_PROTEIN'), JSON_ARRAY(), JSON_ARRAY('셀러리'),
    CURRENT_TIMESTAMP(6), JSON_ARRAY('WEIGHT_LOSS', 'MUSCLE_GAIN'), JSON_ARRAY());

INSERT INTO health_documents(
    user_id, document_type, object_key, original_file_name, content_type,
    size_bytes, measured_at, processing_status, created_at,
    extracted_data, extraction_model_version, extraction_prompt_version, extracted_at)
VALUES(
    @demo_user_id, 'INBODY', CONCAT('demo/', @demo_user_id, '/inbody-1.pdf'),
    'demo-inbody-1.pdf', 'application/pdf', 1024, DATE_SUB(CURDATE(), INTERVAL 60 DAY),
    'PROCESSED', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 60 DAY), JSON_OBJECT('demo', TRUE),
    'demo-seed', 'demo-seed-v1', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 60 DAY));
SET @doc1 = LAST_INSERT_ID();

INSERT INTO health_documents(
    user_id, document_type, object_key, original_file_name, content_type,
    size_bytes, measured_at, processing_status, created_at,
    extracted_data, extraction_model_version, extraction_prompt_version, extracted_at)
VALUES(
    @demo_user_id, 'INBODY', CONCAT('demo/', @demo_user_id, '/inbody-2.pdf'),
    'demo-inbody-2.pdf', 'application/pdf', 1024, DATE_SUB(CURDATE(), INTERVAL 30 DAY),
    'PROCESSED', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY), JSON_OBJECT('demo', TRUE),
    'demo-seed', 'demo-seed-v1', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY));
SET @doc2 = LAST_INSERT_ID();

INSERT INTO health_documents(
    user_id, document_type, object_key, original_file_name, content_type,
    size_bytes, measured_at, processing_status, created_at,
    extracted_data, extraction_model_version, extraction_prompt_version, extracted_at)
VALUES(
    @demo_user_id, 'INBODY', CONCAT('demo/', @demo_user_id, '/inbody-3.pdf'),
    'demo-inbody-3.pdf', 'application/pdf', 1024, CURDATE(),
    'PROCESSED', CURRENT_TIMESTAMP(6), JSON_OBJECT('demo', TRUE),
    'demo-seed', 'demo-seed-v1', CURRENT_TIMESTAMP(6));
SET @doc3 = LAST_INSERT_ID();

INSERT INTO health_measurements(
    user_id, document_id, category, metric_code, label, numeric_value, unit,
    measured_at, confidence, source_text, created_at)
VALUES
(@demo_user_id, @doc1, 'BODY_COMPOSITION', 'WEIGHT_KG', '체중', 64.2, 'kg', DATE_SUB(CURDATE(), INTERVAL 60 DAY), 0.99, '데모 측정값', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc1, 'BODY_COMPOSITION', 'BODY_FAT_PERCENT', '체지방률', 29.4, '%', DATE_SUB(CURDATE(), INTERVAL 60 DAY), 0.99, '데모 측정값', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc1, 'BODY_COMPOSITION', 'SKELETAL_MUSCLE_MASS_KG', '골격근량', 23.1, 'kg', DATE_SUB(CURDATE(), INTERVAL 60 DAY), 0.99, '데모 측정값', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc2, 'BODY_COMPOSITION', 'WEIGHT_KG', '체중', 63.1, 'kg', DATE_SUB(CURDATE(), INTERVAL 30 DAY), 0.99, '데모 측정값', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc2, 'BODY_COMPOSITION', 'BODY_FAT_PERCENT', '체지방률', 28.1, '%', DATE_SUB(CURDATE(), INTERVAL 30 DAY), 0.99, '데모 측정값', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc2, 'BODY_COMPOSITION', 'SKELETAL_MUSCLE_MASS_KG', '골격근량', 23.4, 'kg', DATE_SUB(CURDATE(), INTERVAL 30 DAY), 0.99, '데모 측정값', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'WEIGHT_KG', '체중', 62.4, 'kg', CURDATE(), 0.99, '데모 측정값', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'BODY_FAT_PERCENT', '체지방률', 26.8, '%', CURDATE(), 0.99, '데모 측정값', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'SKELETAL_MUSCLE_MASS_KG', '골격근량', 23.8, 'kg', CURDATE(), 0.99, '데모 측정값', CURRENT_TIMESTAMP(6));

INSERT INTO health_measurements(
    user_id, document_id, category, metric_code, label, body_part, body_side,
    numeric_value, unit, measured_at, confidence, source_text, created_at)
VALUES
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'SEGMENTAL_LEAN_MASS_KG', '부위별 근육량', 'ARM', 'LEFT', 2.18, 'kg', CURDATE(), 0.98, '데모 좌측 팔', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'SEGMENTAL_LEAN_MASS_KG', '부위별 근육량', 'ARM', 'RIGHT', 2.24, 'kg', CURDATE(), 0.98, '데모 우측 팔', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'SEGMENTAL_FAT_MASS_KG', '부위별 지방량', 'ARM', 'LEFT', 1.12, 'kg', CURDATE(), 0.98, '데모 좌측 팔', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'SEGMENTAL_FAT_MASS_KG', '부위별 지방량', 'ARM', 'RIGHT', 1.16, 'kg', CURDATE(), 0.98, '데모 우측 팔', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'SEGMENTAL_LEAN_MASS_KG', '부위별 근육량', 'LEG', 'LEFT', 7.31, 'kg', CURDATE(), 0.98, '데모 좌측 다리', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'SEGMENTAL_LEAN_MASS_KG', '부위별 근육량', 'LEG', 'RIGHT', 7.38, 'kg', CURDATE(), 0.98, '데모 우측 다리', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'SEGMENTAL_FAT_MASS_KG', '부위별 지방량', 'LEG', 'LEFT', 3.02, 'kg', CURDATE(), 0.98, '데모 좌측 다리', CURRENT_TIMESTAMP(6)),
(@demo_user_id, @doc3, 'BODY_COMPOSITION', 'SEGMENTAL_FAT_MASS_KG', '부위별 지방량', 'LEG', 'RIGHT', 3.08, 'kg', CURDATE(), 0.98, '데모 우측 다리', CURRENT_TIMESTAMP(6));

INSERT INTO analyses(
    user_id, analysis_type, source_document_ids, summary, details, status, progress,
    model_version, prompt_version, completed_at, created_at)
VALUES(
    @demo_user_id, 'HEALTH_ANALYSIS', JSON_ARRAY(@doc1, @doc2, @doc3),
    '최근 두 달간 체지방률은 감소하고 골격근량은 완만하게 증가했습니다.',
    JSON_OBJECT(
        'goals', JSON_ARRAY(JSON_OBJECT('type', 'WEIGHT_LOSS', 'description', '체지방률을 안정적으로 낮추기')),
        'precautions', JSON_ARRAY(),
        'nutritionConstraints', JSON_ARRAY('단백질을 매 끼니에 나누어 섭취하세요.'),
        'exerciseConstraints', JSON_ARRAY(),
        'bodyCompositionFindings', JSON_ARRAY(
            JSON_OBJECT('sourceDocumentId', @doc3, 'label', '체중', 'value', 62.4, 'unit', 'kg'),
            JSON_OBJECT('sourceDocumentId', @doc3, 'label', '체지방률', 'value', 26.8, 'unit', '%'),
            JSON_OBJECT('sourceDocumentId', @doc3, 'label', '골격근량', 'value', 23.8, 'unit', 'kg'))),
    'COMPLETED', 100, 'demo-seed', 'demo-seed-v1', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

INSERT INTO personalized_routines(
    user_id, title, description, goal, type, source, start_date, end_date, status,
    version, ai_adjustment_allowed, last_modified_by, created_at, updated_at)
VALUES(
    @demo_user_id, '분석실 4주 데모 루틴', '기간별 운동 분석 확인용 루틴',
    '근육량 유지와 체지방 감량', 'EXERCISE', 'AI', DATE_SUB(CURDATE(), INTERVAL 27 DAY),
    CURDATE(), 'ACTIVE', 0, TRUE, 'AI', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
SET @routine_id = LAST_INSERT_ID();

INSERT INTO routine_items(
    personalized_routine_id, item_type, title, content, target_value, target_unit,
    sets_count, rest_seconds, sequence, edited_by, status, week_number,
    day_of_week, scheduled_date, estimated_minutes, section_type, section_title,
    section_order, muscle_groups)
VALUES
(@routine_id, 'EXERCISE', '전신 스쿼트', '{}', 12, 'REPETITIONS', 3, 60, 1, 'AI', 'COMPLETED', 1, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 27 DAY))), DATE_SUB(CURDATE(), INTERVAL 27 DAY), 25, 'MAIN_EXERCISE', '하체 중심 근력', 1, JSON_ARRAY('LEGS', 'CORE')),
(@routine_id, 'EXERCISE', '밴드 로우', '{}', 12, 'REPETITIONS', 3, 60, 2, 'AI', 'COMPLETED', 1, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 24 DAY))), DATE_SUB(CURDATE(), INTERVAL 24 DAY), 20, 'MAIN_EXERCISE', '등 중심 근력', 1, JSON_ARRAY('BACK', 'ARMS')),
(@routine_id, 'EXERCISE', '인클라인 푸시업', '{}', 10, 'REPETITIONS', 3, 60, 3, 'AI', 'COMPLETED', 2, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 20 DAY))), DATE_SUB(CURDATE(), INTERVAL 20 DAY), 20, 'MAIN_EXERCISE', '상체 밀기', 1, JSON_ARRAY('CHEST', 'ARMS')),
(@routine_id, 'EXERCISE', '런지', '{}', 10, 'REPETITIONS', 4, 60, 4, 'AI', 'COMPLETED', 2, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 17 DAY))), DATE_SUB(CURDATE(), INTERVAL 17 DAY), 25, 'MAIN_EXERCISE', '하체 균형', 1, JSON_ARRAY('LEGS', 'CORE')),
(@routine_id, 'EXERCISE', '숄더 프레스', '{}', 12, 'REPETITIONS', 3, 60, 5, 'AI', 'COMPLETED', 3, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 13 DAY))), DATE_SUB(CURDATE(), INTERVAL 13 DAY), 20, 'MAIN_EXERCISE', '어깨 근력', 1, JSON_ARRAY('SHOULDERS', 'ARMS')),
(@routine_id, 'EXERCISE', '플랭크', '{}', 40, 'SECONDS', 3, 45, 6, 'AI', 'COMPLETED', 3, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 10 DAY))), DATE_SUB(CURDATE(), INTERVAL 10 DAY), 15, 'MAIN_EXERCISE', '코어 안정화', 1, JSON_ARRAY('CORE')),
(@routine_id, 'EXERCISE', '루마니안 데드리프트', '{}', 10, 'REPETITIONS', 4, 75, 7, 'AI', 'COMPLETED', 4, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 6 DAY))), DATE_SUB(CURDATE(), INTERVAL 6 DAY), 25, 'MAIN_EXERCISE', '후면 사슬 근력', 1, JSON_ARRAY('LEGS', 'BACK')),
(@routine_id, 'EXERCISE', '전신 서킷', '{}', 12, 'REPETITIONS', 4, 60, 8, 'AI', 'COMPLETED', 4, UPPER(DAYNAME(CURDATE())), CURDATE(), 30, 'MAIN_EXERCISE', '전신 근력', 1, JSON_ARRAY('FULL_BODY'));

INSERT INTO activity_records(
    user_id, routine_item_id, record_type, status, details, energy_level,
    pain_level, performed_at, created_at)
SELECT
    @demo_user_id, routine_item_id, 'EXERCISE', 'COMPLETED',
    JSON_OBJECT('completed', TRUE, 'totalSets', sets_count, 'minutes', estimated_minutes,
                'calories', estimated_minutes * 6),
    4, 1, TIMESTAMP(scheduled_date, '09:00:00'), TIMESTAMP(scheduled_date, '09:00:00')
FROM routine_items
WHERE personalized_routine_id = @routine_id;

INSERT INTO activity_records(
    user_id, routine_item_id, record_type, status, details, performed_at, created_at)
WITH RECURSIVE demo_days AS (
    SELECT 0 AS day_offset
    UNION ALL
    SELECT day_offset + 1 FROM demo_days WHERE day_offset < 29
)
SELECT
    @demo_user_id, NULL, 'MEAL', 'COMPLETED',
    JSON_OBJECT(
        'completed', TRUE,
        'mealType', 'DAILY_SUMMARY',
        'calories', 1680 + MOD(day_offset, 5) * 35,
        'carbohydrateGrams', 205 + MOD(day_offset, 4) * 6,
        'proteinGrams', 92 + MOD(day_offset, 6) * 3,
        'fatGrams', 48 + MOD(day_offset, 3) * 2,
        'sodiumGrams', 1.8 + MOD(day_offset, 4) * 0.1,
        'fiberGrams', 24 + MOD(day_offset, 5)),
    TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL day_offset DAY), '12:30:00'),
    TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL day_offset DAY), '12:30:00')
FROM demo_days;

SELECT @demo_user_id AS demo_user_id,
       'local:mcc-analysis-demo:analysis-demo@mcc.local:분석실 데모' AS local_id_token;

-- 성장형 데모: 식단 균형과 운동 수행률이 최근으로 갈수록 개선되고 체지방은 감소한다.
INSERT INTO users(
    google_user_id, email, name, nickname, health_goal, credit_balance,
    role, status, onboarding_completed, created_at, updated_at)
VALUES(
    @growth_google_id, 'growth-demo@mcc.local', '성장형 데모', '성장형데모',
    '체지방 감량과 기초체력 향상', 0, 'USER', 'ACTIVE', TRUE,
    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
SET @growth_user_id = LAST_INSERT_ID();

INSERT INTO user_agreements(user_id, type, version, agreed, agreed_at) VALUES
(@growth_user_id, 'TERMS_OF_SERVICE', '1.0', TRUE, CURRENT_TIMESTAMP(6)),
(@growth_user_id, 'PRIVACY', '1.0', TRUE, CURRENT_TIMESTAMP(6)),
(@growth_user_id, 'SENSITIVE_HEALTH_DATA', '1.0', TRUE, CURRENT_TIMESTAMP(6));

INSERT INTO user_health_profiles(
    user_id, birth_date, gender, height_cm, weight_kg, target_weight_kg,
    activity_level, available_exercise_minutes, exercise_days,
    dietary_preferences, allergies, disliked_foods, updated_at, goals, injuries)
VALUES(
    @growth_user_id, '1992-06-18', 'MALE', 176.00, 79.50, 76.00,
    'MODERATE', 50, JSON_ARRAY('TUESDAY', 'THURSDAY', 'SATURDAY'),
    JSON_ARRAY('HIGH_PROTEIN'), JSON_ARRAY(), JSON_ARRAY(), CURRENT_TIMESTAMP(6),
    JSON_ARRAY('WEIGHT_LOSS', 'ENDURANCE'), JSON_ARRAY());

INSERT INTO health_documents(
    user_id, document_type, object_key, original_file_name, content_type, size_bytes,
    measured_at, processing_status, created_at, extracted_data,
    extraction_model_version, extraction_prompt_version, extracted_at)
VALUES
(@growth_user_id, 'INBODY', CONCAT('demo/', @growth_user_id, '/inbody-old.pdf'), 'growth-old.pdf', 'application/pdf', 1024, DATE_SUB(CURDATE(), INTERVAL 60 DAY), 'PROCESSED', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 60 DAY), JSON_OBJECT('demo', TRUE), 'demo-seed', 'demo-seed-v1', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 60 DAY));
SET @growth_doc1 = LAST_INSERT_ID();
INSERT INTO health_documents(
    user_id, document_type, object_key, original_file_name, content_type, size_bytes,
    measured_at, processing_status, created_at, extracted_data,
    extraction_model_version, extraction_prompt_version, extracted_at)
VALUES
(@growth_user_id, 'INBODY', CONCAT('demo/', @growth_user_id, '/inbody-mid.pdf'), 'growth-mid.pdf', 'application/pdf', 1024, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'PROCESSED', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY), JSON_OBJECT('demo', TRUE), 'demo-seed', 'demo-seed-v1', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY));
SET @growth_doc2 = LAST_INSERT_ID();
INSERT INTO health_documents(
    user_id, document_type, object_key, original_file_name, content_type, size_bytes,
    measured_at, processing_status, created_at, extracted_data,
    extraction_model_version, extraction_prompt_version, extracted_at)
VALUES
(@growth_user_id, 'INBODY', CONCAT('demo/', @growth_user_id, '/inbody-latest.pdf'), 'growth-latest.pdf', 'application/pdf', 1024, CURDATE(), 'PROCESSED', CURRENT_TIMESTAMP(6), JSON_OBJECT('demo', TRUE), 'demo-seed', 'demo-seed-v1', CURRENT_TIMESTAMP(6));
SET @growth_doc3 = LAST_INSERT_ID();

INSERT INTO health_measurements(
    user_id, document_id, category, metric_code, label, numeric_value, unit,
    measured_at, confidence, source_text, created_at)
VALUES
(@growth_user_id, @growth_doc1, 'BODY_COMPOSITION', 'WEIGHT_KG', '체중', 82.0, 'kg', DATE_SUB(CURDATE(), INTERVAL 60 DAY), 0.99, '성장형 시작 측정', CURRENT_TIMESTAMP(6)),
(@growth_user_id, @growth_doc1, 'BODY_COMPOSITION', 'BODY_FAT_PERCENT', '체지방률', 31.0, '%', DATE_SUB(CURDATE(), INTERVAL 60 DAY), 0.99, '성장형 시작 측정', CURRENT_TIMESTAMP(6)),
(@growth_user_id, @growth_doc1, 'BODY_COMPOSITION', 'SKELETAL_MUSCLE_MASS_KG', '골격근량', 28.0, 'kg', DATE_SUB(CURDATE(), INTERVAL 60 DAY), 0.99, '성장형 시작 측정', CURRENT_TIMESTAMP(6)),
(@growth_user_id, @growth_doc2, 'BODY_COMPOSITION', 'WEIGHT_KG', '체중', 80.8, 'kg', DATE_SUB(CURDATE(), INTERVAL 30 DAY), 0.99, '성장형 중간 측정', CURRENT_TIMESTAMP(6)),
(@growth_user_id, @growth_doc2, 'BODY_COMPOSITION', 'BODY_FAT_PERCENT', '체지방률', 29.0, '%', DATE_SUB(CURDATE(), INTERVAL 30 DAY), 0.99, '성장형 중간 측정', CURRENT_TIMESTAMP(6)),
(@growth_user_id, @growth_doc2, 'BODY_COMPOSITION', 'SKELETAL_MUSCLE_MASS_KG', '골격근량', 28.6, 'kg', DATE_SUB(CURDATE(), INTERVAL 30 DAY), 0.99, '성장형 중간 측정', CURRENT_TIMESTAMP(6)),
(@growth_user_id, @growth_doc3, 'BODY_COMPOSITION', 'WEIGHT_KG', '체중', 79.5, 'kg', CURDATE(), 0.99, '성장형 최신 측정', CURRENT_TIMESTAMP(6)),
(@growth_user_id, @growth_doc3, 'BODY_COMPOSITION', 'BODY_FAT_PERCENT', '체지방률', 26.8, '%', CURDATE(), 0.99, '성장형 최신 측정', CURRENT_TIMESTAMP(6)),
(@growth_user_id, @growth_doc3, 'BODY_COMPOSITION', 'SKELETAL_MUSCLE_MASS_KG', '골격근량', 29.2, 'kg', CURDATE(), 0.99, '성장형 최신 측정', CURRENT_TIMESTAMP(6));

INSERT INTO analyses(
    user_id, analysis_type, source_document_ids, summary, details, status, progress,
    model_version, prompt_version, completed_at, created_at)
VALUES(
    @growth_user_id, 'HEALTH_ANALYSIS', JSON_ARRAY(@growth_doc1, @growth_doc2, @growth_doc3),
    '최근 두 달간 체지방률이 꾸준히 감소하고 골격근량과 운동 수행량이 증가했습니다.',
    JSON_OBJECT('nutritionConstraints', JSON_ARRAY('현재의 단백질 섭취 습관을 유지하세요.'),
                'exerciseConstraints', JSON_ARRAY('회복일을 유지하며 점진적으로 강도를 높이세요.')),
    'COMPLETED', 100, 'demo-seed', 'demo-seed-v1', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

INSERT INTO personalized_routines(
    user_id, title, description, goal, type, source, start_date, end_date, status,
    version, ai_adjustment_allowed, last_modified_by, created_at, updated_at)
VALUES(
    @growth_user_id, '성장형 4주 루틴', '주차별 수행률 증가 확인용 루틴', '기초체력 향상',
    'EXERCISE', 'AI', DATE_SUB(CURDATE(), INTERVAL 27 DAY), CURDATE(), 'ACTIVE',
    0, TRUE, 'AI', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
SET @growth_routine_id = LAST_INSERT_ID();

INSERT INTO routine_items(
    personalized_routine_id, item_type, title, content, target_value, target_unit,
    sets_count, rest_seconds, sequence, edited_by, status, week_number, day_of_week,
    scheduled_date, estimated_minutes, section_type, section_title, section_order, muscle_groups)
VALUES
(@growth_routine_id, 'EXERCISE', '기초 스쿼트', '{}', 10, 'REPETITIONS', 3, 60, 1, 'AI', 'COMPLETED', 1, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 27 DAY))), DATE_SUB(CURDATE(), INTERVAL 27 DAY), 20, 'MAIN_EXERCISE', '1주차 하체', 1, JSON_ARRAY('LEGS')),
(@growth_routine_id, 'EXERCISE', '밴드 로우', '{}', 10, 'REPETITIONS', 3, 60, 2, 'AI', 'PENDING', 1, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 24 DAY))), DATE_SUB(CURDATE(), INTERVAL 24 DAY), 20, 'MAIN_EXERCISE', '1주차 등', 1, JSON_ARRAY('BACK')),
(@growth_routine_id, 'EXERCISE', '런지', '{}', 10, 'REPETITIONS', 3, 60, 3, 'AI', 'COMPLETED', 2, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 20 DAY))), DATE_SUB(CURDATE(), INTERVAL 20 DAY), 22, 'MAIN_EXERCISE', '2주차 하체', 1, JSON_ARRAY('LEGS')),
(@growth_routine_id, 'EXERCISE', '인클라인 푸시업', '{}', 10, 'REPETITIONS', 3, 60, 4, 'AI', 'PENDING', 2, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 17 DAY))), DATE_SUB(CURDATE(), INTERVAL 17 DAY), 20, 'MAIN_EXERCISE', '2주차 가슴', 1, JSON_ARRAY('CHEST')),
(@growth_routine_id, 'EXERCISE', '덤벨 스쿼트', '{}', 12, 'REPETITIONS', 4, 60, 5, 'AI', 'COMPLETED', 3, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 13 DAY))), DATE_SUB(CURDATE(), INTERVAL 13 DAY), 25, 'MAIN_EXERCISE', '3주차 하체', 1, JSON_ARRAY('LEGS')),
(@growth_routine_id, 'EXERCISE', '원암 로우', '{}', 12, 'REPETITIONS', 4, 60, 6, 'AI', 'COMPLETED', 3, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 10 DAY))), DATE_SUB(CURDATE(), INTERVAL 10 DAY), 25, 'MAIN_EXERCISE', '3주차 등', 1, JSON_ARRAY('BACK')),
(@growth_routine_id, 'EXERCISE', '전신 서킷', '{}', 12, 'REPETITIONS', 4, 60, 7, 'AI', 'COMPLETED', 4, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 6 DAY))), DATE_SUB(CURDATE(), INTERVAL 6 DAY), 30, 'MAIN_EXERCISE', '4주차 전신', 1, JSON_ARRAY('FULL_BODY')),
(@growth_routine_id, 'EXERCISE', '플랭크', '{}', 45, 'SECONDS', 4, 45, 8, 'AI', 'COMPLETED', 4, UPPER(DAYNAME(CURDATE())), CURDATE(), 20, 'MAIN_EXERCISE', '4주차 코어', 1, JSON_ARRAY('CORE'));

INSERT INTO activity_records(
    user_id, routine_item_id, record_type, status, details, energy_level,
    pain_level, performed_at, created_at)
SELECT @growth_user_id, routine_item_id, 'EXERCISE', 'COMPLETED',
       JSON_OBJECT('completed', TRUE, 'totalSets', sets_count, 'minutes', estimated_minutes,
                   'calories', estimated_minutes * 6),
       LEAST(5, 2 + CEIL(sequence / 2)), 1,
       TIMESTAMP(scheduled_date, '09:00:00'), TIMESTAMP(scheduled_date, '09:00:00')
FROM routine_items
WHERE personalized_routine_id = @growth_routine_id AND sequence IN (1, 3, 5, 6, 7, 8);

INSERT INTO activity_records(
    user_id, routine_item_id, record_type, status, details, performed_at, created_at)
WITH RECURSIVE growth_days AS (
    SELECT 0 AS day_offset UNION ALL SELECT day_offset + 1 FROM growth_days WHERE day_offset < 29
)
SELECT @growth_user_id, NULL, 'MEAL', 'COMPLETED',
       JSON_OBJECT(
           'completed', TRUE, 'mealType', 'DAILY_SUMMARY',
           'calories', 2140 - day_offset * 8,
           'carbohydrateGrams', 265 - day_offset * 2.4,
           'proteinGrams', 118 - day_offset * 1.6,
           'fatGrams', 58 + day_offset * 0.9,
           'sodiumGrams', 1.9 + day_offset * 0.035,
           'fiberGrams', 28 - day_offset * 0.35),
       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL day_offset DAY), '12:30:00'),
       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL day_offset DAY), '12:30:00')
FROM growth_days;

-- 퇴보형 데모: 최근으로 갈수록 식단 균형과 운동 수행률이 낮아지고 체성분도 악화된다.
INSERT INTO users(
    google_user_id, email, name, nickname, health_goal, credit_balance,
    role, status, onboarding_completed, created_at, updated_at)
VALUES(
    @decline_google_id, 'decline-demo@mcc.local', '퇴보형 데모', '퇴보형데모',
    '생활 리듬 회복과 체지방 관리', 0, 'USER', 'ACTIVE', TRUE,
    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
SET @decline_user_id = LAST_INSERT_ID();

INSERT INTO user_agreements(user_id, type, version, agreed, agreed_at) VALUES
(@decline_user_id, 'TERMS_OF_SERVICE', '1.0', TRUE, CURRENT_TIMESTAMP(6)),
(@decline_user_id, 'PRIVACY', '1.0', TRUE, CURRENT_TIMESTAMP(6)),
(@decline_user_id, 'SENSITIVE_HEALTH_DATA', '1.0', TRUE, CURRENT_TIMESTAMP(6));

INSERT INTO user_health_profiles(
    user_id, birth_date, gender, height_cm, weight_kg, target_weight_kg,
    activity_level, available_exercise_minutes, exercise_days,
    dietary_preferences, allergies, disliked_foods, updated_at, goals, injuries)
VALUES(
    @decline_user_id, '1988-11-03', 'FEMALE', 168.00, 78.00, 70.00,
    'LIGHT', 35, JSON_ARRAY('MONDAY', 'WEDNESDAY', 'FRIDAY'),
    JSON_ARRAY(), JSON_ARRAY(), JSON_ARRAY('브로콜리'), CURRENT_TIMESTAMP(6),
    JSON_ARRAY('WEIGHT_LOSS', 'HEALTH_MAINTENANCE'), JSON_ARRAY());

INSERT INTO health_documents(
    user_id, document_type, object_key, original_file_name, content_type, size_bytes,
    measured_at, processing_status, created_at, extracted_data,
    extraction_model_version, extraction_prompt_version, extracted_at)
VALUES
(@decline_user_id, 'INBODY', CONCAT('demo/', @decline_user_id, '/inbody-old.pdf'), 'decline-old.pdf', 'application/pdf', 1024, DATE_SUB(CURDATE(), INTERVAL 60 DAY), 'PROCESSED', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 60 DAY), JSON_OBJECT('demo', TRUE), 'demo-seed', 'demo-seed-v1', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 60 DAY));
SET @decline_doc1 = LAST_INSERT_ID();
INSERT INTO health_documents(
    user_id, document_type, object_key, original_file_name, content_type, size_bytes,
    measured_at, processing_status, created_at, extracted_data,
    extraction_model_version, extraction_prompt_version, extracted_at)
VALUES
(@decline_user_id, 'INBODY', CONCAT('demo/', @decline_user_id, '/inbody-mid.pdf'), 'decline-mid.pdf', 'application/pdf', 1024, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'PROCESSED', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY), JSON_OBJECT('demo', TRUE), 'demo-seed', 'demo-seed-v1', DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 30 DAY));
SET @decline_doc2 = LAST_INSERT_ID();
INSERT INTO health_documents(
    user_id, document_type, object_key, original_file_name, content_type, size_bytes,
    measured_at, processing_status, created_at, extracted_data,
    extraction_model_version, extraction_prompt_version, extracted_at)
VALUES
(@decline_user_id, 'INBODY', CONCAT('demo/', @decline_user_id, '/inbody-latest.pdf'), 'decline-latest.pdf', 'application/pdf', 1024, CURDATE(), 'PROCESSED', CURRENT_TIMESTAMP(6), JSON_OBJECT('demo', TRUE), 'demo-seed', 'demo-seed-v1', CURRENT_TIMESTAMP(6));
SET @decline_doc3 = LAST_INSERT_ID();

INSERT INTO health_measurements(
    user_id, document_id, category, metric_code, label, numeric_value, unit,
    measured_at, confidence, source_text, created_at)
VALUES
(@decline_user_id, @decline_doc1, 'BODY_COMPOSITION', 'WEIGHT_KG', '체중', 75.0, 'kg', DATE_SUB(CURDATE(), INTERVAL 60 DAY), 0.99, '퇴보형 시작 측정', CURRENT_TIMESTAMP(6)),
(@decline_user_id, @decline_doc1, 'BODY_COMPOSITION', 'BODY_FAT_PERCENT', '체지방률', 24.0, '%', DATE_SUB(CURDATE(), INTERVAL 60 DAY), 0.99, '퇴보형 시작 측정', CURRENT_TIMESTAMP(6)),
(@decline_user_id, @decline_doc1, 'BODY_COMPOSITION', 'SKELETAL_MUSCLE_MASS_KG', '골격근량', 31.0, 'kg', DATE_SUB(CURDATE(), INTERVAL 60 DAY), 0.99, '퇴보형 시작 측정', CURRENT_TIMESTAMP(6)),
(@decline_user_id, @decline_doc2, 'BODY_COMPOSITION', 'WEIGHT_KG', '체중', 76.3, 'kg', DATE_SUB(CURDATE(), INTERVAL 30 DAY), 0.99, '퇴보형 중간 측정', CURRENT_TIMESTAMP(6)),
(@decline_user_id, @decline_doc2, 'BODY_COMPOSITION', 'BODY_FAT_PERCENT', '체지방률', 26.1, '%', DATE_SUB(CURDATE(), INTERVAL 30 DAY), 0.99, '퇴보형 중간 측정', CURRENT_TIMESTAMP(6)),
(@decline_user_id, @decline_doc2, 'BODY_COMPOSITION', 'SKELETAL_MUSCLE_MASS_KG', '골격근량', 30.4, 'kg', DATE_SUB(CURDATE(), INTERVAL 30 DAY), 0.99, '퇴보형 중간 측정', CURRENT_TIMESTAMP(6)),
(@decline_user_id, @decline_doc3, 'BODY_COMPOSITION', 'WEIGHT_KG', '체중', 78.0, 'kg', CURDATE(), 0.99, '퇴보형 최신 측정', CURRENT_TIMESTAMP(6)),
(@decline_user_id, @decline_doc3, 'BODY_COMPOSITION', 'BODY_FAT_PERCENT', '체지방률', 28.5, '%', CURDATE(), 0.99, '퇴보형 최신 측정', CURRENT_TIMESTAMP(6)),
(@decline_user_id, @decline_doc3, 'BODY_COMPOSITION', 'SKELETAL_MUSCLE_MASS_KG', '골격근량', 29.8, 'kg', CURDATE(), 0.99, '퇴보형 최신 측정', CURRENT_TIMESTAMP(6));

INSERT INTO analyses(
    user_id, analysis_type, source_document_ids, summary, details, status, progress,
    model_version, prompt_version, completed_at, created_at)
VALUES(
    @decline_user_id, 'HEALTH_ANALYSIS', JSON_ARRAY(@decline_doc1, @decline_doc2, @decline_doc3),
    '최근 두 달간 체지방률과 체중이 증가하고 골격근량과 운동 수행량이 감소했습니다.',
    JSON_OBJECT('nutritionConstraints', JSON_ARRAY('단백질과 식이섬유 섭취를 우선 회복하세요.'),
                'exerciseConstraints', JSON_ARRAY('낮은 강도부터 규칙성을 다시 만드세요.')),
    'COMPLETED', 100, 'demo-seed', 'demo-seed-v1', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

INSERT INTO personalized_routines(
    user_id, title, description, goal, type, source, start_date, end_date, status,
    version, ai_adjustment_allowed, last_modified_by, created_at, updated_at)
VALUES(
    @decline_user_id, '퇴보형 4주 루틴', '주차별 수행률 감소 확인용 루틴', '생활 리듬 회복',
    'EXERCISE', 'AI', DATE_SUB(CURDATE(), INTERVAL 27 DAY), CURDATE(), 'ACTIVE',
    0, TRUE, 'AI', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
SET @decline_routine_id = LAST_INSERT_ID();

INSERT INTO routine_items(
    personalized_routine_id, item_type, title, content, target_value, target_unit,
    sets_count, rest_seconds, sequence, edited_by, status, week_number, day_of_week,
    scheduled_date, estimated_minutes, section_type, section_title, section_order, muscle_groups)
VALUES
(@decline_routine_id, 'EXERCISE', '전신 스쿼트', '{}', 12, 'REPETITIONS', 4, 60, 1, 'AI', 'COMPLETED', 1, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 27 DAY))), DATE_SUB(CURDATE(), INTERVAL 27 DAY), 28, 'MAIN_EXERCISE', '1주차 하체', 1, JSON_ARRAY('LEGS')),
(@decline_routine_id, 'EXERCISE', '밴드 로우', '{}', 12, 'REPETITIONS', 4, 60, 2, 'AI', 'COMPLETED', 1, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 24 DAY))), DATE_SUB(CURDATE(), INTERVAL 24 DAY), 25, 'MAIN_EXERCISE', '1주차 등', 1, JSON_ARRAY('BACK')),
(@decline_routine_id, 'EXERCISE', '런지', '{}', 10, 'REPETITIONS', 4, 60, 3, 'AI', 'COMPLETED', 2, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 20 DAY))), DATE_SUB(CURDATE(), INTERVAL 20 DAY), 25, 'MAIN_EXERCISE', '2주차 하체', 1, JSON_ARRAY('LEGS')),
(@decline_routine_id, 'EXERCISE', '푸시업', '{}', 10, 'REPETITIONS', 4, 60, 4, 'AI', 'COMPLETED', 2, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 17 DAY))), DATE_SUB(CURDATE(), INTERVAL 17 DAY), 23, 'MAIN_EXERCISE', '2주차 가슴', 1, JSON_ARRAY('CHEST')),
(@decline_routine_id, 'EXERCISE', '라이트 스쿼트', '{}', 10, 'REPETITIONS', 3, 60, 5, 'AI', 'COMPLETED', 3, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 13 DAY))), DATE_SUB(CURDATE(), INTERVAL 13 DAY), 20, 'MAIN_EXERCISE', '3주차 하체', 1, JSON_ARRAY('LEGS')),
(@decline_routine_id, 'EXERCISE', '라이트 로우', '{}', 10, 'REPETITIONS', 3, 60, 6, 'AI', 'PENDING', 3, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 10 DAY))), DATE_SUB(CURDATE(), INTERVAL 10 DAY), 20, 'MAIN_EXERCISE', '3주차 등', 1, JSON_ARRAY('BACK')),
(@decline_routine_id, 'EXERCISE', '걷기 서킷', '{}', 20, 'MINUTES', 3, 45, 7, 'AI', 'PENDING', 4, UPPER(DAYNAME(DATE_SUB(CURDATE(), INTERVAL 6 DAY))), DATE_SUB(CURDATE(), INTERVAL 6 DAY), 20, 'MAIN_EXERCISE', '4주차 전신', 1, JSON_ARRAY('FULL_BODY')),
(@decline_routine_id, 'EXERCISE', '짧은 플랭크', '{}', 20, 'SECONDS', 3, 45, 8, 'AI', 'PENDING', 4, UPPER(DAYNAME(CURDATE())), CURDATE(), 12, 'MAIN_EXERCISE', '4주차 코어', 1, JSON_ARRAY('CORE'));

INSERT INTO activity_records(
    user_id, routine_item_id, record_type, status, details, energy_level,
    pain_level, performed_at, created_at)
SELECT @decline_user_id, routine_item_id, 'EXERCISE', 'COMPLETED',
       JSON_OBJECT('completed', TRUE, 'totalSets', sets_count, 'minutes', estimated_minutes,
                   'calories', estimated_minutes * 5),
       GREATEST(2, 5 - CEIL(sequence / 2)), 2,
       TIMESTAMP(scheduled_date, '09:00:00'), TIMESTAMP(scheduled_date, '09:00:00')
FROM routine_items
WHERE personalized_routine_id = @decline_routine_id AND sequence IN (1, 2, 3, 4, 5);

INSERT INTO activity_records(
    user_id, routine_item_id, record_type, status, details, performed_at, created_at)
WITH RECURSIVE decline_days AS (
    SELECT 0 AS day_offset UNION ALL SELECT day_offset + 1 FROM decline_days WHERE day_offset < 29
)
SELECT @decline_user_id, NULL, 'MEAL', 'COMPLETED',
       JSON_OBJECT(
           'completed', TRUE, 'mealType', 'DAILY_SUMMARY',
           'calories', 2500 - day_offset * 22,
           'carbohydrateGrams', 335 - day_offset * 4.8,
           'proteinGrams', 62 + day_offset * 1.8,
           'fatGrams', 98 - day_offset * 1.3,
           'sodiumGrams', 3.8 - day_offset * 0.06,
           'fiberGrams', 13 + day_offset * 0.4),
       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL day_offset DAY), '20:30:00'),
       TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL day_offset DAY), '20:30:00')
FROM decline_days;

SELECT @growth_user_id AS growth_demo_user_id,
       'local:mcc-analysis-growth-demo:growth-demo@mcc.local:성장형 데모' AS local_id_token;
SELECT @decline_user_id AS decline_demo_user_id,
       'local:mcc-analysis-decline-demo:decline-demo@mcc.local:퇴보형 데모' AS local_id_token;
