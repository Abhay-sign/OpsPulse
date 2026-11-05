package com.opspulse.ingest;

import com.opspulse.api.ApiTestSupport;
import com.opspulse.domain.enums.Severity;
import com.opspulse.web.dto.AlertDtos.AlertRequest;
import com.opspulse.web.dto.AlertDtos.IngestResponse;
import com.opspulse.web.dto.AlertDtos.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guarantee the whole product rests on, under real contention: N requests
 * with the same fingerprint, released at the same instant against real
 * Postgres, must produce exactly ONE incident holding all N alerts.
 *
 * The cache is cold (fresh service => no Redis pointer), so every thread takes
 * the database path and they genuinely race on INSERT ... ON CONFLICT.
 */
class IngestConcurrencyTest extends ApiTestSupport {

    private static final int THREADS = 25;

    @Autowired AlertIngestService ingest;
    @Autowired JdbcTemplate jdbc;

    @Test
    void simultaneousIdenticalAlertsOpenExactlyOneIncident() throws Exception {
        Sender s = newSender();
        var req = new AlertRequest("storm:db-01", null, Severity.SEV1, "Database down", null, "load-test", null);

        List<IngestResponse> results = fireConcurrently(() -> ingest.ingest(s.principal(), req));

        assertThat(results.stream().filter(r -> r.outcome() == Outcome.CREATED)).hasSize(1);
        assertThat(results.stream().filter(r -> r.outcome() == Outcome.ATTACHED)).hasSize(THREADS - 1);
        assertThat(results.stream().map(IngestResponse::incidentId).distinct()).hasSize(1);

        var row = jdbc.queryForMap("SELECT count(*) AS n, max(alert_count) AS alerts FROM incidents WHERE service_id = ?",
                s.serviceId());
        assertThat(((Number) row.get("n")).intValue()).isEqualTo(1);
        // no lost increments: the counter matches the number of alert rows
        assertThat(((Number) row.get("alerts")).intValue()).isEqualTo(THREADS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts WHERE service_id = ?", Integer.class, s.serviceId()))
                .isEqualTo(THREADS);
    }

    @Test
    void concurrentDifferentFingerprintsDoNotBlockEachOther() throws Exception {
        Sender s = newSender();
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        List<IngestResponse> results = fireConcurrently(() -> ingest.ingest(s.principal(),
                new AlertRequest("host-" + counter.incrementAndGet(), null, Severity.SEV3, "x", null, null, null)));
        assertThat(results).allMatch(r -> r.outcome() == Outcome.CREATED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incidents WHERE service_id = ?", Integer.class, s.serviceId()))
                .isEqualTo(THREADS);
    }

    private List<IngestResponse> fireConcurrently(Callable<IngestResponse> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<IngestResponse>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();          // every thread waits here, then all fire together
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<IngestResponse> out = new ArrayList<>();
            for (var f : futures) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
