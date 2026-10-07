package com.preclinic.backend.medicine;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.common.ApiException;
import com.preclinic.backend.common.AuditService;
import com.preclinic.backend.common.ClinicClock;
import com.preclinic.backend.common.PageResult;
import com.preclinic.backend.common.PgArrays;
import com.preclinic.backend.medicine.MedicineDtos.CatalogItem;
import com.preclinic.backend.medicine.MedicineDtos.DuplicateDto;
import com.preclinic.backend.medicine.MedicineDtos.IngredientLine;
import com.preclinic.backend.medicine.MedicineDtos.MedicineDto;
import com.preclinic.backend.medicine.MedicineDtos.MedicineRequest;
import com.preclinic.backend.medicine.MedicineDtos.MedicineStats;
import com.preclinic.backend.medicine.MedicineDtos.PackDto;
import com.preclinic.backend.medicine.MedicineDtos.PackInput;
import com.preclinic.backend.security.CurrentUser;

/**
 * The medicine database. Products, brands, manufacturers and ingredients are shared master data (or
 * owned by one clinic when the doctor adds them); how often a clinic prescribes a product, and its
 * favourites, are per clinic. A product's generic name and strength label are stored on the product
 * (kept in step here) so the prescription box can search one table quickly.
 */
@Service
public class MedicineService {

	private static final String VISIBLE = "(p.clinic_id is null or p.clinic_id = :c)";

	private static final String FROM = """
			from medicine_product p
			join brand b on b.id = p.brand_id
			join manufacturer m on m.id = p.manufacturer_id
			left join clinic_medicine cm on cm.product_id = p.id and cm.clinic_id = :c
			""";

	private static final String LIST_COLUMNS = """
			select p.id, p.product_name, p.brand_id, b.name as brand_name, p.manufacturer_id, m.name as manufacturer_name,
			       p.dosage_form, p.route, p.status, p.verification_status, p.category, p.registration_number,
			       p.registration_status, p.aliases, p.generic_name, p.strength_label, p.composition_id,
			       coalesce(cm.is_favorite, false) as is_favorite, coalesce(cm.usage_count, 0) as usage_count,
			       cm.last_used_at, (p.clinic_id is not null) as clinic_owned, p.created_at, p.updated_at
			""";

	private static final String CATALOG_COLUMNS = """
			select p.id, p.product_name, p.generic_name, p.strength_label, p.dosage_form, m.name as manufacturer_name,
			       coalesce(cm.is_favorite, false) as is_favorite, coalesce(cm.usage_count, 0) as usage_count,
			       cm.last_used_at
			""";

	public record Query(String q, String status, String form, Long manufacturerId, String sort, String dir, int skip,
			int limit) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ClinicClock clock;
	private final AuditService audit;

	public MedicineService(JdbcClient jdbc, CurrentUser currentUser, ClinicClock clock, AuditService audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.clock = clock;
		this.audit = audit;
	}

	static String normalize(String value) {
		return value.trim().toLowerCase().replaceAll("\\s+", " ");
	}

