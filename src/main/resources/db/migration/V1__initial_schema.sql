CREATE TABLE users (
  user_id BIGINT AUTO_INCREMENT PRIMARY KEY, google_user_id VARCHAR(255) NOT NULL,
  email VARCHAR(255), name VARCHAR(100), nickname VARCHAR(100) NOT NULL,
  health_goal TEXT, credit_balance INT NOT NULL DEFAULT 0, profile_image_url TEXT,
  role VARCHAR(30) NOT NULL DEFAULT 'USER', status VARCHAR(30) NOT NULL DEFAULT 'PENDING_TERMS',
  onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE, created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL, withdrawn_at DATETIME(6),
  CONSTRAINT uk_users_google UNIQUE (google_user_id), CONSTRAINT uk_users_nickname UNIQUE (nickname),
  CONSTRAINT chk_users_credit CHECK (credit_balance >= 0)
);
CREATE TABLE social_accounts (
  social_account_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
  provider VARCHAR(30) NOT NULL, provider_user_id VARCHAR(255) NOT NULL,
  provider_email VARCHAR(255), connected_at DATETIME(6) NOT NULL,
  CONSTRAINT uk_social_provider_user UNIQUE(provider, provider_user_id),
  CONSTRAINT fk_social_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE user_agreements (
  agreement_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, type VARCHAR(50) NOT NULL,
  version VARCHAR(30) NOT NULL, agreed BOOLEAN NOT NULL, agreed_at DATETIME(6) NOT NULL,
  CONSTRAINT uk_user_agreement UNIQUE(user_id, type),
  CONSTRAINT fk_agreement_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE refresh_token_sessions (
  session_id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, token_hash VARCHAR(64) NOT NULL,
  expires_at DATETIME(6) NOT NULL, revoked_at DATETIME(6), device_info VARCHAR(255), last_used_at DATETIME(6) NOT NULL,
  CONSTRAINT uk_refresh_hash UNIQUE(token_hash), CONSTRAINT fk_refresh_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE user_health_profiles (
  user_id BIGINT PRIMARY KEY, birth_date DATE, gender VARCHAR(30), height_cm DECIMAL(6,2), weight_kg DECIMAL(6,2),
  target_weight_kg DECIMAL(6,2), activity_level VARCHAR(30), available_exercise_minutes INT,
  exercise_days JSON, dietary_preferences JSON, allergies JSON, disliked_foods JSON, updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_profile_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE user_goals (goal_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, goal_type VARCHAR(50) NOT NULL, target_value DECIMAL(10,2), CONSTRAINT fk_goal_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE);
CREATE TABLE user_injuries (injury_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, body_part VARCHAR(100) NOT NULL, description VARCHAR(500), CONSTRAINT fk_injury_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE);
CREATE TABLE experts (
  user_id BIGINT PRIMARY KEY, specialty VARCHAR(100) NOT NULL, qualification_info TEXT NOT NULL,
  evidence_url VARCHAR(1000), introduction TEXT, verification_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW', applied_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_experts_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE health_records (
  health_record_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, metric_type VARCHAR(50) NOT NULL,
  metric_value DECIMAL(10,2) NOT NULL, unit VARCHAR(30) NOT NULL, input_source VARCHAR(50) NOT NULL, measured_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_health_records_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE health_documents (
  document_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, document_type VARCHAR(40) NOT NULL,
  object_key VARCHAR(500) NOT NULL, original_file_name VARCHAR(255) NOT NULL, content_type VARCHAR(100) NOT NULL,
  size_bytes BIGINT NOT NULL, measured_at DATE, processing_status VARCHAR(30) NOT NULL, created_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
  CONSTRAINT fk_document_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE analyses (
  analysis_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, analysis_type VARCHAR(50) NOT NULL,
  summary TEXT, details JSON, status VARCHAR(30) NOT NULL DEFAULT 'PENDING', progress INT NOT NULL DEFAULT 0,
  failure_reason TEXT, model_version VARCHAR(100), prompt_version VARCHAR(100), completed_at DATETIME(6), created_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_analyses_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE analysis_documents (
  analysis_id BIGINT NOT NULL, document_id BIGINT NOT NULL, PRIMARY KEY(analysis_id, document_id),
  CONSTRAINT fk_ad_analysis FOREIGN KEY(analysis_id) REFERENCES analyses(analysis_id) ON DELETE CASCADE,
  CONSTRAINT fk_ad_document FOREIGN KEY(document_id) REFERENCES health_documents(document_id) ON DELETE CASCADE
);
CREATE TABLE ai_jobs (
  ai_job_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, job_type VARCHAR(50) NOT NULL,
  status VARCHAR(30) NOT NULL, progress INT NOT NULL DEFAULT 0, request_json JSON, result_id BIGINT,
  retry_count INT NOT NULL DEFAULT 0, next_attempt_at DATETIME(6), failure_reason TEXT, idempotency_key VARCHAR(255),
  model_version VARCHAR(100), prompt_version VARCHAR(100), created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  CONSTRAINT uk_ai_job_idempotency UNIQUE(user_id, job_type, idempotency_key),
  CONSTRAINT fk_ai_job_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);
CREATE TABLE market_items (
  market_item_id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(200) NOT NULL, item_type VARCHAR(50) NOT NULL,
  price BIGINT NOT NULL DEFAULT 0, provider_name VARCHAR(200), image_url VARCHAR(1000), purchase_url VARCHAR(1000), status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT chk_market_price CHECK(price >= 0)
);
CREATE TABLE curricula (
  market_item_id BIGINT PRIMARY KEY, expert_id BIGINT NOT NULL, category VARCHAR(100) NOT NULL, difficulty VARCHAR(30),
  duration_days INT, description TEXT, content_url VARCHAR(1000),
  CONSTRAINT fk_curricula_item FOREIGN KEY(market_item_id) REFERENCES market_items(market_item_id) ON DELETE CASCADE,
  CONSTRAINT fk_curricula_expert FOREIGN KEY(expert_id) REFERENCES experts(user_id) ON DELETE CASCADE
);
CREATE TABLE personalized_routines (
  personalized_routine_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, source_curriculum_id BIGINT,
  previous_routine_id BIGINT, title VARCHAR(200) NOT NULL, description TEXT, goal TEXT, type VARCHAR(30) NOT NULL,
  source VARCHAR(40) NOT NULL, start_date DATE NOT NULL, end_date DATE, status VARCHAR(30) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0, ai_adjustment_allowed BOOLEAN NOT NULL DEFAULT TRUE, last_modified_by VARCHAR(30) NOT NULL,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, deleted_at DATETIME(6),
  CONSTRAINT fk_routine_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_routine_curriculum FOREIGN KEY(source_curriculum_id) REFERENCES curricula(market_item_id) ON DELETE SET NULL,
  CONSTRAINT fk_routine_previous FOREIGN KEY(previous_routine_id) REFERENCES personalized_routines(personalized_routine_id) ON DELETE SET NULL
);
CREATE TABLE routine_days (
  routine_day_id BIGINT AUTO_INCREMENT PRIMARY KEY, personalized_routine_id BIGINT NOT NULL, week_number INT NOT NULL,
  day_of_week VARCHAR(20) NOT NULL, scheduled_date DATE NOT NULL, estimated_minutes INT,
  CONSTRAINT fk_day_routine FOREIGN KEY(personalized_routine_id) REFERENCES personalized_routines(personalized_routine_id) ON DELETE CASCADE
);
CREATE TABLE routine_sections (
  section_id BIGINT AUTO_INCREMENT PRIMARY KEY, routine_day_id BIGINT NOT NULL, section_type VARCHAR(40) NOT NULL,
  title VARCHAR(100) NOT NULL, sort_order INT NOT NULL,
  CONSTRAINT fk_section_day FOREIGN KEY(routine_day_id) REFERENCES routine_days(routine_day_id) ON DELETE CASCADE
);
CREATE TABLE routine_items (
  routine_item_id BIGINT AUTO_INCREMENT PRIMARY KEY, personalized_routine_id BIGINT NOT NULL, section_id BIGINT,
  item_type VARCHAR(50) NOT NULL, title VARCHAR(200) NOT NULL, content TEXT, scheduled_at DATETIME(6), target_value DECIMAL(10,2),
  target_unit VARCHAR(30), sets_count INT, rest_seconds INT, video_url VARCHAR(1000), thumbnail_url VARCHAR(1000), memo VARCHAR(500),
  sequence INT NOT NULL, edited_by VARCHAR(30) NOT NULL, exclude_from_ai_adjustment BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING', deleted_at DATETIME(6),
  CONSTRAINT fk_item_routine FOREIGN KEY(personalized_routine_id) REFERENCES personalized_routines(personalized_routine_id) ON DELETE CASCADE,
  CONSTRAINT fk_item_section FOREIGN KEY(section_id) REFERENCES routine_sections(section_id) ON DELETE CASCADE
);
CREATE TABLE activity_records (
  activity_record_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, routine_item_id BIGINT,
  record_type VARCHAR(30) NOT NULL, content TEXT, actual_value DECIMAL(10,2), image_key VARCHAR(500), status VARCHAR(30) NOT NULL,
  details JSON, energy_level INT, pain_level INT, condition_memo VARCHAR(500), performed_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_record_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_record_item FOREIGN KEY(routine_item_id) REFERENCES routine_items(routine_item_id) ON DELETE SET NULL
);
CREATE TABLE coachings (
  coaching_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, trigger_record_id BIGINT,
  type VARCHAR(40) NOT NULL, title VARCHAR(200), message TEXT NOT NULL, actions JSON, safety_level VARCHAR(30), disclaimer TEXT, created_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_coaching_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_coaching_record FOREIGN KEY(trigger_record_id) REFERENCES activity_records(activity_record_id) ON DELETE SET NULL
);
CREATE TABLE enrollments (
  enrollment_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, content_id BIGINT NOT NULL,
  access_type VARCHAR(30) NOT NULL, personalized BOOLEAN NOT NULL, progress_rate DECIMAL(5,2) NOT NULL DEFAULT 0,
  status VARCHAR(30) NOT NULL, started_at DATE NOT NULL,
  CONSTRAINT uk_enrollment UNIQUE(user_id, content_id), CONSTRAINT fk_enroll_user FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_enroll_content FOREIGN KEY(content_id) REFERENCES curricula(market_item_id) ON DELETE CASCADE
);
CREATE INDEX idx_documents_user_created ON health_documents(user_id, created_at);
CREATE INDEX idx_analyses_user_created ON analyses(user_id, created_at);
CREATE INDEX idx_jobs_status_attempt ON ai_jobs(status, next_attempt_at);
CREATE INDEX idx_routines_user_status ON personalized_routines(user_id, status);
CREATE INDEX idx_items_routine_sequence ON routine_items(personalized_routine_id, sequence);
CREATE INDEX idx_records_user_performed ON activity_records(user_id, performed_at);
CREATE UNIQUE INDEX uk_completed_routine_item ON activity_records(routine_item_id, status);
