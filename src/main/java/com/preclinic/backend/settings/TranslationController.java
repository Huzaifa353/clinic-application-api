package com.preclinic.backend.settings;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.preclinic.backend.common.Api;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.settings.SettingsDtos.TranslationDto;
import com.preclinic.backend.settings.SettingsDtos.UpsertTranslationRequest;

import jakarta.validation.Valid;

/**
 * The English to Urdu dictionary used only when printing documents. Everyone reads it (printing
 * needs it); only the doctor edits it. A term with no entry simply prints in English.
 */
@RestController
@RequestMapping(Api.V1 + "/translations")
public class TranslationController {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	public TranslationController(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Transactional(readOnly = true)
	public List<TranslationDto> list(@RequestParam(required = false) String category) {
		return jdbc.sql("""
				select category, source_text, urdu_text from translation
				where clinic_id = :c and (cast(:category as text) is null or category = :category)
				order by category, source_norm
				""")
				.param("c", currentUser.clinicId())
				.param("category", category == null || category.isBlank() ? null : category)
				.query(TranslationDto.class).list();
	}

	/** Adds or replaces the Urdu text for an English term (matched ignoring case and extra spaces). */
	@PutMapping
	@PreAuthorize("hasRole('DOCTOR')")
	@Transactional
	public TranslationDto upsert(@Valid @RequestBody UpsertTranslationRequest request) {
		String source = request.sourceText().trim();
		return jdbc.sql("""
				insert into translation (clinic_id, category, source_norm, source_text, urdu_text)
				values (:c, :category, :norm, :source, :urdu)
				on conflict (clinic_id, category, source_norm) do update set urdu_text = excluded.urdu_text
				returning category, source_text, urdu_text
				""")
				.param("c", currentUser.clinicId())
				.param("category", request.category())
				.param("norm", normalize(source))
				.param("source", source)
				.param("urdu", request.urduText().trim())
				.query(TranslationDto.class).single();
	}

	@DeleteMapping
	@PreAuthorize("hasRole('DOCTOR')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	public void remove(@RequestParam String category, @RequestParam String sourceText) {
		jdbc.sql("delete from translation where clinic_id = :c and category = :category and source_norm = :norm")
				.param("c", currentUser.clinicId())
				.param("category", category)
				.param("norm", normalize(sourceText))
				.update();
	}

	static String normalize(String text) {
		return text.trim().toLowerCase().replaceAll("\\s+", " ");
	}
}
