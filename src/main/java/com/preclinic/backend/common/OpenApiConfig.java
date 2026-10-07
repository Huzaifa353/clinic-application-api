package com.preclinic.backend.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * The generated API description (Swagger UI). Every endpoint except login needs the bearer token that
 * {@code POST /api/v1/auth/login} returns; "Authorize" in the UI takes it.
 */
@Configuration
class OpenApiConfig {

	private static final String BEARER = "bearerAuth";

	@Bean
	OpenAPI clinstraApi() {
		return new OpenAPI()
				.info(new Info().title("Clinstra API").version("v1")
						.description("Clinic management API: patients, queue and intake, consultations, billing, "
								+ "follow-ups, medicines and reports. All times are the clinic's local day."))
				.components(new Components().addSecuritySchemes(BEARER,
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
				.addSecurityItem(new SecurityRequirement().addList(BEARER));
	}
}
