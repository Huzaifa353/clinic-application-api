package com.preclinic.backend.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReportBucketsTest {

	private static List<ReportService.Bucket> buckets(String from, String to) {
		return ReportService.buckets(LocalDate.parse(from), LocalDate.parse(to));
	}

	@Test
	void shortRangesAreDaily() {
		var oneDay = buckets("2026-10-07", "2026-10-07");
		assertThat(oneDay).hasSize(1);
		assertThat(oneDay.get(0).label()).isEqualTo("07 Oct");
		assertThat(buckets("2026-10-01", "2026-10-31")).hasSize(31);
	}

	@Test
	void rangesOver31DaysAreWeekly() {
		var weeks = buckets("2026-09-01", "2026-10-02"); // 32 days
		assertThat(weeks).hasSize(5);
		assertThat(weeks.get(0).label()).isEqualTo("Week 1");
		assertThat(weeks.get(4).label()).isEqualTo("Week 5");
		// the last week is clipped to the range, and every day is in exactly one bucket
		assertThat(weeks.get(4).start()).isEqualTo(LocalDate.parse("2026-09-29"));
		assertThat(weeks.get(4).end()).isEqualTo(LocalDate.parse("2026-10-02"));
		assertThat(buckets("2026-01-01", "2026-06-29")).hasSize(26); // 180 days
	}

	@Test
	void rangesOver180DaysAreMonthlyAndClippedAtTheEdges() {
		var months = buckets("2026-01-15", "2026-07-20"); // 187 days
		assertThat(months).hasSize(7);
		assertThat(months.get(0).label()).isEqualTo("Jan 26");
		assertThat(months.get(0).start()).isEqualTo(LocalDate.parse("2026-01-15"));
		assertThat(months.get(0).end()).isEqualTo(LocalDate.parse("2026-01-31"));
		assertThat(months.get(6).label()).isEqualTo("Jul 26");
		assertThat(months.get(6).end()).isEqualTo(LocalDate.parse("2026-07-20"));
	}

	@Test
	void everyDayLandsInExactlyOneBucket() {
		for (String[] range : new String[][] { { "2026-03-01", "2026-03-20" }, { "2026-03-01", "2026-05-30" },
				{ "2025-11-15", "2026-08-02" } }) {
			var buckets = buckets(range[0], range[1]);
			for (LocalDate d = LocalDate.parse(range[0]); !d.isAfter(LocalDate.parse(range[1])); d = d.plusDays(1)) {
				final LocalDate day = d;
				assertThat(buckets.stream().filter(b -> b.contains(day)).count()).as(day.toString()).isEqualTo(1);
			}
		}
	}
}
