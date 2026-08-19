package tri_lion.health.service.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.user.*;
import tri_lion.health.dto.request.user.UserRequests;
import tri_lion.health.exception.ApiException;
import tri_lion.health.repository.user.UserRepositories;
import tri_lion.health.security.AuthenticatedUser;

@Service
public class UserService {
    private final AuthenticatedUser auth;
    private final UserRepositories.Users users;
    private final UserRepositories.Agreements agreements;
    private final UserRepositories.Profiles profiles;
    private final ObjectMapper json;

    public UserService(
            AuthenticatedUser a,
            UserRepositories.Users u,
            UserRepositories.Agreements g,
            UserRepositories.Profiles p,
            ObjectMapper o) {
        auth = a;
        users = u;
        agreements = g;
        profiles = p;
        json = o;
    }

    @Transactional
    public void agreements(UserRequests.AgreementsRequest req) {
        User u = auth.get();
        for (var a : req.agreements()) {
            UserAgreement.Type type = UserAgreement.Type.valueOf(a.type());
            var entity =
                    agreements
                            .findByUserIdAndType(u.getId(), type)
                            .orElseGet(() -> new UserAgreement(u.getId(), type, a.agreed()));
            entity.update(a.agreed());
            agreements.save(entity);
        }
        boolean complete =
                List.of(
                                UserAgreement.Type.TERMS_OF_SERVICE,
                                UserAgreement.Type.PRIVACY,
                                UserAgreement.Type.SENSITIVE_HEALTH_DATA)
                        .stream()
                        .allMatch(
                                t ->
                                        agreements
                                                .findByUserIdAndType(u.getId(), t)
                                                .map(UserAgreement::isAgreed)
                                                .orElse(false));
        if (!complete)
            throw new ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "필수 약관에 모두 동의해야 합니다.");
        u.agreementsCompleted();
    }

    @Transactional
    public Instant onboarding(UserRequests.OnboardingRequest r) {
        User u = auth.get();
        if (u.getStatus() != User.Status.ONBOARDING && u.getStatus() != User.Status.ACTIVE)
            throw ApiException.forbidden("약관 동의를 먼저 완료해 주세요.");
        HealthProfile p =
                profiles.findById(u.getId()).orElseGet(() -> new HealthProfile(u.getId()));
        try {
            p.update(
                    r.birthDate(),
                    r.gender(),
                    r.heightCm(),
                    r.weightKg(),
                    r.targetWeightKg(),
                    r.activityLevel(),
                    r.availableExerciseMinutes(),
                    json.writeValueAsString(r.availableExerciseDays()),
                    json.writeValueAsString(r.dietaryPreferences()),
                    json.writeValueAsString(r.allergies()),
                    json.writeValueAsString(r.dislikedFoods()),
                    json.writeValueAsString(r.goals()),
                    json.writeValueAsString(r.injuries()));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        profiles.save(p);
        u.onboardingCompleted(r.name());
        return p.getUpdatedAt();
    }

    public Profile profile() {
        User u = auth.get();
        HealthProfile p = profiles.findById(u.getId()).orElse(null);
        return new Profile(
                u.getId(),
                u.getName(),
                u.getEmail(),
                u.getProfileImageUrl(),
                u.getRole().name(),
                u.getStatus().name(),
                u.isOnboardingCompleted(),
                u.getCreditBalance(),
                p);
    }

    public record Profile(
            Long userId,
            String name,
            String email,
            String profileImageUrl,
            String role,
            String status,
            boolean onboardingCompleted,
            int creditBalance,
            HealthProfile healthProfile) {}
}
