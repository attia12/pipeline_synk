package fr.tictak.dema.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("API de Gestion des Déménagements TicTak")
                        .version("1.0.0")
                        .description("Une API complète pour la gestion des services de déménagement et l'authentification sur la plateforme TicTak, conçue pour offrir une gestion sécurisée et efficace des opérations de déménagement. Développée par BramaSquare.")
                        .contact(new Contact()
                                .name("Mohamed Aichaoui")
                                .email("mohamed.aichaoui.tic@gmail.com")
                                .url("https://www.tictak.fr"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Authentification basée sur JWT pour un accès sécurisé aux points d'entrée de l'API TicTak"))
                )
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"));
    }
}