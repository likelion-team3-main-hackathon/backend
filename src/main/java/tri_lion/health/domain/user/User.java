package tri_lion.health.domain.user;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "google_user_id", nullable = false, unique = true)
    private String googleUserId;

    private String email;
    private String name;

    @Column(nullable = false, unique = true)
    private String nickname;

    @Column(name = "profile_image_url")
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "onboarding_completed")
    private boolean onboardingCompleted;

    @Column(name = "credit_balance")
    private int creditBalance;

    @Column(name = "health_goal")
    private String healthGoal;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    public User(String sub, String email, String name, String picture) {
        this.googleUserId = sub;
        this.email = email;
        this.name = name;
        this.profileImageUrl = picture;
        this.nickname = "user-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        this.role = Role.USER;
        this.status = Status.PENDING_TERMS;
        this.creditBalance = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void addCredits(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("크레딧은 양수만 지급할 수 있습니다.");
        creditBalance += amount;
        updatedAt = Instant.now();
    }

    public void agreementsCompleted() {
        status = Status.ONBOARDING;
        updatedAt = Instant.now();
    }

    public void onboardingCompleted(String name) {
        if (name != null && !name.isBlank()) this.name = name;
        this.status = Status.ACTIVE;
        this.onboardingCompleted = true;
        this.updatedAt = Instant.now();
    }

    public enum Role {
        USER,
        EXPERT,
        ADMIN
    }

    public enum Status {
        PENDING_TERMS,
        ONBOARDING,
        ACTIVE,
        SUSPENDED,
        WITHDRAWN
    }
}
