package tri_lion.health.repository.user;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import tri_lion.health.domain.user.*;

public final class UserRepositories {
    private UserRepositories() {}

    public interface Users extends JpaRepository<User, Long> {
        Optional<User> findByGoogleUserId(String sub);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select u from User u where u.id=:id")
        Optional<User> findForUpdateById(@Param("id") Long id);
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
