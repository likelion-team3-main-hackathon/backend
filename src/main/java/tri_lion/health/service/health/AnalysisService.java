package tri_lion.health.service.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.health.*;
import tri_lion.health.exception.ApiException;
import tri_lion.health.repository.health.HealthRepositories;
import tri_lion.health.repository.user.UserRepositories;
import tri_lion.health.security.AuthenticatedUser;

@Service
public class AnalysisService {
    private final HealthRepositories.Analyses analyses;
    private final HealthRepositories.Documents docs;
    private final HealthRepositories.Jobs jobs;
    private final UserRepositories.Users users;
    private final ObjectMapper json;
    private final AuthenticatedUser auth;
    private final AiRequestLimitService limits;

    public AnalysisService(
            HealthRepositories.Analyses a,
            HealthRepositories.Documents d,
            HealthRepositories.Jobs j,
            UserRepositories.Users u,
            ObjectMapper o,
            AuthenticatedUser au,
            AiRequestLimitService limits) {
        analyses = a;
        docs = d;
        jobs = j;
        users = u;
        json = o;
        auth = au;
        this.limits = limits;
    }

    @Transactional
    public Analysis create(List<Long> ids, String key) {
        Long uid = auth.sensitive().getId();
        if (ids == null) throw new IllegalArgumentException("분석 문서 목록이 필요합니다.");
        users.findForUpdateById(uid).orElseThrow();
        for (Long id : ids)
            docs.findByIdAndUserIdAndDeletedAtIsNull(id, uid)
                    .orElseThrow(() -> ApiException.notFound("건강 문서를 찾을 수 없습니다."));
        limits.lockJobCreation();
        if (key != null) {
            var existing =
                    jobs.findByUserIdAndTypeAndIdempotencyKey(uid, AiJob.Type.HEALTH_ANALYSIS, key);
            if (existing.isPresent())
                return analyses.findById(existing.get().getResultId()).orElseThrow();
        }
        var active =
                jobs.findFirstByUserIdAndTypeAndStatusInOrderByCreatedAtDesc(
                        uid,
                        AiJob.Type.HEALTH_ANALYSIS,
                        List.of(
                                AiJob.Status.PENDING,
                                AiJob.Status.PROCESSING,
                                AiJob.Status.RETRYING));
        if (active.isPresent()) return analyses.findById(active.get().getResultId()).orElseThrow();
        limits.authorizeJob(uid, AiJob.Type.HEALTH_ANALYSIS);
        try {
            Analysis a = analyses.save(new Analysis(uid, json.writeValueAsString(ids)));
            jobs.save(
                    new AiJob(
                            uid,
                            AiJob.Type.HEALTH_ANALYSIS,
                            json.writeValueAsString(Map.of("documentIds", ids)),
                            a.getId(),
                            key));
            return a;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("건강 분석 요청을 구성할 수 없습니다.", exception);
        }
    }

    public Analysis one(Long id) {
        return analyses.findByIdAndUserId(id, auth.sensitive().getId())
                .orElseThrow(() -> ApiException.notFound("건강 분석을 찾을 수 없습니다."));
    }

    public Analysis latest() {
        return analyses.findFirstByUserIdAndStatusOrderByCompletedAtDesc(
                        auth.sensitive().getId(), Analysis.Status.COMPLETED)
                .orElseThrow(() -> ApiException.notFound("완료된 AI 건강 분석이 없습니다."));
    }

    public Page<Analysis> list(String status, int page, int size) {
        Long id = auth.sensitive().getId();
        Pageable p = PageRequest.of(page, Math.min(size, 100));
        return status == null
                ? analyses.findByUserIdOrderByCreatedAtDesc(id, p)
                : analyses.findByUserIdAndStatusOrderByCreatedAtDesc(
                        id, Analysis.Status.valueOf(status), p);
    }
}
