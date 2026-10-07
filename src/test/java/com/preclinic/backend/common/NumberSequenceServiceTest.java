package com.preclinic.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.preclinic.backend.user.ClinicRepository;

/** Not @Transactional on purpose: these tests need real, separately committed transactions. */
@SpringBootTest
@ActiveProfiles("test")
class NumberSequenceServiceTest {

	@Autowired
	NumberSequenceService sequence;
	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	ClinicRepository clinics;

	TransactionTemplate tx;
	long clinicId;
	String scope;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		clinicId = clinics.findFirstByOrderByIdAsc().orElseThrow().getId();
		scope = "test-" + UUID.randomUUID();
	}

	@AfterEach
	void cleanUp() {
		jdbc.sql("delete from number_sequence where scope like 'test-%'").update();
	}

	private long next() {
		return tx.execute(status -> sequence.next(clinicId, SequenceKind.TOKEN, scope));
	}

	@Test
	void countsUpFromOne() {
		assertThat(next()).isEqualTo(1);
		assertThat(next()).isEqualTo(2);
		assertThat(next()).isEqualTo(3);
	}

	@Test
	void eachScopeAndKindHasItsOwnCounter() {
		assertThat(next()).isEqualTo(1);
		assertThat(next()).isEqualTo(2);
		long otherScope = tx.execute(s -> sequence.next(clinicId, SequenceKind.TOKEN, scope + "-b"));
		long otherKind = tx.execute(s -> sequence.next(clinicId, SequenceKind.INVOICE, scope));
		assertThat(otherScope).isEqualTo(1);
		assertThat(otherKind).isEqualTo(1);
		assertThat(next()).isEqualTo(3);
	}

	@Test
	void mustRunInsideATransaction() {
		assertThatThrownBy(() -> sequence.next(clinicId, SequenceKind.TOKEN, scope))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void aRolledBackTransactionDoesNotBurnANumber() {
		assertThat(next()).isEqualTo(1);
		tx.executeWithoutResult(status -> {
			assertThat(sequence.next(clinicId, SequenceKind.TOKEN, scope)).isEqualTo(2);
			status.setRollbackOnly();
		});
		assertThat(next()).isEqualTo(2);
	}

	@Test
	void concurrentCallersNeverGetTheSameNumber() throws Exception {
		int callers = 24;
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Long>> jobs = IntStream.range(0, callers).<Callable<Long>>mapToObj(i -> this::next).toList();
			List<Long> numbers = pool.invokeAll(jobs).stream().map(NumberSequenceServiceTest::get).toList();
			assertThat(numbers).doesNotHaveDuplicates();
			assertThat(numbers).containsExactlyInAnyOrderElementsOf(
					IntStream.rangeClosed(1, callers).mapToObj(Long::valueOf).toList());
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static Long get(Future<Long> future) {
		try {
			return future.get();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
