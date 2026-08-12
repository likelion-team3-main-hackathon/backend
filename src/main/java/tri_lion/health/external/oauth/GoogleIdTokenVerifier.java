package tri_lion.health.external.oauth;

public interface GoogleIdTokenVerifier {
    GoogleUserInfo verify(String token);

    record GoogleUserInfo(
            String sub, String email, boolean emailVerified, String name, String picture) {}
}
