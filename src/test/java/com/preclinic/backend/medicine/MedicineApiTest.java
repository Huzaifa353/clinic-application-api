package com.preclinic.backend.medicine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.preclinic.backend.ApiIntegrationTest;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.Role;

class MedicineApiTest extends ApiIntegrationTest {

	@Autowired
	MedicineService medicineService;

	AppUser doctor;
	AppUser assistant;
	String asDoctor;
	String asAssistant;

	@BeforeEach
	void setUp() {
		doctor = newUser("doc@test.local", "Dr. Test", Role.DOCTOR);
		assistant = newUser("asst@test.local", "Ayesha Assistant", Role.ASSISTANT);
		asDoctor = bearer(doctor);
		asAssistant = bearer(assistant);
	}

	// ----- request builders -----

	private static String ingredient(String name, String value, String unit) {
		return "{\"name\":\"" + name + "\",\"strengthValue\":" + value + ",\"strengthUnit\":\"" + unit + "\"}";
	}

	private static String medicineJson(String productName, String brand, String manufacturer, String form,
			List<String> ingredients, String packs) {
		return "{\"productName\":\"" + productName + "\",\"brandName\":\"" + brand + "\",\"manufacturerName\":\""
				+ manufacturer + "\",\"dosageForm\":\"" + form + "\",\"route\":\"Oral\",\"status\":\"Active\","
				+ "\"category\":\"Analgesics\",\"registrationNumber\":\"REG-1\",\"ingredients\":["
				+ String.join(",", ingredients) + "]" + (packs == null ? "" : ",\"packs\":" + packs) + "}";
	}

