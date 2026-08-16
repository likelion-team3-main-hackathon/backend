package tri_lion.health.repository.record;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import tri_lion.health.domain.record.MealAnalysis;

public interface MealAnalysisRepository extends JpaRepository<MealAnalysis, Long> {
    Optional<MealAnalysis> findByIdAndUserId(Long id, Long userId);
}
