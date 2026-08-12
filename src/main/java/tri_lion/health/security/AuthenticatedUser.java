package tri_lion.health.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tri_lion.health.domain.user.User;
import tri_lion.health.domain.user.UserAgreement;
import tri_lion.health.exception.ApiException;
import tri_lion.health.repository.user.UserRepositories;

@Component
public class AuthenticatedUser {
    private final UserRepositories.Users users;
    private final UserRepositories.Agreements agreements;

    public AuthenticatedUser(UserRepositories.Users users, UserRepositories.Agreements agreements) {
        this.users = users;
        this.agreements = agreements;
    }

    public Long id() {
        Object p = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return Long.valueOf(p.toString());
    }

    public User get() {
        return users.findById(id()).orElseThrow(() -> ApiException.notFound("사용자를 찾을 수 없습니다."));
    }

    public User active() {
        User u = get();
        if (u.getStatus() != User.Status.ACTIVE)
            throw ApiException.forbidden("온보딩을 완료해야 사용할 수 있습니다.");
        return u;
    }

    public User sensitive() {
        User u = active();
        boolean ok =
                agreements
                        .findByUserIdAndType(u.getId(), UserAgreement.Type.SENSITIVE_HEALTH_DATA)
                        .map(UserAgreement::isAgreed)
                        .orElse(false);
        if (!ok) throw ApiException.forbidden("민감 건강정보 처리 동의가 필요합니다.");
        return u;
    }
}