	private static String like(String value) {
		return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	// --- prescription box ----------------------------------------------------------

	/**
	 * Ranked search (exact name, brand, generic, alias, prefix, contains, strength digits, dosage form,
	 * manufacturer); favourites and well-used medicines get a boost. Only active medicines are offered.
	 */
	@Transactional(readOnly = true)
	public List<CatalogItem> search(String query, int limit) {
		String q = normalize(query == null ? "" : query);
		if (q.isEmpty()) {
			return List.of();
		}
		String digits = q.replaceAll("[^0-9]", "");
		boolean hasDigit = q.matches(".*\\d.*");
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		params.put("q", q);
		params.put("prefix", likeStart(q));
		params.put("contains", like(q));
		params.put("hasDigit", hasDigit);
		params.put("digitsLike", like(digits));
		params.put("limit", limit);
		return jdbc.sql(CATALOG_COLUMNS + ", t.score " + """
				from (
				  select p.id as pid, (case
				    when p.normalized_name = :q then 100
				    when b.normalized_name = :q then 90
				    when lower(p.generic_name) = :q then 85
				    when exists (select 1 from unnest(p.aliases) a where lower(a) = :q) then 80
				    when p.normalized_name like :prefix escape '\\' then 70
				    when b.normalized_name like :prefix escape '\\' then 65
				    when p.normalized_name like :contains escape '\\' then 50
				    when b.normalized_name like :contains escape '\\' then 45
				    when lower(p.generic_name) like :contains escape '\\' then 40
				    when :hasDigit and regexp_replace(p.strength_label, '[^0-9]', ' ', 'g') like :digitsLike escape '\\' then 38
				    when lower(p.dosage_form) = :q then 35
				    when exists (select 1 from unnest(p.aliases) a where lower(a) like :contains escape '\\') then 30
				    when lower(m.name) like :contains escape '\\' then 15
				    else 0 end) as base
				  from medicine_product p
				  join brand b on b.id = p.brand_id
				  join manufacturer m on m.id = p.manufacturer_id
				  where """ + " " + VISIBLE + """
				     and p.status = 'Active'
				) ranked
				join medicine_product p on p.id = ranked.pid
				join manufacturer m on m.id = p.manufacturer_id
				left join clinic_medicine cm on cm.product_id = p.id and cm.clinic_id = :c
				cross join lateral (select ranked.base
				    + (case when coalesce(cm.is_favorite, false) then 15 else 0 end)
				    + least(coalesce(cm.usage_count, 0), 10) as score) t
				where ranked.base > 0
				order by t.score desc, p.product_name
				limit :limit
				""").params(params).query(catalogMapper()).list();
	}

	private static String likeStart(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

	@Transactional(readOnly = true)
	public List<CatalogItem> frequentlyUsed(int limit) {
		return jdbc.sql(CATALOG_COLUMNS + FROM + " where " + VISIBLE
				+ " and p.status = 'Active' and cm.usage_count > 0 order by cm.usage_count desc, p.product_name limit :limit")
				.param("c", currentUser.clinicId()).param("limit", limit).query(catalogMapper()).list();
	}

	@Transactional(readOnly = true)
	public List<CatalogItem> recentlyUsed(int limit) {
		return jdbc.sql(CATALOG_COLUMNS + FROM + " where " + VISIBLE
				+ " and p.status = 'Active' and cm.last_used_at is not null order by cm.last_used_at desc, p.id limit :limit")
				.param("c", currentUser.clinicId()).param("limit", limit).query(catalogMapper()).list();
	}

	// --- library -------------------------------------------------------------------

	@Transactional(readOnly = true)
	public MedicineDto get(long id) {
		List<Row> found = jdbc.sql(LIST_COLUMNS + FROM + " where p.id = :id and " + VISIBLE)
				.param("id", id).param("c", currentUser.clinicId()).query(listMapper()).list();
		if (found.isEmpty()) {
			throw ApiException.notFound("MEDICINE_NOT_FOUND", "No such medicine.");
		}
		return withDetails(found).get(0);
	}

	@Transactional(readOnly = true)
	public PageResult<MedicineDto> list(Query q) {
		Map<String, Object> params = new HashMap<>();
		params.put("c", currentUser.clinicId());
		StringBuilder where = new StringBuilder(" where " + VISIBLE);
		if (q.q() != null && !q.q().isBlank()) {
			where.append(" and (lower(p.product_name) like :text escape '\\'")
					.append(" or lower(b.name) like :text escape '\\'")
					.append(" or lower(p.generic_name) like :text escape '\\'")
					.append(" or lower(m.name) like :text escape '\\'")
					.append(" or lower(p.dosage_form) like :text escape '\\'")
					.append(" or lower(coalesce(p.registration_number, '')) like :text escape '\\')");
			params.put("text", like(q.q().trim().toLowerCase()));
		}
		if (q.status() != null && !q.status().isBlank()) {
			where.append(" and p.status = :status");
			params.put("status", q.status());
		}
		if (q.form() != null && !q.form().isBlank()) {
			where.append(" and p.dosage_form = :form");
			params.put("form", q.form());
		}
		if (q.manufacturerId() != null) {
			where.append(" and p.manufacturer_id = :manufacturerId");
			params.put("manufacturerId", q.manufacturerId());
		}
		long total = jdbc.sql("select count(*) " + FROM + where).params(params).query(Long.class).single();
		params.put("limit", q.limit());
		params.put("skip", q.skip());
		List<Row> page = jdbc.sql(LIST_COLUMNS + FROM + where + " order by " + orderBy(q)
				+ " limit :limit offset :skip").params(params).query(listMapper()).list();
		return new PageResult<>(withDetails(page), total);
	}

	private static String orderBy(Query q) {
		String dir = "desc".equalsIgnoreCase(q.dir()) ? " desc" : " asc";
		String key = q.sort() == null ? "name" : q.sort();
		return switch (key) {
			case "name" -> "p.normalized_name" + dir + ", p.id";
			case "generic" -> "lower(p.generic_name)" + dir + ", p.normalized_name, p.id";
			case "form" -> "p.dosage_form" + dir + ", p.normalized_name, p.id";
			case "manufacturer" -> "lower(m.name)" + dir + ", p.normalized_name, p.id";
			case "usage" -> "coalesce(cm.usage_count, 0)" + dir + ", p.normalized_name, p.id";
			default -> throw ApiException.badRequest("INVALID_SORT", "Unknown sort: " + key);
		};
	}

	@Transactional(readOnly = true)
	public MedicineStats stats() {
		return jdbc.sql("""
				select count(*) as total,
				       count(*) filter (where p.status = 'Active') as active,
				       count(distinct p.brand_id) as brands,
				       (select count(distinct ci.ingredient_id) from composition_ingredient ci
				         join medicine_product p2 on p2.composition_id = ci.composition_id
				         where (p2.clinic_id is null or p2.clinic_id = :c)) as generics
				from medicine_product p where """ + " " + VISIBLE)
				.param("c", currentUser.clinicId()).query(MedicineStats.class).single();
	}

	/** Other active brands with the same ingredients, strengths and dosage form. */
	@Transactional(readOnly = true)
	public List<MedicineDto> alternatives(long id) {
		MedicineDto source = get(id);
		String sql = LIST_COLUMNS + FROM + """
				 where p.id <> :id and p.status = 'Active' and p.dosage_form = :form and """ + VISIBLE + """
				   and (select string_agg(ci.ingredient_id || ':' || trim_scale(ci.strength_value) || ci.strength_unit
				                          || coalesce(':' || trim_scale(ci.per_value) || ci.per_unit, ''), '|' order by ci.ingredient_id)
				        from composition_ingredient ci where ci.composition_id = p.composition_id)
				     = (select string_agg(ci.ingredient_id || ':' || trim_scale(ci.strength_value) || ci.strength_unit
				                          || coalesce(':' || trim_scale(ci.per_value) || ci.per_unit, ''), '|' order by ci.ingredient_id)
				        from composition_ingredient ci join medicine_product s on s.composition_id = ci.composition_id
				        where s.id = :id)
				 order by b.name, p.normalized_name
				""";
		return withDetails(jdbc.sql(sql).param("id", id).param("c", currentUser.clinicId())
				.param("form", source.dosageForm()).query(listMapper()).list());
	}

	/** Autocomplete for the add/edit form. */
	@Transactional(readOnly = true)
	public List<String> lookup(String kind, String q) {
		String table = switch (kind) {
			case "ingredients" -> "ingredient";
			case "manufacturers" -> "manufacturer";
			case "brands" -> "brand";
			default -> throw ApiException.badRequest("INVALID_LOOKUP", "Unknown lookup: " + kind);
		};
		String text = q == null || q.isBlank() ? "%" : like(q.trim().toLowerCase());
		return jdbc.sql("select distinct name from " + table + " where lower(name) like :t escape '\\' order by name limit 20")
				.param("t", text).query(String.class).list();
	}

	// --- writes --------------------------------------------------------------------

	/** Medicines the clinic already has that look like this one (same brand, form and a shared ingredient). */
	@Transactional(readOnly = true)
	public List<DuplicateDto> duplicates(String brandName, String dosageForm, List<String> ingredientNames) {
		if (brandName == null || brandName.isBlank() || ingredientNames == null || ingredientNames.isEmpty()) {
			return List.of();
		}
		List<String> normalized = ingredientNames.stream().filter(n -> n != null && !n.isBlank())
				.map(MedicineService::normalize).toList();
		if (normalized.isEmpty()) {
			return List.of();
		}
		List<Long> ids = jdbc.sql("""
				select p.id from medicine_product p
				join brand b on b.id = p.brand_id
				join composition_ingredient ci on ci.composition_id = p.composition_id
				join ingredient i on i.id = ci.ingredient_id
				where """ + " " + VISIBLE + """
				  and b.normalized_name = :brand and p.dosage_form = :form and i.normalized_name in (:ingredients)
				group by p.id order by p.id
				""")
				.param("c", currentUser.clinicId()).param("brand", normalize(brandName)).param("form", dosageForm)
				.param("ingredients", normalized).query(Long.class).list();
		return ids.stream().map(id -> new DuplicateDto(get(id),
				"Same brand, dosage form, and at least one matching ingredient.")).toList();
	}

	@Transactional
	public MedicineDto create(MedicineRequest r, boolean force) {
		if (!force) {
			List<DuplicateDto> found = duplicates(r.brandName(), r.dosageForm(),
					r.ingredients().stream().map(MedicineDtos.IngredientInput::name).toList());
			if (!found.isEmpty()) {
				throw ApiException.conflict("POSSIBLE_DUPLICATE",
						"A medicine with the same brand, dosage form and ingredient already exists.");
			}
		}
		long clinicId = currentUser.clinicId();
		long manufacturerId = findOrCreateManufacturer(r.manufacturerName(), "Active");
		long brandId = findOrCreateBrand(r.brandName(), manufacturerId, "Active");
		long compositionId = insertComposition(r.ingredients());
		long id = jdbc.sql("""
				insert into medicine_product (clinic_id, brand_id, composition_id, manufacturer_id, product_name,
				       normalized_name, dosage_form, route, status, category, registration_number, registration_status)
				values (:c, :brand, :comp, :mf, :name, :norm, :form, :route, :status, :category, :regNo, :regStatus)
				returning id
				""")
				.param("c", clinicId).param("brand", brandId).param("comp", compositionId).param("mf", manufacturerId)
				.param("name", r.productName().trim()).param("norm", normalize(r.productName()))
				.param("form", r.dosageForm()).param("route", r.route()).param("status", r.status())
				.param("category", blankToNull(r.category())).param("regNo", blankToNull(r.registrationNumber()))
				.param("regStatus", blankToNull(r.registrationStatus())).query(Long.class).single();
		refreshLabels(id, compositionId);
		syncPacks(id, r.packs());
		audit.log("medicine.create", "medicine", id, AuditService.detail("name", r.productName().trim()));
		return get(id);
	}

	@Transactional
	public MedicineDto update(long id, MedicineRequest r) {
		var current = jdbc.sql("select composition_id from medicine_product p where p.id = :id and " + VISIBLE + " for update")
				.param("id", id).param("c", currentUser.clinicId()).query(Long.class).optional()
				.orElseThrow(() -> ApiException.notFound("MEDICINE_NOT_FOUND", "No such medicine."));
		long oldComposition = current;
		long manufacturerId = findOrCreateManufacturer(r.manufacturerName(), "Active");
		long brandId = findOrCreateBrand(r.brandName(), manufacturerId, "Active");
		// A fresh composition for this product: other products may share the old one, and must not change.
		long compositionId = insertComposition(r.ingredients());
		jdbc.sql("""
				update medicine_product set brand_id = :brand, composition_id = :comp, manufacturer_id = :mf,
				       product_name = :name, normalized_name = :norm, dosage_form = :form, route = :route,
				       status = :status, category = :category, registration_number = :regNo,
				       registration_status = :regStatus, updated_at = now()
				where id = :id
				""")
				.param("brand", brandId).param("comp", compositionId).param("mf", manufacturerId)
				.param("name", r.productName().trim()).param("norm", normalize(r.productName()))
				.param("form", r.dosageForm()).param("route", r.route()).param("status", r.status())
				.param("category", blankToNull(r.category())).param("regNo", blankToNull(r.registrationNumber()))
				.param("regStatus", blankToNull(r.registrationStatus())).param("id", id).update();
		jdbc.sql("delete from medicine_composition where id = :old and not exists (select 1 from medicine_product where composition_id = :old)")
				.param("old", oldComposition).update();
		refreshLabels(id, compositionId);
		if (r.packs() != null) {
			syncPacks(id, r.packs());
		}
		audit.log("medicine.update", "medicine", id, AuditService.detail("name", r.productName().trim()));
		return get(id);
	}

	@Transactional
	public MedicineDto setStatus(long id, String status) {
		int changed = jdbc.sql("update medicine_product p set status = :status, updated_at = now() where p.id = :id and "
				+ VISIBLE).param("status", status).param("id", id).param("c", currentUser.clinicId()).update();
		if (changed == 0) {
			throw ApiException.notFound("MEDICINE_NOT_FOUND", "No such medicine.");
		}
		audit.log("medicine.status", "medicine", id, AuditService.detail("status", status));
		return get(id);
	}

	@Transactional
	public boolean toggleFavorite(long id) {
		get(id);
		return jdbc.sql("""
				insert into clinic_medicine (clinic_id, product_id, usage_count, is_favorite)
				values (:c, :p, 0, true)
				on conflict (clinic_id, product_id) do update set is_favorite = not clinic_medicine.is_favorite
				returning is_favorite
				""").param("c", currentUser.clinicId()).param("p", id).query(Boolean.class).single();
	}

	/**
	 * Counts one more use of each medicine for a clinic (called when a prescription is saved). Unknown ids
	 * are ignored: a prescription line may be free text with no product behind it.
	 */
	@Transactional
	public void recordUsage(long clinicId, List<Long> productIds) {
		for (Long productId : productIds) {
			if (productId == null) {
				continue;
			}
			jdbc.sql("""
					insert into clinic_medicine (clinic_id, product_id, usage_count, last_used_at, is_favorite)
					select :c, p.id, 1, now(), false from medicine_product p where p.id = :p and """ + " " + VISIBLE + """

					on conflict (clinic_id, product_id)
					do update set usage_count = clinic_medicine.usage_count + 1, last_used_at = now()
					""").param("c", clinicId).param("p", productId).update();
		}
	}

	/**
	 * "Type it and go": prescribing never blocks on filling in master data. An existing product with the
	 * same name is reused; otherwise a minimal, unverified, clinic-owned record is created.
	 */
	@Transactional
	public CatalogItem quickAdd(String rawName) {
		String name = rawName.trim();
		String norm = normalize(name);
		Long existing = jdbc.sql("select p.id from medicine_product p where p.normalized_name = :n and " + VISIBLE
				+ " order by p.id limit 1").param("n", norm).param("c", currentUser.clinicId())
				.query(Long.class).optional().orElse(null);
		long id;
		if (existing != null) {
			id = existing;
		}
		else {
			long ingredientId = findOrCreateIngredient("Unspecified", "Unverified");
			long manufacturerId = findOrCreateManufacturer("Unspecified", "Unverified");
			long brandId = findOrCreateBrand(name, manufacturerId, "Unverified");
			long compositionId = jdbc.sql("insert into medicine_composition default values returning id")
					.query(Long.class).single();
			jdbc.sql("insert into composition_ingredient (composition_id, ingredient_id, strength_value, strength_unit, position)"
					+ " values (:comp, :ing, 0, '', 0)").param("comp", compositionId).param("ing", ingredientId).update();
			id = jdbc.sql("""
					insert into medicine_product (clinic_id, brand_id, composition_id, manufacturer_id, product_name,
					       normalized_name, dosage_form, route, status, category, verification_status)
					values (:c, :brand, :comp, :mf, :name, :norm, 'Tablet', 'Oral', 'Unverified', 'Manual Entry', 'Unverified')
					returning id
					""")
					.param("c", currentUser.clinicId()).param("brand", brandId).param("comp", compositionId)
					.param("mf", manufacturerId).param("name", name).param("norm", norm).query(Long.class).single();
			refreshLabels(id, compositionId);
		}
		return jdbc.sql(CATALOG_COLUMNS + FROM + " where p.id = :id and " + VISIBLE)
				.param("id", id).param("c", currentUser.clinicId()).query(catalogMapper()).single();
	}

	// --- internals -----------------------------------------------------------------

	private long findOrCreateIngredient(String name, String status) {
		String norm = normalize(name);
		jdbc.sql("insert into ingredient (name, normalized_name, status) values (:n, :norm, :s)"
				+ " on conflict (normalized_name) do nothing").param("n", name.trim()).param("norm", norm)
				.param("s", status).update();
		return jdbc.sql("select id from ingredient where normalized_name = :norm").param("norm", norm)
				.query(Long.class).single();
	}

	private long findOrCreateManufacturer(String name, String status) {
		String norm = normalize(name);
		jdbc.sql("insert into manufacturer (name, normalized_name, status) values (:n, :norm, :s)"
				+ " on conflict (normalized_name) do nothing").param("n", name.trim()).param("norm", norm)
				.param("s", status).update();
		return jdbc.sql("select id from manufacturer where normalized_name = :norm").param("norm", norm)
				.query(Long.class).single();
	}

	private long findOrCreateBrand(String name, long manufacturerId, String status) {
		String norm = normalize(name);
		jdbc.sql("insert into brand (manufacturer_id, name, normalized_name, status) values (:m, :n, :norm, :s)"
				+ " on conflict (manufacturer_id, normalized_name) do nothing")
				.param("m", manufacturerId).param("n", name.trim()).param("norm", norm).param("s", status).update();
		return jdbc.sql("select id from brand where manufacturer_id = :m and normalized_name = :norm")
				.param("m", manufacturerId).param("norm", norm).query(Long.class).single();
	}

	private long insertComposition(List<MedicineDtos.IngredientInput> ingredients) {
		long compositionId = jdbc.sql("insert into medicine_composition default values returning id")
				.query(Long.class).single();
		int position = 0;
		java.util.Set<Long> used = new java.util.HashSet<>();
		for (MedicineDtos.IngredientInput i : ingredients) {
			long ingredientId = findOrCreateIngredient(i.name(), "Active");
			if (!used.add(ingredientId)) {
				throw ApiException.unprocessable("DUPLICATE_INGREDIENT",
						"The ingredient \"" + i.name().trim() + "\" is listed twice.");
			}
			jdbc.sql("""
					insert into composition_ingredient (composition_id, ingredient_id, strength_value, strength_unit,
					       per_value, per_unit, position)
					values (:comp, :ing, :value, :unit, :perValue, :perUnit, :pos)
					""")
					.param("comp", compositionId).param("ing", ingredientId).param("value", i.strengthValue())
					.param("unit", i.strengthUnit().trim()).param("perValue", i.strengthPerValue())
					.param("perUnit", i.strengthPerValue() == null ? null : blankToNull(i.strengthPerUnit()))
					.param("pos", position++).update();
		}
		return compositionId;
	}

	/** Recomputes the stored generic name and strength label from the composition's ingredient lines. */
	private void refreshLabels(long productId, long compositionId) {
		List<IngredientLine> lines = ingredientLines(List.of(compositionId)).getOrDefault(compositionId, List.of());
		jdbc.sql("update medicine_product set generic_name = :generic, strength_label = :strength where id = :id")
				.param("generic", genericName(lines)).param("strength", strengthLabel(lines)).param("id", productId).update();
	}

	/** Adds, updates or deactivates packs; price changes are appended to the pack's history, never overwritten. */
	private void syncPacks(long productId, List<PackInput> packs) {
		if (packs == null) {
			return;
		}
		LocalDate today = clock.today();
		List<Long> keep = new ArrayList<>();
		for (PackInput pack : packs) {
			long packId;
			if (pack.id() != null) {
				Integer owned = jdbc.sql("select 1 from medicine_pack where id = :id and product_id = :p")
						.param("id", pack.id()).param("p", productId).query(Integer.class).optional().orElse(null);
				if (owned == null) {
					throw ApiException.unprocessable("PACK_NOT_OF_PRODUCT", "A pack does not belong to this medicine.");
				}
				packId = pack.id();
				jdbc.sql("update medicine_pack set quantity = :q, unit = :u, pack_description = :d, status = 'Active' where id = :id")
						.param("q", pack.quantity()).param("u", pack.unit().trim()).param("d", pack.packDescription().trim())
						.param("id", packId).update();
			}
			else {
				packId = jdbc.sql("""
						insert into medicine_pack (product_id, quantity, unit, pack_description)
						values (:p, :q, :u, :d) returning id
						""").param("p", productId).param("q", pack.quantity()).param("u", pack.unit().trim())
						.param("d", pack.packDescription().trim()).query(Long.class).single();
			}
			keep.add(packId);
			if (pack.price() != null) {
				BigDecimal latest = jdbc.sql("select price from medicine_price where pack_id = :id order by effective_from desc, id desc limit 1")
						.param("id", packId).query(BigDecimal.class).optional().orElse(null);
				if (latest == null || latest.compareTo(pack.price()) != 0) {
					jdbc.sql("insert into medicine_price (pack_id, price, effective_from, source) values (:id, :price, :date, 'Manually entered')")
							.param("id", packId).param("price", pack.price()).param("date", today).update();
				}
			}
		}
		if (keep.isEmpty()) {
			jdbc.sql("update medicine_pack set status = 'Inactive' where product_id = :p").param("p", productId).update();
		}
		else {
			jdbc.sql("update medicine_pack set status = 'Inactive' where product_id = :p and id not in (:keep)")
					.param("p", productId).param("keep", keep).update();
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	// --- label helpers (also used by the demo-data loader) --------------------------

	static String format(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString();
	}

	static String strengthOf(IngredientLine line) {
		String base = format(line.strengthValue()) + line.strengthUnit();
		return line.strengthPerValue() == null ? base
				: base + " / " + format(line.strengthPerValue()) + (line.strengthPerUnit() == null ? "" : line.strengthPerUnit());
	}

	static String genericName(List<IngredientLine> lines) {
		return lines.stream().map(IngredientLine::name).collect(Collectors.joining(" + "));
	}

	static String strengthLabel(List<IngredientLine> lines) {
		return lines.stream().map(MedicineService::strengthOf).collect(Collectors.joining(" + "));
	}

	static String compositionLabel(List<IngredientLine> lines) {
		return lines.stream().map(l -> l.name() + " " + strengthOf(l)).collect(Collectors.joining(" + "));
	}

	// --- loading -------------------------------------------------------------------

	private record Row(MedicineDto base, long compositionId) {
	}

	private RowMapper<Row> listMapper() {
		return (rs, i) -> {
			OffsetDateTime used = rs.getObject("last_used_at", OffsetDateTime.class);
			MedicineDto dto = new MedicineDto(rs.getLong("id"), rs.getString("product_name"), rs.getLong("brand_id"),
					rs.getString("brand_name"), rs.getLong("manufacturer_id"), rs.getString("manufacturer_name"),
					rs.getString("dosage_form"), rs.getString("route"), rs.getString("status"),
					rs.getString("verification_status"), rs.getString("category"), rs.getString("registration_number"),
					rs.getString("registration_status"), PgArrays.read(rs.getArray("aliases")),
					rs.getString("generic_name"), rs.getString("strength_label"), null, List.of(), List.of(),
					rs.getBoolean("is_favorite"), rs.getInt("usage_count"), used == null ? null : used.toInstant(),
					rs.getBoolean("clinic_owned"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
					rs.getObject("updated_at", OffsetDateTime.class).toInstant());
			return new Row(dto, rs.getLong("composition_id"));
		};
	}

	/** Attaches ingredient lines and packs (two queries for the whole page, not two per row). */
	private List<MedicineDto> withDetails(List<Row> rows) {
		if (rows.isEmpty()) {
			return List.of();
		}
		List<Long> compositionIds = rows.stream().map(Row::compositionId).distinct().toList();
		List<Long> productIds = rows.stream().map(r -> r.base().id()).toList();
		Map<Long, List<IngredientLine>> lines = ingredientLines(compositionIds);
		Map<Long, List<PackDto>> packs = new LinkedHashMap<>();
		jdbc.sql("""
				select pk.product_id, pk.id, pk.quantity, pk.unit, pk.pack_description, pk.status,
				       lp.price, lp.currency, lp.effective_from
				from medicine_pack pk
				left join lateral (select price, currency, effective_from from medicine_price
				                   where pack_id = pk.id order by effective_from desc, id desc limit 1) lp on true
				where pk.product_id in (:ids) order by pk.id
				""").param("ids", productIds)
				.query((rs, i) -> Map.entry(rs.getLong("product_id"), new PackDto(rs.getLong("id"),
						rs.getBigDecimal("quantity"), rs.getString("unit"), rs.getString("pack_description"),
						rs.getString("status"), rs.getBigDecimal("price"), rs.getString("currency"),
						rs.getObject("effective_from", LocalDate.class))))
				.list().forEach(e -> packs.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));

		List<MedicineDto> result = new ArrayList<>();
		for (Row holder : rows) {
			MedicineDto row = holder.base();
			List<IngredientLine> ingredientLines = lines.getOrDefault(holder.compositionId(), List.of());
			result.add(new MedicineDto(row.id(), row.productName(), row.brandId(), row.brandName(), row.manufacturerId(),
					row.manufacturerName(), row.dosageForm(), row.route(), row.status(), row.verificationStatus(),
					row.category(), row.registrationNumber(), row.registrationStatus(), row.aliases(), row.genericName(),
					row.strengthLabel(), compositionLabel(ingredientLines), ingredientLines,
					packs.getOrDefault(row.id(), List.of()), row.isFavorite(), row.usageCount(), row.lastUsedAt(),
					row.clinicOwned(), row.createdAt(), row.updatedAt()));
		}
		return result;
	}

	private Map<Long, List<IngredientLine>> ingredientLines(List<Long> compositionIds) {
		Map<Long, List<IngredientLine>> byComposition = new HashMap<>();
		jdbc.sql("""
				select ci.composition_id, i.id as ingredient_id, i.name, ci.strength_value, ci.strength_unit,
				       ci.per_value, ci.per_unit
				from composition_ingredient ci join ingredient i on i.id = ci.ingredient_id
				where ci.composition_id in (:ids) order by ci.composition_id, ci.position, i.id
				""").param("ids", compositionIds)
				.query((rs, n) -> Map.entry(rs.getLong("composition_id"), new IngredientLine(rs.getLong("ingredient_id"),
						rs.getString("name"), rs.getBigDecimal("strength_value"), rs.getString("strength_unit"),
						rs.getBigDecimal("per_value"), rs.getString("per_unit"))))
				.list().forEach(e -> byComposition.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
		return byComposition;
	}

	private static RowMapper<CatalogItem> catalogMapper() {
		return (rs, i) -> toCatalogItem(rs);
	}

	private static CatalogItem toCatalogItem(ResultSet rs) throws SQLException {
		OffsetDateTime used = rs.getObject("last_used_at", OffsetDateTime.class);
		return new CatalogItem(rs.getLong("id"), rs.getString("product_name"), rs.getString("generic_name"),
				rs.getString("strength_label"), rs.getString("dosage_form"), rs.getString("manufacturer_name"),
				rs.getBoolean("is_favorite"), rs.getInt("usage_count"), used == null ? null : used.toInstant());
	}
}
