package com.preclinic.backend.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.preclinic.backend.security.JwtService;
import com.preclinic.backend.user.AppUser;
import com.preclinic.backend.user.AppUserRepository;
import com.preclinic.backend.user.ClinicRepository;
import com.preclinic.backend.user.Role;

/**
 * Real concurrency, so deliberately NOT @Transactional: every request commits on its own, exactly like
 * production. Proves two cashiers can never overpay the same invoice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingConcurrencyTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	AppUserRepository users;
	@Autowired
	ClinicRepository clinics;
	@Autowired
	PasswordEncoder encoder;
	@Autowired
	JwtService jwt;
	@Autowired
	PlatformTransactionManager transactionManager;

	long clinicId;
	long patientId;
	long invoiceId;
	String bearer;

	@BeforeEach
	void commitSetup() {
		clinicId = clinics.findFirstByOrderByIdAsc().orElseThrow().getId();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			AppUser user = new AppUser();
			user.setClinicId(clinicId);
			user.setEmail("cashier@concurrency.test");
			user.setFullName("Concurrent Cashier");
			user.setRole(Role.ASSISTANT);
			user.setPasswordHash(encoder.encode("x"));
			user = users.save(user);
			bearer = "Bearer " + jwt.issue(user).value();
			patientId = jdbc.sql("""
					insert into patient (clinic_id, patient_no, name, mobile, mobile_norm)
					values (:c, (select coalesce(max(patient_no), 0) + 1 from patient where clinic_id = :c), 'CT Patient', '0300', '0300')
					returning id
					""").param("c", clinicId).query(Long.class).single();
			invoiceId = jdbc.sql("""
					insert into invoice (clinic_id, invoice_no, patient_id, consultation_fee, total)
					values (:c, (select coalesce(max(invoice_no), 0) + 1 from invoice where clinic_id = :c), :p, 1000, 1000)
					returning id
					""").param("c", clinicId).param("p", patientId).query(Long.class).single();
		});
	}

	@AfterEach
	void cleanUp() {
		// TRUNCATE does not fire the row triggers that make payments and invoices undeletable.
		jdbc.sql("truncate payment, invoice").update();
		jdbc.sql("delete from audit_log where user_id in (select id from app_user where email like '%@concurrency.test')").update();
		jdbc.sql("delete from patient where name = 'CT Patient'").update();
		jdbc.sql("delete from app_user where email like '%@concurrency.test'").update();
		jdbc.sql("delete from number_sequence where kind in ('receipt','invoice') and clinic_id = :c")
				.param("c", clinicId).update();
	}

	private int pay(String amount) throws Exception {
		return mvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments").header(HttpHeaders.AUTHORIZATION, bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + ",\"method\":\"Cash\"}"))
				.andReturn().getResponse().getStatus();
	}

	private List<Integer> payConcurrently(int callers, String amount) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(callers);
		CountDownLatch ready = new CountDownLatch(callers);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				Callable<Integer> job = () -> {
					ready.countDown();
					go.await();
					return pay(amount);
				};
				futures.add(pool.submit(job));
			}
			ready.await();
			go.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : futures) {
				statuses.add(f.get());
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void twoCashiersCannotBothTakeTheLastOfTheMoney() throws Exception {
		List<Integer> statuses = payConcurrently(2, "600");
		assertThat(statuses).containsExactlyInAnyOrder(201, 422);
		assertThat(totalPaid()).isEqualByComparingTo("600");
	}

	@Test
	void manySmallPaymentsAtOnceAddUpToExactlyTheTotalAndNoMore() throws Exception {
		List<Integer> statuses = payConcurrently(8, "250");
		assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(4);
		assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(4);
		assertThat(totalPaid()).isEqualByComparingTo("1000");
		String status = jdbc.sql("select payment_status from invoice_summary where id = :id").param("id", invoiceId)
				.query(String.class).single();
		assertThat(status).isEqualTo("Paid");
		// receipt numbers were handed out without duplicates
		Long distinct = jdbc.sql("select count(distinct receipt_no) from payment where invoice_id = :id")
				.param("id", invoiceId).query(Long.class).single();
		assertThat(distinct).isEqualTo(4);
	}

	private BigDecimal totalPaid() {
		return jdbc.sql("select coalesce(sum(amount), 0) from payment where invoice_id = :id").param("id", invoiceId)
				.query(BigDecimal.class).single();
	}
}