	private ResultActions create(String json, String as) throws Exception {
		return mvc.perform(post("/api/v1/medicines").header(HttpHeaders.AUTHORIZATION, as)
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long createOk(String json) throws Exception {
		// force: sibling products (Panadol 500mg / Panadol Extra) deliberately trip the duplicate warning
		String body = mvc.perform(post("/api/v1/medicines").param("force", "true").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private long panadol500() throws Exception {
		return createOk(medicineJson("Panadol 500mg Tablet", "Panadol", "Haleon Pakistan", "Tablet",
				List.of(ingredient("Paracetamol", "500", "mg")), "[{\"quantity\":20,\"unit\":\"tablet\",\"packDescription\":\"20 tablets\",\"price\":45}]"));
	}

	private long panadolExtra() throws Exception {
		return createOk(medicineJson("Panadol Extra Tablet", "Panadol", "Haleon Pakistan", "Tablet",
				List.of(ingredient("Paracetamol", "500", "mg"), ingredient("Caffeine", "65", "mg")), null));
	}

	private long calpolSyrup() throws Exception {
		return createOk(medicineJson("Calpol 250mg Syrup", "Calpol", "GSK Pakistan", "Syrup",
				List.of("{\"name\":\"Paracetamol\",\"strengthValue\":250,\"strengthUnit\":\"mg\",\"strengthPerValue\":5,\"strengthPerUnit\":\"mL\"}"), null));
	}

	private long brufen400() throws Exception {
		return createOk(medicineJson("Brufen 400mg Tablet", "Brufen", "Abbott Pakistan", "Tablet",
				List.of(ingredient("Ibuprofen", "400", "mg")), null));
	}

	private ResultActions search(String q) throws Exception {
		return mvc.perform(get("/api/v1/medicines/search").param("q", q).header(HttpHeaders.AUTHORIZATION, asDoctor));
	}

	// ===== create / read =====

	@Test
	void aMedicineIsCreatedWithItsLabelsPacksAndPrice() throws Exception {
		create(medicineJson("Panadol 500mg Tablet", "Panadol", "Haleon Pakistan", "Tablet",
				List.of(ingredient("Paracetamol", "500", "mg")),
				"[{\"quantity\":20,\"unit\":\"tablet\",\"packDescription\":\"20 tablets\",\"price\":45}]"), asDoctor)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.productName").value("Panadol 500mg Tablet"))
				.andExpect(jsonPath("$.brandName").value("Panadol"))
				.andExpect(jsonPath("$.manufacturerName").value("Haleon Pakistan"))
				.andExpect(jsonPath("$.genericName").value("Paracetamol"))
				.andExpect(jsonPath("$.strengthLabel").value("500mg"))
				.andExpect(jsonPath("$.compositionLabel").value("Paracetamol 500mg"))
				.andExpect(jsonPath("$.status").value("Active"))
				.andExpect(jsonPath("$.verificationStatus").value("Unverified"))
				.andExpect(jsonPath("$.clinicOwned").value(true))
				.andExpect(jsonPath("$.usageCount").value(0))
				.andExpect(jsonPath("$.isFavorite").value(false))
				.andExpect(jsonPath("$.packs", hasSize(1)))
				.andExpect(jsonPath("$.packs[0].packDescription").value("20 tablets"))
				.andExpect(jsonPath("$.packs[0].latestPrice").value(45.0))
				.andExpect(jsonPath("$.packs[0].currency").value("PKR"));
	}

	@Test
	void ingredientsKeepTheOrderTheyWereEntered() throws Exception {
		long id = panadolExtra();
		mvc.perform(get("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.genericName").value("Paracetamol + Caffeine"))
				.andExpect(jsonPath("$.strengthLabel").value("500mg + 65mg"))
				.andExpect(jsonPath("$.compositionLabel").value("Paracetamol 500mg + Caffeine 65mg"))
				.andExpect(jsonPath("$.ingredients[*].name", contains("Paracetamol", "Caffeine")));
	}

	@Test
	void perVolumeStrengthsReadNaturally() throws Exception {
		long id = calpolSyrup();
		mvc.perform(get("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.strengthLabel").value("250mg / 5mL"))
				.andExpect(jsonPath("$.compositionLabel").value("Paracetamol 250mg / 5mL"));
	}

	@Test
	void theSameBrandManufacturerAndIngredientAreSharedNotDuplicated() throws Exception {
		panadol500();
		panadolExtra();
		Long brands = jdbc.sql("select count(*) from brand where normalized_name = 'panadol'").query(Long.class).single();
		Long manufacturers = jdbc.sql("select count(*) from manufacturer where normalized_name = 'haleon pakistan'").query(Long.class).single();
		Long paracetamol = jdbc.sql("select count(*) from ingredient where normalized_name = 'paracetamol'").query(Long.class).single();
		assertThat(brands).isEqualTo(1);
		assertThat(manufacturers).isEqualTo(1);
		assertThat(paracetamol).isEqualTo(1);
	}

	@Test
	void anInvalidMedicineIsRefused() throws Exception {
		String ok = medicineJson("X", "X", "X", "Tablet", List.of(ingredient("A", "1", "mg")), null);
		assertThat(ok).isNotBlank();
		for (String bad : new String[] {
				medicineJson("X", "X", "X", "Tablet", List.of(), null),
				medicineJson("X", "X", "X", "Teleporter", List.of(ingredient("A", "1", "mg")), null),
				medicineJson("X", "X", "X", "Tablet", List.of(ingredient("A", "0", "mg")), null),
				medicineJson("", "X", "X", "Tablet", List.of(ingredient("A", "1", "mg")), null) }) {
			create(bad, asDoctor).andExpect(status().isBadRequest());
		}
		create(medicineJson("X", "X", "X", "Tablet", List.of(ingredient("A", "1", "mg"), ingredient("a", "2", "mg")), null),
				asDoctor).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("DUPLICATE_INGREDIENT"));
	}

	// ===== duplicates =====

	@Test
	void aLikelyDuplicateIsRefusedUnlessForced() throws Exception {
		long first = panadol500();
		String again = medicineJson("Panadol 500 mg", "panadol", "Haleon Pakistan", "Tablet",
				List.of(ingredient("PARACETAMOL", "500", "mg")), null);
		create(again, asDoctor).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("POSSIBLE_DUPLICATE"));
		mvc.perform(post("/api/v1/medicines").param("force", "true").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content(again)).andExpect(status().isCreated());

		mvc.perform(get("/api/v1/medicines/duplicates").param("brandName", "Panadol").param("dosageForm", "Tablet")
				.param("ingredient", "Paracetamol").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[0].product.id").value(first))
				.andExpect(jsonPath("$[0].reason").exists());
		// a different dosage form, or no shared ingredient, is not a duplicate
		mvc.perform(get("/api/v1/medicines/duplicates").param("brandName", "Panadol").param("dosageForm", "Syrup")
				.param("ingredient", "Paracetamol").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/v1/medicines/duplicates").param("brandName", "Panadol").param("dosageForm", "Tablet")
				.param("ingredient", "Ibuprofen").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	// ===== search (the prescription box) =====

	@Test
	void searchRanksExactThenPrefixThenContainsThenGeneric() throws Exception {
		long panadol = panadol500();
		long extra = panadolExtra();
		long calpol = calpolSyrup();
		brufen400();

		search("panadol 500mg tablet").andExpect(jsonPath("$[0].productId").value(panadol));
		// both are prefix matches (70); equal scores fall back to name order, "500mg" before "Extra"
		search("panadol").andExpect(jsonPath("$[*].productId", contains((int) panadol, (int) extra)));
		search("PARACETAMOL").andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[*].productId", org.hamcrest.Matchers.hasItems((int) panadol, (int) extra, (int) calpol)));
		search("caffeine").andExpect(jsonPath("$[*].productId", contains((int) extra)));
		search("ibuprofen").andExpect(jsonPath("$[0].name").value("Brufen 400mg Tablet"));
	}

	@Test
	void searchReturnsEverythingTheBoxNeedsToShow() throws Exception {
		panadol500();
		search("panadol").andExpect(jsonPath("$[0].name").value("Panadol 500mg Tablet"))
				.andExpect(jsonPath("$[0].genericName").value("Paracetamol"))
				.andExpect(jsonPath("$[0].strengthLabel").value("500mg"))
				.andExpect(jsonPath("$[0].dosageForm").value("Tablet"))
				.andExpect(jsonPath("$[0].manufacturerName").value("Haleon Pakistan"))
				.andExpect(jsonPath("$[0].isFavorite").value(false))
				.andExpect(jsonPath("$[0].usageCount").value(0));
	}

	@Test
	void searchByStrengthNumberAndDosageFormAndManufacturer() throws Exception {
		long panadol = panadol500();
		brufen400();
		search("500").andExpect(jsonPath("$[*].productId", contains((int) panadol)));
		search("haleon").andExpect(jsonPath("$[*].productId", contains((int) panadol)));
		search("tablet").andExpect(jsonPath("$", hasSize(2)));
	}

	@Test
	void aliasesAreSearchable() throws Exception {
		long panadol = panadol500();
		jdbc.sql("update medicine_product set aliases = '{\"PCM\"}' where id = :id").param("id", panadol).update();
		search("pcm").andExpect(jsonPath("$[*].productId", contains((int) panadol)));
	}

	@Test
	void favouritesAndFrequentlyUsedRiseToTheTop() throws Exception {
		long panadol = panadol500();
		long extra = panadolExtra();
		long calpol = calpolSyrup();
		// "paracetamol" is the exact generic name of Panadol 500 and Calpol (85 each) but only part of
		// Panadol Extra's "Paracetamol + Caffeine" (40). Ties fall back to name order.
		search("paracetamol").andExpect(jsonPath("$[*].productId", contains((int) calpol, (int) panadol, (int) extra)));

		// a favourite gets +15 ...
		mvc.perform(post("/api/v1/medicines/" + panadol + "/favorite").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.isFavorite").value(true));
		search("paracetamol").andExpect(jsonPath("$[*].productId", contains((int) panadol, (int) calpol, (int) extra)));
		mvc.perform(post("/api/v1/medicines/" + panadol + "/favorite").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.isFavorite").value(false));
		search("paracetamol").andExpect(jsonPath("$[0].productId").value(calpol));

		// ... and each use adds 1 (up to 10)
		medicineService.recordUsage(defaultClinic().getId(), List.of(panadol, panadol, panadol));
		search("paracetamol").andExpect(jsonPath("$[*].productId", contains((int) panadol, (int) calpol, (int) extra)))
				.andExpect(jsonPath("$[0].usageCount").value(3));
	}

	@Test
	void onlyActiveMedicinesAreOffered() throws Exception {
		long panadol = panadol500();
		mvc.perform(patch("/api/v1/medicines/" + panadol + "/status").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Discontinued\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("Discontinued"));
		search("panadol").andExpect(jsonPath("$", hasSize(0)));
		// but it is still in the library
		mvc.perform(get("/api/v1/medicines").param("q", "panadol").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.totalData").value(1));
	}

	@Test
	void searchTreatsWildcardsLiterallyAndBlankReturnsNothing() throws Exception {
		panadol500();
		search("%").andExpect(jsonPath("$", hasSize(0)));
		search("_anadol").andExpect(jsonPath("$", hasSize(0)));
		search("   ").andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void frequentAndRecentListsFollowUsage() throws Exception {
		long panadol = panadol500();
		long extra = panadolExtra();
		long brufen = brufen400();
		mvc.perform(get("/api/v1/medicines/frequent").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
		medicineService.recordUsage(defaultClinic().getId(), List.of(panadol, extra, extra, brufen));
		mvc.perform(get("/api/v1/medicines/frequent").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[0].productId").value(extra))
				.andExpect(jsonPath("$[0].usageCount").value(2))
				.andExpect(jsonPath("$", hasSize(3)));
		mvc.perform(get("/api/v1/medicines/recent").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].lastUsedAt").exists());
		mvc.perform(get("/api/v1/medicines/frequent").param("limit", "1").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void usageOfUnknownProductsIsIgnored() throws Exception {
		medicineService.recordUsage(defaultClinic().getId(), java.util.Arrays.asList(999999L, null));
		mvc.perform(get("/api/v1/medicines/frequent").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	// ===== library =====

	@Test
	void theLibraryFiltersSortsAndPages() throws Exception {
		panadol500();
		panadolExtra();
		calpolSyrup();
		brufen400();
		mvc.perform(get("/api/v1/medicines").param("sort", "name").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(4))
				.andExpect(jsonPath("$.data[*].productName", contains("Brufen 400mg Tablet", "Calpol 250mg Syrup",
						"Panadol 500mg Tablet", "Panadol Extra Tablet")));
		mvc.perform(get("/api/v1/medicines").param("form", "Syrup").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].productName", contains("Calpol 250mg Syrup")));
		mvc.perform(get("/api/v1/medicines").param("sort", "manufacturer").param("dir", "desc").param("limit", "2")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].manufacturerName").value("Haleon Pakistan"));
		mvc.perform(get("/api/v1/medicines").param("sort", "name").param("limit", "2").param("skip", "2")
				.header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.data[*].productName", contains("Panadol 500mg Tablet", "Panadol Extra Tablet")));
		mvc.perform(get("/api/v1/medicines").param("q", "reg-1").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.totalData").value(4));
		mvc.perform(get("/api/v1/medicines").param("sort", "name; drop table x").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isBadRequest());
	}

	@Test
	void theLibrarySummaryCounts() throws Exception {
		panadol500();
		long extra = panadolExtra();
		calpolSyrup();
		mvc.perform(patch("/api/v1/medicines/" + extra + "/status").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Inactive\"}"));
		mvc.perform(get("/api/v1/medicines/stats").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.active").value(2))
				.andExpect(jsonPath("$.brands").value(2))
				.andExpect(jsonPath("$.generics").value(2));
	}

	@Test
	void lookupsHelpTheFormAutocomplete() throws Exception {
		panadol500();
		brufen400();
		mvc.perform(get("/api/v1/medicines/ingredients").param("q", "para").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", contains("Paracetamol")));
		mvc.perform(get("/api/v1/medicines/manufacturers").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", contains("Abbott Pakistan", "Haleon Pakistan")));
		mvc.perform(get("/api/v1/medicines/brands").param("q", "BRU").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$", contains("Brufen")));
	}

	@Test
	void anUnknownMedicineIs404() throws Exception {
		mvc.perform(get("/api/v1/medicines/999999").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEDICINE_NOT_FOUND"));
	}

	// ===== edit =====

	@Test
	void editingRefreshesTheLabelsAndMovesToANewBrand() throws Exception {
		long id = panadol500();
		mvc.perform(put("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON)
				.content(medicineJson("Panadol Cold & Flu", "Panadol", "Haleon Pakistan", "Tablet",
						List.of(ingredient("Paracetamol", "500", "mg"), ingredient("Phenylephrine", "5", "mg")), null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.productName").value("Panadol Cold & Flu"))
				.andExpect(jsonPath("$.genericName").value("Paracetamol + Phenylephrine"))
				.andExpect(jsonPath("$.strengthLabel").value("500mg + 5mg"));
		search("phenylephrine").andExpect(jsonPath("$[0].productId").value(id));
		search("panadol cold").andExpect(jsonPath("$[0].name").value("Panadol Cold & Flu"));
		search("panadol 500mg tablet").andExpect(jsonPath("$[*].name", not(hasItem("Panadol 500mg Tablet"))));
	}

	@Test
	void editingPacksKeepsPriceHistoryAndDeactivatesRemovedPacks() throws Exception {
		long id = panadol500();
		String detail = mvc.perform(get("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andReturn().getResponse().getContentAsString();
		long packId = ((Number) JsonPath.read(detail, "$.packs[0].id")).longValue();
		String base = medicineJson("Panadol 500mg Tablet", "Panadol", "Haleon Pakistan", "Tablet",
				List.of(ingredient("Paracetamol", "500", "mg")), "\"PACKS\"");

		// same price -> no new history row; new price -> appended
		mvc.perform(put("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor).contentType(MediaType.APPLICATION_JSON)
				.content(base.replace("\"PACKS\"", "[{\"id\":" + packId + ",\"quantity\":20,\"unit\":\"tablet\",\"packDescription\":\"20 tablets\",\"price\":45}]")))
				.andExpect(status().isOk());
		assertThat(priceRows(packId)).isEqualTo(1);
		mvc.perform(put("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor).contentType(MediaType.APPLICATION_JSON)
				.content(base.replace("\"PACKS\"", "[{\"id\":" + packId + ",\"quantity\":20,\"unit\":\"tablet\",\"packDescription\":\"20 tablets\",\"price\":52},"
						+ "{\"quantity\":100,\"unit\":\"tablet\",\"packDescription\":\"100 tablets\",\"price\":210}]")))
				.andExpect(jsonPath("$.packs", hasSize(2)))
				.andExpect(jsonPath("$.packs[0].latestPrice").value(52.0));
		assertThat(priceRows(packId)).isEqualTo(2);

		// leaving a pack out deactivates it but keeps its history
		mvc.perform(put("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor).contentType(MediaType.APPLICATION_JSON)
				.content(base.replace("\"PACKS\"", "[{\"id\":" + packId + ",\"quantity\":20,\"unit\":\"tablet\",\"packDescription\":\"20 tablets\"}]")))
				.andExpect(jsonPath("$.packs[?(@.packDescription=='100 tablets')].status").value("Inactive"))
				.andExpect(jsonPath("$.packs[?(@.packDescription=='20 tablets')].status").value("Active"));
		assertThat(priceRows(packId)).isEqualTo(2);
	}

	private long priceRows(long packId) {
		return jdbc.sql("select count(*) from medicine_price where pack_id = :id").param("id", packId).query(Long.class).single();
	}

	@Test
	void aPackOfAnotherMedicineCannotBeEdited() throws Exception {
		long a = panadol500();
		long b = brufen400();
		String detail = mvc.perform(get("/api/v1/medicines/" + a).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andReturn().getResponse().getContentAsString();
		long foreignPack = ((Number) JsonPath.read(detail, "$.packs[0].id")).longValue();
		mvc.perform(put("/api/v1/medicines/" + b).header(HttpHeaders.AUTHORIZATION, asDoctor).contentType(MediaType.APPLICATION_JSON)
				.content(medicineJson("Brufen 400mg Tablet", "Brufen", "Abbott Pakistan", "Tablet",
						List.of(ingredient("Ibuprofen", "400", "mg")),
						"[{\"id\":" + foreignPack + ",\"quantity\":1,\"unit\":\"x\",\"packDescription\":\"stolen\"}]")))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("PACK_NOT_OF_PRODUCT"));
	}

	// ===== quick add =====

	@Test
	void quickAddMakesAnUnverifiedClinicRecordAndReusesIt() throws Exception {
		String first = mvc.perform(post("/api/v1/medicines/quick").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Home Remedy Syrup \"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Home Remedy Syrup"))
				.andExpect(jsonPath("$.genericName").value("Unspecified"))
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(first, "$.productId")).longValue();
		mvc.perform(post("/api/v1/medicines/quick").header(HttpHeaders.AUTHORIZATION, asDoctor)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"home remedy syrup\"}"))
				.andExpect(jsonPath("$.productId").value(id));

		mvc.perform(get("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.status").value("Unverified"))
				.andExpect(jsonPath("$.category").value("Manual Entry"))
				.andExpect(jsonPath("$.clinicOwned").value(true));
		search("home remedy").andExpect(jsonPath("$", hasSize(0))); // unverified entries are not suggested
		Long products = jdbc.sql("select count(*) from medicine_product where normalized_name = 'home remedy syrup'")
				.query(Long.class).single();
		assertThat(products).isEqualTo(1);
	}

	// ===== alternatives =====

	@Test
	void alternativesAreOtherBrandsWithTheSameIngredientsStrengthAndForm() throws Exception {
		long panadol = panadol500();
		long other = createOk(medicineJson("Calpol 500mg Tablet", "Calpol", "GSK Pakistan", "Tablet",
				List.of(ingredient("Paracetamol", "500", "mg")), null));
		panadolExtra();
		calpolSyrup();
		brufen400();
		mvc.perform(get("/api/v1/medicines/" + panadol + "/alternatives").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[*].id", contains((int) other)));
		mvc.perform(get("/api/v1/medicines/" + other + "/alternatives").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$[*].id", contains((int) panadol)));
	}

	// ===== permissions & isolation =====

	@Test
	void onlyTheDoctorChangesTheMedicineDatabase() throws Exception {
		long id = panadol500();
		create(medicineJson("X", "X", "X", "Tablet", List.of(ingredient("A", "1", "mg")), null), asAssistant)
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON)
				.content(medicineJson("X", "X", "X", "Tablet", List.of(ingredient("A", "1", "mg")), null)))
				.andExpect(status().isForbidden());
		mvc.perform(patch("/api/v1/medicines/" + id + "/status").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Inactive\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/medicines/" + id + "/favorite").header(HttpHeaders.AUTHORIZATION, asAssistant))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/medicines/quick").header(HttpHeaders.AUTHORIZATION, asAssistant)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
		// reading is fine for both
		mvc.perform(get("/api/v1/medicines/" + id).header(HttpHeaders.AUTHORIZATION, asAssistant)).andExpect(status().isOk());
	}

	@Test
	void aClinicsOwnMedicinesAreInvisibleToOthersButSharedOnesAreSeenByAll() throws Exception {
		long mine = panadol500();
		long shared = brufen400();
		jdbc.sql("update medicine_product set clinic_id = null where id = :id").param("id", shared).update();

		AppUser stranger = newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.DOCTOR);
		String asStranger = bearer(stranger);
		mvc.perform(get("/api/v1/medicines/" + mine).header(HttpHeaders.AUTHORIZATION, asStranger)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/medicines").header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(jsonPath("$.totalData").value(1))
				.andExpect(jsonPath("$.data[0].clinicOwned").value(false));
		mvc.perform(get("/api/v1/medicines/search").param("q", "panadol").header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/v1/medicines/search").param("q", "brufen").header(HttpHeaders.AUTHORIZATION, asStranger))
				.andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(patch("/api/v1/medicines/" + mine + "/status").header(HttpHeaders.AUTHORIZATION, asStranger)
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Inactive\"}")).andExpect(status().isNotFound());
		mvc.perform(put("/api/v1/medicines/" + mine).header(HttpHeaders.AUTHORIZATION, asStranger)
				.contentType(MediaType.APPLICATION_JSON)
				.content(medicineJson("Hijack", "Hijack", "Hijack", "Tablet", List.of(ingredient("A", "1", "mg")), null)))
				.andExpect(status().isNotFound());
	}

	@Test
	void usageAndFavouritesArePerClinicEvenForSharedMedicines() throws Exception {
		long shared = brufen400();
		jdbc.sql("update medicine_product set clinic_id = null where id = :id").param("id", shared).update();
		mvc.perform(post("/api/v1/medicines/" + shared + "/favorite").header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.isFavorite").value(true));
		AppUser stranger = newUser(otherClinic().getId(), "stranger@other.local", "Stranger", Role.DOCTOR);
		mvc.perform(get("/api/v1/medicines/" + shared).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
				.andExpect(jsonPath("$.isFavorite").value(false));
		mvc.perform(get("/api/v1/medicines/" + shared).header(HttpHeaders.AUTHORIZATION, asDoctor))
				.andExpect(jsonPath("$.isFavorite").value(true));
		assertThat(not(hasItem("x"))).isNotNull();
	}

	@Test
	void everythingNeedsALogin() throws Exception {
		mvc.perform(get("/api/v1/medicines")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/medicines/search").param("q", "a")).andExpect(status().isUnauthorized());
	}
}
