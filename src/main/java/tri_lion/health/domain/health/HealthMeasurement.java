package tri_lion.health.domain.health;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.*;

@Entity
@Table(name = "health_measurements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HealthMeasurement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "health_measurement_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "document_id")
    private Long documentId;

    private String category;

    @Column(name = "metric_code")
    private String metricCode;

    private String label;

    @Column(name = "body_part")
    private String bodyPart;

    @Column(name = "body_side")
    private String bodySide;

    @Column(name = "numeric_value")
    private BigDecimal numericValue;

    @Column(name = "text_value")
    private String textValue;

    private String unit;

    @Column(name = "reference_min")
    private BigDecimal referenceMin;

    @Column(name = "reference_max")
    private BigDecimal referenceMax;

    @Column(name = "measured_at")
    private LocalDate measuredAt;

    private BigDecimal confidence;

    @Column(name = "source_text")
    private String sourceText;

    @Column(name = "created_at")
    private Instant createdAt;

    public HealthMeasurement(
            Long userId,
            Long documentId,
            String category,
            String metricCode,
            String label,
            String bodyPart,
            String bodySide,
            BigDecimal numericValue,
            String textValue,
            String unit,
            BigDecimal referenceMin,
            BigDecimal referenceMax,
            LocalDate measuredAt,
            BigDecimal confidence,
            String sourceText) {
        this.userId = userId;
        this.documentId = documentId;
        this.category = category;
        this.metricCode = metricCode;
        this.label = label;
        this.bodyPart = bodyPart;
        this.bodySide = bodySide;
        this.numericValue = numericValue;
        this.textValue = textValue;
        this.unit = unit;
        this.referenceMin = referenceMin;
        this.referenceMax = referenceMax;
        this.measuredAt = measuredAt;
        this.confidence = confidence;
        this.sourceText = sourceText;
        createdAt = Instant.now();
    }

    public void measuredAt(LocalDate value) {
        if (value != null) measuredAt = value;
    }
}
