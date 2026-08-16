ALTER TABLE health_documents
    ADD COLUMN extracted_data JSON NULL;

ALTER TABLE health_documents
    ADD COLUMN extraction_model_version VARCHAR(100) NULL;

ALTER TABLE health_documents
    ADD COLUMN extraction_prompt_version VARCHAR(100) NULL;

ALTER TABLE health_documents
    ADD COLUMN extracted_at DATETIME(6) NULL;

ALTER TABLE health_documents
    ADD COLUMN extraction_failure_reason TEXT NULL;

CREATE TABLE health_measurements (
    health_measurement_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    category VARCHAR(40) NOT NULL,
    metric_code VARCHAR(80) NOT NULL,
    label VARCHAR(200) NOT NULL,
    body_part VARCHAR(40) NULL,
    body_side VARCHAR(20) NULL,
    numeric_value DECIMAL(14,4) NULL,
    text_value VARCHAR(500) NULL,
    unit VARCHAR(40) NULL,
    reference_min DECIMAL(14,4) NULL,
    reference_max DECIMAL(14,4) NULL,
    measured_at DATE NULL,
    confidence DECIMAL(5,4) NULL,
    source_text VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_health_measurement_user
        FOREIGN KEY(user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_health_measurement_document
        FOREIGN KEY(document_id) REFERENCES health_documents(document_id) ON DELETE CASCADE,
    CONSTRAINT uk_health_measurement_document_metric
        UNIQUE(document_id, metric_code, body_part, body_side)
);

ALTER TABLE routine_items
    ADD COLUMN muscle_groups JSON NULL;

CREATE INDEX idx_measurements_user_category_date
    ON health_measurements(user_id, category, measured_at);

CREATE INDEX idx_measurements_user_metric_date
    ON health_measurements(user_id, metric_code, measured_at);

CREATE INDEX idx_records_user_type_date
    ON activity_records(user_id, record_type, performed_at);
