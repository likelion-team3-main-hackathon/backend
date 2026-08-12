package tri_lion.health.domain.health;

import jakarta.persistence.*;
import java.io.Serializable;
import lombok.*;

@Entity
@Table(name = "analysis_documents")
@IdClass(AnalysisDocument.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisDocument {
    @Id
    @Column(name = "analysis_id")
    private Long analysisId;

    @Id
    @Column(name = "document_id")
    private Long documentId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long analysisId;
        private Long documentId;
    }
}
