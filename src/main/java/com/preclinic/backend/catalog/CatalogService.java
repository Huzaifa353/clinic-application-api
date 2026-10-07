package com.preclinic.backend.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.catalog.CatalogDtos.CatalogBootstrap;
import com.preclinic.backend.catalog.CatalogDtos.CatalogEntryDto;
import com.preclinic.backend.catalog.CatalogDtos.FrequencyDto;
import com.preclinic.backend.catalog.CatalogDtos.InvestigationDto;
import com.preclinic.backend.catalog.CatalogDtos.ServiceFeeDto;
import com.preclinic.backend.catalog.CatalogDtos.ServiceFeeRequest;
import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.security.CurrentUser;

@Service
public class CatalogService {

	/** Shown even when empty: the frontend always offers "Other" as a free-text category. */
	private static final String OTHER_CATEGORY = "Other";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	public CatalogService(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@Transactional(readOnly = true)
	public CatalogBootstrap bootstrap() {
		long c = currentUser.clinicId();

		List<String> symptoms = names("select name from symptom_catalog where clinic_id = :c order by id", c);
		List<String> diagnoses = names("select name from diagnosis_catalog where clinic_id = :c order by id", c);

		List<InvestigationDto> investigations = jdbc.sql("""
				select code as id, name, full_name, grp as "group" from investigation_catalog
				where clinic_id = :c and active order by sort_order, id
				""").param("c", c).query(InvestigationDto.class).list();

		Map<String, List<String>> findings = new LinkedHashMap<>();
		jdbc.sql("""
				select category, finding from examination_finding_catalog
				where clinic_id = :c order by category_order, category, sort_order, id
				""")
				.param("c", c)
				.query((rs, i) -> Map.entry(rs.getString("category"), rs.getString("finding")))
				.list()
				.forEach(e -> findings.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
		List<String> categories = new ArrayList<>(findings.keySet());
		if (!categories.contains(OTHER_CATEGORY)) {
			categories.add(OTHER_CATEGORY);
			findings.put(OTHER_CATEGORY, new ArrayList<>());
		}

		List<FrequencyDto> frequencies = jdbc.sql("""
				select code, meaning_en as meaning from frequency_code where clinic_id = :c order by sort_order, id
				""").param("c", c).query(FrequencyDto.class).list();

		return new CatalogBootstrap(symptoms, diagnoses, investigations, categories, findings, frequencies,
				options("duration", c), options("timing", c));
	}

	private List<String> options(String kind, long clinicId) {
		return jdbc.sql("select value from prescription_option where clinic_id = :c and kind = :k order by sort_order, id")
				.param("c", clinicId).param("k", kind).query(String.class).list();
	}

	private List<String> names(String sql, long clinicId) {
		return jdbc.sql(sql).param("c", clinicId).query(String.class).list();
	}

	/**
	 * Adds a symptom; if the clinic already has one with the same name (ignoring case) that spelling
	 * is returned instead of creating a duplicate.
	 */
	@Transactional
	public CatalogEntryDto addSymptom(String name) {
		return addNamed("symptom_catalog", name);
	}

	@Transactional
	public CatalogEntryDto addDiagnosis(String name) {
		return addNamed("diagnosis_catalog", name);
	}

	/** {@code table} is only ever one of two constants above, never user input. */
	private CatalogEntryDto addNamed(String table, String rawName) {
		long c = currentUser.clinicId();
		String name = rawName.trim().replaceAll("\\s+", " ");
		boolean created = jdbc.sql("insert into " + table + " (clinic_id, name) values (:c, :name) "
				+ "on conflict (clinic_id, lower(name)) do nothing")
				.param("c", c).param("name", name).update() == 1;
		String canonical = jdbc.sql("select name from " + table + " where clinic_id = :c and lower(name) = lower(:name)")
				.param("c", c).param("name", name).query(String.class).single();
		return new CatalogEntryDto(canonical, created);
	}

	// --- service fees ---------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<ServiceFeeDto> serviceFees() {
		return jdbc.sql("select id, name, fee from service_fee where clinic_id = :c and active order by sort_order, id")
				.param("c", currentUser.clinicId()).query(ServiceFeeDto.class).list();
	}

	@Transactional
	public ServiceFeeDto addServiceFee(ServiceFeeRequest r) {
		return jdbc.sql("""
				insert into service_fee (clinic_id, name, fee, sort_order)
				values (:c, :name, :fee,
				        coalesce((select max(sort_order) from service_fee where clinic_id = :c), 0) + 1)
				returning id, name, fee
				""")
				.param("c", currentUser.clinicId()).param("name", r.name().trim()).param("fee", r.fee())
				.query(ServiceFeeDto.class).single();
	}

	@Transactional
	public ServiceFeeDto updateServiceFee(long id, ServiceFeeRequest r) {
		return jdbc.sql("""
				update service_fee set name = :name, fee = :fee where id = :id and clinic_id = :c and active
				returning id, name, fee
				""")
				.param("id", id).param("c", currentUser.clinicId()).param("name", r.name().trim()).param("fee", r.fee())
				.query(ServiceFeeDto.class).optional()
				.orElseThrow(() -> ApiException.notFound("SERVICE_NOT_FOUND", "No such service."));
	}

	/** Invoices keep pointing at the service they were billed for, so a "delete" only deactivates it. */
	@Transactional
	public void removeServiceFee(long id) {
		int changed = jdbc.sql("update service_fee set active = false where id = :id and clinic_id = :c and active")
				.param("id", id).param("c", currentUser.clinicId()).update();
		if (changed == 0) {
			throw ApiException.notFound("SERVICE_NOT_FOUND", "No such service.");
		}
	}
}
