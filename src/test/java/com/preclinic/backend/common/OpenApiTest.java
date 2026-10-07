package com.preclinic.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.ApiIntegrationTest;

class OpenApiTest extends ApiIntegrationTest {

	@Test
	void theApiIsDescribedAndTheBearerTokenIsDeclared() throws Exception {
		String json = mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("Clinstra API"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andReturn().getResponse().getContentAsString();
		Map<String, Object> paths = JsonPath.read(json, "$.paths");
		assertThat(paths.keySet()).contains(
				"/api/v1/auth/login",
				"/api/v1/queue/intake",
				"/api/v1/queue/call-next",
				"/api/v1/visits/{visitId}/consultation",
				"/api/v1/invoices/{id}/payments",
				"/api/v1/follow-ups/{id}/schedule-appointment",
				"/api/v1/medicines/search",
				"/api/v1/reports/summary",
				"/api/v1/patients/{id}/timeline",
				"/api/v1/events");
		assertThat(paths.size()).isGreaterThan(90);
	}

	@Test
	void theDocumentationPagesAreReachableWithoutLoggingIn() throws Exception {
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}
}
