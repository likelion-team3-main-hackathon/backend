package tri_lion.health.domain.user;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "user_agreements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAgreement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "agreement_id")
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    private Type type;

    private String version;
    private boolean agreed;

    @Column(name = "agreed_at")
    private Instant agreedAt;

    public UserAgreement(Long userId, Type type, boolean agreed) {
        this.userId = userId;
        this.type = type;
        this.agreed = agreed;
        this.version = "1.0";
        this.agreedAt = Instant.now();
    }

    public void update(boolean agreed) {
        this.agreed = agreed;
        this.agreedAt = Instant.now();
    }

    public enum Type {
        TERMS_OF_SERVICE,
        PRIVACY,
        SENSITIVE_HEALTH_DATA,
        MARKETING
    }
}
