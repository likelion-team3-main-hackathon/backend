package tri_lion.health.external.oauth;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import tri_lion.health.exception.ApiException;

@Configuration
public class GoogleVerifierAdapters {
    @Bean @Profile({"local","test"}) GoogleIdTokenVerifier fakeGoogleVerifier(){
        return token -> {
            if(token==null || !token.startsWith("local:")) throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Google ID Token을 확인할 수 없습니다.");
            String[] p=token.split(":",4); if(p.length<2) throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Google ID Token을 확인할 수 없습니다.");
            return new GoogleIdTokenVerifier.GoogleUserInfo(p[1],p.length>2?p[2]:null,true,p.length>3?p[3]:"Local User",null);
        };
    }
    @Bean @Profile("!local & !test") GoogleIdTokenVerifier googleVerifier(@Value("${app.google.issuer}") String issuer,@Value("${app.google.client-id}") String clientId){
        JwtDecoder decoder=JwtDecoders.fromIssuerLocation(issuer);
        return token->{ try { Jwt jwt=decoder.decode(token); if(!jwt.getAudience().contains(clientId)) throw new JwtValidationException("aud",List.of()); return new GoogleIdTokenVerifier.GoogleUserInfo(jwt.getSubject(),jwt.getClaimAsString("email"),Boolean.TRUE.equals(jwt.getClaim("email_verified")),jwt.getClaimAsString("name"),jwt.getClaimAsString("picture")); } catch(Exception e){ throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Google ID Token을 확인할 수 없습니다."); } };
    }
}
