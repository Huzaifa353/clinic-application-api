package com.preclinic.backend.consultation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.consultation.ConsultationDtos.PrescriptionItemDto;
import com.preclinic.backend.consultation.ConsultationDtos.PrescriptionItemInput;
import com.preclinic.backend.consultation.ConsultationDtos.TemplateDto;
import com.preclinic.backend.consultation.ConsultationDtos.TemplateRequest;
import com.preclinic.backend.security.CurrentUser;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.DoctorDirectory;

/**
 * A doctor's reusable prescription structures ("Common URTI"). Templates are the doctor's own;
 * the assistant who prints prescriptions reads the clinic doctor's.
 */
@Service
public class TemplateService {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final DoctorDirectory doctors;

	public TemplateService(JdbcClient jdbc, CurrentUser currentUser, DoctorDirectory doctors) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.doctors = doctors;
	}

	@Transactional(readOnly = true)
	public List<TemplateDto> list() {
		AppUser doctor = doctors.resolve(currentUser.isDoctor() ? currentUser.id() : null);
		List<TemplateDto> templates = jdbc.sql("""
				select t.id, t.name, t.doctor_id from prescription_template t
				where t.clinic_id = :c and t.doctor_id = :d order by lower(t.name)
				""").param("c", currentUser.clinicId()).param("d", doctor.getId())
				.query((rs, i) -> new TemplateDto(rs.getLong("id"), rs.getString("name"), rs.getLong("doctor_id"),
						doctor.getFullName(), List.of()))
				.list();
		return withItems(templates);
	}

	@Transactional
	public TemplateDto create(TemplateRequest r) {
		long clinicId = currentUser.clinicId();
		String name = r.name().trim();
		Integer taken = jdbc.sql("select 1 from prescription_template where doctor_id = :d and lower(name) = lower(:n)")
				.param("d", currentUser.id()).param("n", name).query(Integer.class).optional().orElse(null);
		if (taken != null) {
			throw ApiException.conflict("TEMPLATE_NAME_TAKEN", "You already have a template with that name.");
		}
		long id = jdbc.sql("insert into prescription_template (clinic_id, doctor_id, name) values (:c, :d, :n) returning id")
				.param("c", clinicId).param("d", currentUser.id()).param("n", name).query(Long.class).single();
		int position = 0;
		for (PrescriptionItemInput item : r.medicines()) {
			Long product = item.productId();
			if (product != null) {
				Integer visible = jdbc.sql("select 1 from medicine_product p where p.id = :id and (p.clinic_id is null or p.clinic_id = :c)")
						.param("id", product).param("c", clinicId).query(Integer.class).optional().orElse(null);
				if (visible == null) {
					throw ApiException.unprocessable("UNKNOWN_MEDICINE", "A medicine is not in the medicine database.");
				}
			}
			jdbc.sql("""
					insert into prescription_template_item (template_id, position, medicine_name, product_id, frequency,
					       duration, timing, notes)
					values (:t, :pos, :name, :product, :freq, :duration, :timing, :notes)
					""")
					.param("t", id).param("pos", position++).param("name", item.medicine().trim()).param("product", product)
					.param("freq", blank(item.frequency())).param("duration", blank(item.duration()))
					.param("timing", blank(item.instructions())).param("notes", blank(item.notes())).update();
		}
		return withItems(List.of(new TemplateDto(id, name, currentUser.id(), currentUser.name(), List.of()))).get(0);
	}

	@Transactional
	public void delete(long id) {
		int removed = jdbc.sql("delete from prescription_template where id = :id and doctor_id = :d and clinic_id = :c")
				.param("id", id).param("d", currentUser.id()).param("c", currentUser.clinicId()).update();
		if (removed == 0) {
			throw ApiException.notFound("TEMPLATE_NOT_FOUND", "No such template.");
		}
	}

	private List<TemplateDto> withItems(List<TemplateDto> templates) {
		if (templates.isEmpty()) {
			return templates;
		}
		List<Long> ids = templates.stream().map(TemplateDto::id).toList();
		Map<Long, List<PrescriptionItemDto>> items = new HashMap<>();
		jdbc.sql("select id, template_id, medicine_name, product_id, frequency, duration, timing, notes"
				+ " from prescription_template_item where template_id in (:ids) order by template_id, position")
				.param("ids", ids)
				.query((rs, i) -> {
					long product = rs.getLong("product_id");
					Long productId = rs.wasNull() ? null : product;
					return Map.entry(rs.getLong("template_id"), new PrescriptionItemDto(rs.getLong("id"),
							rs.getString("medicine_name"), productId, rs.getString("frequency"), rs.getString("duration"),
							rs.getString("timing"), rs.getString("notes")));
				})
				.list().forEach(e -> items.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
		return templates.stream().map(t -> new TemplateDto(t.id(), t.name(), t.doctorId(), t.doctorName(),
				items.getOrDefault(t.id(), List.of()))).toList();
	}

	private static String blank(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
