package com.preclinic.backend.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.common.ProblemJson;

/**
 * Stateless JWT security. Everything requires a valid token except login and the health probe.
 * Role checks are done with {@code @PreAuthorize} on the service/controller methods.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter, JdbcClient jdbc)
			throws Exception {
		AuthenticationEntryPoint unauthorized = (request, response, ex) -> ProblemJson.write(response,
				HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required or the token is invalid or expired.");
		AccessDeniedHandler forbidden = (request, response, ex) -> ProblemJson.write(response,
				HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to do that.");

		http
				.cors(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers(HttpMethod.POST, Api.V1 + "/auth/login").permitAll()
						// EventSource cannot send headers; this route checks a one-time ticket itself.
						.requestMatchers(HttpMethod.GET, Api.V1 + "/events").permitAll()
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						// API documentation (switched off in production by configuration)
						.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth -> oauth
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
						.authenticationEntryPoint(unauthorized)
						.accessDeniedHandler(forbidden))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(unauthorized)
						.accessDeniedHandler(forbidden))
				// a valid token of a deactivated user must stop working immediately, not at expiry
				.addFilterAfter(new ActiveUserFilter(jdbc), BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(@Value("${clinstra.cors.allowed-origins}") String origins) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
