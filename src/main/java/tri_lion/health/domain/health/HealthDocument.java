package tri_lion.health.domain.health;

import jakarta.persistence.*;
import java.time.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "health_documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HealthDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type")
    private Type type;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "measured_at")
    private LocalDate measuredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_data")
    private String extractedData;

    @Column(name = "extraction_model_version")
    private String extractionModelVersion;

    @Column(name = "extraction_prompt_version")
    private String extractionPromptVersion;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    @Column(name = "extraction_failure_reason")
    private String extractionFailureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status")
    private Status processingStatus;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public HealthDocument(Long u, Type t, String k, String n, String c, long s, LocalDate m) {
        userId = u;
        type = t;
        objectKey = k;
        originalFileName = n;
        contentType = c;
        sizeBytes = s;
        measuredAt = m;
        processingStatus = Status.UPLOADED;
        createdAt = Instant.now();
    }

    public void delete() {
        deletedAt = Instant.now();
        processingStatus = Status.DELETED;
    }

    public void processing() {
        processingStatus = Status.PROCESSING;
    }

    public void processed() {
        processingStatus = Status.PROCESSED;
    }

    public void extracted(String data, String model, String prompt) {
        extractedData = data;
        extractionModelVersion = model;
        extractionPromptVersion = prompt;
        extractedAt = Instant.now();
        extractionFailureReason = null;
    }

    public void measuredAt(LocalDate value) {
        if (value != null) measuredAt = value;
    }

    public void fail() {
        processingStatus = Status.FAILED;
    }

    public void extractionFailed(String reason) {
        extractionFailureReason = reason;
        processingStatus = Status.FAILED;
    }

    public enum Type {
        MEDICAL_RECORD,
        PRESCRIPTION,
        INBODY,
        MCC_RESULT,
        BODY_PHOTO,
        OTHER
    }

    public enum Status {
        UPLOADED,
        PROCESSING,
        PROCESSED,
        FAILED,
        DELETED
    }
}
