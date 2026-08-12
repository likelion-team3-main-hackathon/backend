package tri_lion.health.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI api() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Tri Lion Wellness API")
                                .description("개인 맞춤 건강 분석, 루틴, 액티비티 및 전문가 커리큘럼 API")
                                .version("v1"))
                .servers(List.of(new Server().url("/").description("현재 서버")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")));
    }

    @Bean
    OpenApiCustomizer publicApiCustomizer() {
        return openApi ->
                List.of("/api/v1/auth/oauth/google", "/api/v1/auth/token/refresh")
                        .forEach(
                                path -> {
                                    var pathItem = openApi.getPaths().get(path);
                                    if (pathItem != null) {
                                        pathItem.readOperations()
                                                .forEach(
                                                        operation ->
                                                                operation.setSecurity(List.of()));
                                    }
                                });
    }
}
