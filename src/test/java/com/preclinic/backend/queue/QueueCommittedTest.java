package com.preclinic.backend.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.preclinic.backend.CommittedDataTest;

/**
 * The guarantees that only real commits can prove: a failed intake leaves nothing behind, and
 * simultaneous requests can never produce duplicate tokens, double check-ins or two patients with the doctor.
 */
class QueueCommittedTest extends CommittedDataTest {

	private int intake(long patientId, String extra, String as) throws Exception {
		return mvc.perform(post("/api/v1/queue/intake").header(HttpHeaders.AUTHORIZATION, as)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"patientId\":" + patientId + ",\"consultationFee\":1500" + (extra == null ? "" : "," + extra) + "}"))
				.andReturn().getResponse().getStatus();
	}

	private long count(String table) {
		return jdbc.sql("select count(*) from " + table).query(Long.class).single();
	}

	private <T> List<T> runTogether(int threads, List<Callable<T>> jobs) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(jobs.size());
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> job : jobs) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return job.call();
				}));
			}
			ready.await();
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get());
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	// --- all or nothing ---

	@Test
	void aFailedIntakeLeavesNoTokenVisitInvoiceOrCheckIn() throws Exception {
		long patient = commitPatient("Atomic Patient", "03001112223");
		long appointment = tx().execute(s -> jdbc.sql("""
				insert into appointment (clinic_id, appointment_no, patient_id, doctor_id, appt_date, appt_time)
				values (:c, 1, :p, :d, :date, '23:00') returning id
				""").param("c", clinicId).param("p", patient).param("d", doctor.getId()).param("date", clock.today())
				.query(Long.class).single());

		// pays more than the fee, which is only discovered AFTER the visit row was written
		assertThat(intake(patient, "\"amountPaid\":99999", asAssistant)).isEqualTo(422);

		assertThat(count("visit")).isZero();
		assertThat(count("invoice")).isZero();
		assertThat(count("audit_log")).isZero();
		assertThat(jdbc.sql("select status from appointment where id = :id").param("id", appointment)
				.query(String.class).single()).isEqualTo("scheduled");
		// and the token that was drawn was not used up
		assertThat(intake(patient, null, asAssistant)).isEqualTo(201);
		assertThat(jdbc.sql("select token_no from visit").query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select status from appointment where id = :id").param("id", appointment)
				.query(String.class).single()).isEqualTo("checked-in");
	}

	// --- simultaneous requests ---

	@Test
	void simultaneousIntakesNeverShareATokenOrASortPosition() throws Exception {
		int patients = 8;
		List<Callable<Integer>> jobs = new ArrayList<>();
		for (int i = 0; i < patients; i++) {
			long id = commitPatient("Parallel " + i, "0300000010" + i);
			jobs.add(() -> intake(id, null, asAssistant));
		}
		List<Integer> statuses = runTogether(patients, jobs);
		assertThat(statuses).containsOnly(201);
		List<Integer> tokens = jdbc.sql("select token_no from visit order by token_no").query(Integer.class).list();
		assertThat(tokens).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
		assertThat(jdbc.sql("select count(*) from invoice").query(Long.class).single()).isEqualTo(patients);
		assertThat(jdbc.sql("select count(distinct invoice_no) from invoice").query(Long.class).single()).isEqualTo(patients);
	}

	@Test
	void aDoubleClickOnTheSamePatientQueuesThemOnce() throws Exception {
		long patient = commitPatient("Double Click", "03005550000");
		List<Callable<Integer>> jobs = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			jobs.add(() -> intake(patient, null, asAssistant));
		}
		List<Integer> statuses = runTogether(4, jobs);
		assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
		assertThat(count("visit")).isEqualTo(1);
		assertThat(count("invoice")).isEqualTo(1);
	}

	@Test
	void onlyOnePatientCanBeWithTheDoctorEvenWhenCalledAtTheSameTime() throws Exception {
		List<Long> visits = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			long p = commitPatient("Queue " + i, "0300000020" + i);
			intake(p, null, asAssistant);
		}
		visits.addAll(jdbc.sql("select id from visit order by id").query(Long.class).list());
		List<Callable<Integer>> jobs = new ArrayList<>();
		for (Long visit : visits) {
			jobs.add(() -> mvc.perform(patch("/api/v1/queue/" + visit + "/status").header(HttpHeaders.AUTHORIZATION, asDoctor)
					.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"consulting\"}"))
					.andReturn().getResponse().getStatus());
		}
		List<Integer> statuses = runTogether(4, jobs);
		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
		assertThat(jdbc.sql("select count(*) from visit where status = 'consulting'").query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from notification").query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void simultaneousCallNextCallsExactlyOnePatient() throws Exception {
		for (int i = 0; i < 3; i++) {
			intake(commitPatient("Next " + i, "0300000030" + i), null, asAssistant);
		}
		List<Callable<Integer>> jobs = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			jobs.add(() -> mvc.perform(post("/api/v1/queue/call-next").header(HttpHeaders.AUTHORIZATION, asDoctor))
					.andReturn().getResponse().getStatus());
		}
		List<Integer> statuses = runTogether(4, jobs);
		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
		assertThat(jdbc.sql("select count(*) from visit where status = 'consulting'").query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from visit where status = 'waiting'").query(Long.class).single()).isEqualTo(2);
	}
}
