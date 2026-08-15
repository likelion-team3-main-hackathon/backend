CREATE TABLE ai_request_limit_guard (
  guard_id BIGINT PRIMARY KEY,
  updated_at DATETIME(6) NOT NULL
);

INSERT INTO ai_request_limit_guard(guard_id, updated_at) VALUES (1, CURRENT_TIMESTAMP(6));

CREATE TABLE ai_api_request_events (
  ai_api_request_event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  job_type VARCHAR(50) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_ai_api_event_user
    FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

CREATE INDEX idx_ai_jobs_user_type_created
  ON ai_jobs(user_id, job_type, created_at);

CREATE INDEX idx_ai_api_events_created
  ON ai_api_request_events(created_at);

CREATE INDEX idx_ai_api_events_user_type_created
  ON ai_api_request_events(user_id, job_type, created_at);
