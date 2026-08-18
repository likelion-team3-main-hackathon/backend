package tri_lion.health.repository.record;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tri_lion.health.domain.record.PoseAnalysis;

public interface PoseAnalysisRepository extends JpaRepository<PoseAnalysis, Long> {
    Optional<PoseAnalysis> findByIdAndUserId(Long id, Long userId);
}
