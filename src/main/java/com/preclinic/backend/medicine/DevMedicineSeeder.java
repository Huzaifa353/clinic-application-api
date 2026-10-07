package com.preclinic.backend.medicine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.preclinic.backend.medicine.MedicineDtos.IngredientLine;
import com.preclinic.backend.user.ClinicRepository;

/**
 * Local-development convenience: loads the small demonstration medicine list the frontend used to
 * carry (16 Pakistani brands, generic names, packs and prices) when the medicine database is empty, so
 * the prescription box has something to search. It is demo data, NOT a verified DRAP registry; a real
 * deployment imports the registry instead. Runs only with {@code clinstra.dev-seed.enabled=true}.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "clinstra.dev-seed.enabled", havingValue = "true")
public class DevMedicineSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DevMedicineSeeder.class);

	private record Ing(String ingredient, String value, String unit, String per, String perUnit) {
		static Ing of(String ingredient, String value, String unit) {
			return new Ing(ingredient, value, unit, null, null);
		}
	}

	private record Product(String key, String brand, String manufacturer, String name, String form, String status,
			String category, int usage, List<Ing> ingredients, String packDescription, String packUnit, String quantity,
			String packStatus, String price) {
	}

	private final JdbcClient jdbc;
	private final ClinicRepository clinics;

	public DevMedicineSeeder(JdbcClient jdbc, ClinicRepository clinics) {
		this.jdbc = jdbc;
		this.clinics = clinics;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		Long existing = jdbc.sql("select count(*) from medicine_product where clinic_id is null").query(Long.class).single();
		if (existing > 0) {
			return;
		}
		var clinic = clinics.findFirstByOrderByIdAsc().orElse(null);
		if (clinic == null) {
			return;
		}
		Map<String, String> ingredientAliases = Map.of("Paracetamol", "{\"PCM\",\"Acetaminophen\"}");
		Map<String, Long> ingredients = new HashMap<>();
		Map<String, Long> manufacturers = new HashMap<>();
		Map<String, Long> brands = new HashMap<>();
		LocalDate today = LocalDate.now();

		for (Product p : products()) {
			long manufacturerId = manufacturers.computeIfAbsent(p.manufacturer(), name -> insertNamed("manufacturer", name, null));
			long brandId = brands.computeIfAbsent(p.manufacturer() + "/" + p.brand(), key -> jdbc.sql("""
					insert into brand (manufacturer_id, name, normalized_name) values (:m, :n, :norm) returning id
					""").param("m", manufacturerId).param("n", p.brand()).param("norm", p.brand().toLowerCase())
					.query(Long.class).single());
			long compositionId = jdbc.sql("insert into medicine_composition default values returning id").query(Long.class).single();
			int position = 0;
			for (Ing ing : p.ingredients()) {
				long ingredientId = ingredients.computeIfAbsent(ing.ingredient(),
						name -> insertNamed("ingredient", name, ingredientAliases.get(name)));
				jdbc.sql("""
						insert into composition_ingredient (composition_id, ingredient_id, strength_value, strength_unit,
						       per_value, per_unit, position)
						values (:c, :i, :v, :u, :pv, :pu, :pos)
						""").param("c", compositionId).param("i", ingredientId).param("v", new BigDecimal(ing.value()))
						.param("u", ing.unit()).param("pv", ing.per() == null ? null : new BigDecimal(ing.per()))
						.param("pu", ing.perUnit()).param("pos", position++).update();
			}
			List<IngredientLine> lines = p.ingredients().stream().map(i -> new IngredientLine(0, i.ingredient(),
					new BigDecimal(i.value()), i.unit(), i.per() == null ? null : new BigDecimal(i.per()), i.perUnit())).toList();
			long productId = jdbc.sql("""
					insert into medicine_product (clinic_id, brand_id, composition_id, manufacturer_id, product_name,
					       normalized_name, dosage_form, route, status, category, registration_number, registration_status,
					       generic_name, strength_label)
					values (null, :brand, :comp, :mf, :name, :norm, :form, 'Oral', :status, :category, :reg, 'Registered',
					        :generic, :strength)
					returning id
					""")
					.param("brand", brandId).param("comp", compositionId).param("mf", manufacturerId)
					.param("name", p.name()).param("norm", p.name().toLowerCase()).param("form", p.form())
					.param("status", p.status()).param("category", p.category()).param("reg", "DEMO-PROD-" + p.key().toUpperCase())
					.param("generic", MedicineService.genericName(lines)).param("strength", MedicineService.strengthLabel(lines))
					.query(Long.class).single();
			long packId = jdbc.sql("""
					insert into medicine_pack (product_id, quantity, unit, pack_description, status)
					values (:p, :q, :u, :d, :s) returning id
					""").param("p", productId).param("q", new BigDecimal(p.quantity())).param("u", p.packUnit())
					.param("d", p.packDescription()).param("s", p.packStatus()).query(Long.class).single();
			jdbc.sql("insert into medicine_price (pack_id, price, effective_from, source) values (:pack, :price, :date, 'Demo data')")
					.param("pack", packId).param("price", new BigDecimal(p.price())).param("date", today).update();
			if (p.usage() > 0) {
				jdbc.sql("insert into clinic_medicine (clinic_id, product_id, usage_count) values (:c, :p, :u)")
						.param("c", clinic.getId()).param("p", productId).param("u", p.usage()).update();
			}
		}
		log.warn("Dev seed: loaded {} demo medicines (demonstration data, not a verified registry).", products().size());
	}

	private long insertNamed(String table, String name, String aliasesLiteral) {
		String norm = name.toLowerCase();
		if (table.equals("ingredient")) {
			return jdbc.sql("insert into ingredient (name, normalized_name, aliases) values (:n, :norm, cast(:a as text[])) returning id")
					.param("n", name).param("norm", norm).param("a", aliasesLiteral == null ? "{}" : aliasesLiteral)
					.query(Long.class).single();
		}
		return jdbc.sql("insert into manufacturer (name, normalized_name, country) values (:n, :norm, 'Pakistan') returning id")
				.param("n", name).param("norm", norm).query(Long.class).single();
	}

	private static List<Product> products() {
		Ing paracetamol500 = Ing.of("Paracetamol", "500", "mg");
		Ing amoxicillin500 = Ing.of("Amoxicillin", "500", "mg");
		return List.of(
				new Product("panadol-500", "Panadol", "Haleon Pakistan", "Panadol 500mg Tablet", "Tablet", "Active", "Analgesics", 9,
						List.of(paracetamol500), "20 tablets", "tablet", "20", "Active", "45"),
				new Product("panadol-extra", "Panadol", "Haleon Pakistan", "Panadol Extra Tablet", "Tablet", "Active", "Analgesics", 0,
						List.of(paracetamol500, Ing.of("Caffeine", "65", "mg")), "20 tablets", "tablet", "20", "Active", "65"),
				new Product("panadol-syrup", "Panadol", "Haleon Pakistan", "Panadol Syrup", "Syrup", "Active", "Analgesics", 0,
						List.of(new Ing("Paracetamol", "250", "mg", "5", "mL")), "60 mL bottle", "mL", "60", "Active", "110"),
				new Product("calpol-syrup", "Calpol", "GSK Pakistan", "Calpol 250mg Syrup", "Syrup", "Under Review", "Analgesics", 0,
						List.of(new Ing("Paracetamol", "250", "mg", "5", "mL")), "60 mL bottle", "mL", "60", "Active", "105"),
				new Product("augmentin-625", "Augmentin", "GSK Pakistan", "Augmentin 625mg Tablet", "Tablet", "Active", "Antibiotics", 8,
						List.of(amoxicillin500, Ing.of("Clavulanic Acid", "125", "mg")), "6 tablets", "tablet", "6", "Active", "480"),
				new Product("amoxil-500", "Amoxil", "GSK Pakistan", "Amoxil 500mg Capsule", "Capsule", "Active", "Antibiotics", 7,
						List.of(amoxicillin500), "12 capsules", "capsule", "12", "Active", "210"),
				new Product("amoxil-250", "Amoxil", "GSK Pakistan", "Amoxil 250mg Capsule", "Capsule", "Active", "Antibiotics", 0,
						List.of(Ing.of("Amoxicillin", "250", "mg")), "12 capsules", "capsule", "12", "Active", "150"),
				new Product("flagyl-400", "Flagyl", "Searle Pakistan", "Flagyl 400mg Tablet", "Tablet", "Active", "Antibiotics", 6,
						List.of(Ing.of("Metronidazole", "400", "mg")), "14 tablets", "tablet", "14", "Active", "90"),
				new Product("brufen-400", "Brufen", "Abbott Pakistan", "Brufen 400mg Tablet", "Tablet", "Active", "Analgesics", 5,
						List.of(Ing.of("Ibuprofen", "400", "mg")), "30 tablets", "tablet", "30", "Active", "175"),
				new Product("brufen-syrup", "Brufen", "Abbott Pakistan", "Brufen Syrup", "Syrup", "Active", "Analgesics", 0,
						List.of(new Ing("Ibuprofen", "100", "mg", "5", "mL")), "100 mL bottle", "mL", "100", "Active", "130"),
				new Product("brufen-600", "Brufen", "Abbott Pakistan", "Brufen 600mg Tablet", "Tablet", "Discontinued", "Analgesics", 0,
						List.of(Ing.of("Ibuprofen", "600", "mg")), "30 tablets", "tablet", "30", "Inactive", "190"),
				new Product("risek-20", "Risek", "Getz Pharma", "Risek 20mg Capsule", "Capsule", "Active", "Antacids", 4,
						List.of(Ing.of("Omeprazole", "20", "mg")), "14 capsules", "capsule", "14", "Active", "260"),
				new Product("zyrtec-10", "Zyrtec", "GSK Pakistan", "Zyrtec 10mg Tablet", "Tablet", "Active", "Antihistamines", 3,
						List.of(Ing.of("Cetirizine", "10", "mg")), "10 tablets", "tablet", "10", "Active", "95"),
				new Product("motilium-10", "Motilium", "Haleon Pakistan", "Motilium 10mg Tablet", "Tablet", "Active", "Other", 2,
						List.of(Ing.of("Domperidone", "10", "mg")), "30 tablets", "tablet", "30", "Active", "220"),
				new Product("ponstan-500", "Ponstan", "Abbott Pakistan", "Ponstan Forte 500mg Tablet", "Tablet", "Active", "Analgesics", 1,
						List.of(Ing.of("Mefenamic Acid", "500", "mg")), "10 tablets", "tablet", "10", "Active", "140"),
				new Product("azomax-500", "Azomax", "Getz Pharma", "Azomax 500mg Tablet", "Tablet", "Active", "Antibiotics", 0,
						List.of(Ing.of("Azithromycin", "500", "mg")), "3 tablets", "tablet", "3", "Active", "380"));
	}
}
