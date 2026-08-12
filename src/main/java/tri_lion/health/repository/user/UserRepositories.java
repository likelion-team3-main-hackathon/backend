package tri_lion.health.repository.user;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import tri_lion.health.domain.user.*;

public final class UserRepositories {
    private UserRepositories() {}

    public interface Users extends JpaRepository<User, Long> {
        Optional<User> findByGoogleUserId(String sub);
    }

    public interface Agreements extends JpaRepository<UserAgreement, Long> {
        Optional<UserAgreement> findByUserIdAndType(Long id, UserAgreement.Type type);

        List<UserAgreement> findByUserId(Long id);
    }

    public interface RefreshSessions extends JpaRepository<RefreshSession, String> {
        Optional<RefreshSession> findByTokenHash(String hash);
    }

    public interface Profiles extends JpaRepository<HealthProfile, Long> {}
}
