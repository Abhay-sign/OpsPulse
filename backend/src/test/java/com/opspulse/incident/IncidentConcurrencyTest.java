package com.opspulse.incident;

import com.opspulse.api.ApiTestSupport;
import com.opspulse.domain.enums.Role;
import com.opspulse.security.AuthUser;
import com.opspulse.service.IncidentCommandService;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two responders press "Acknowledge" at the same instant. Exactly one must
 * win; the other must be told (409), never silently overwrite the winner.
 * Repeated because a race test that passes once proves little.
 */
class IncidentConcurrencyTest extends ApiTestSupport {

    @Autowired IncidentCommandService commands;
    @Autowired JdbcTemplate jdbc;

    @RepeatedTest(5)
    void simultaneousAcknowledgesHaveExactlyOneWinner() throws Exception {
        UUID id = UUID.fromString(body(alert(newSender(), trigger(unique("race"), "SEV1"))).get("incidentId").asText());
        Actor a = actor("RESPONDER"), b = actor("RESPONDER");
        AuthUser ua = new AuthUser(UUID.fromString(a.id()), "a", Role.RESPONDER);
        AuthUser ub = new AuthUser(UUID.fromString(b.id()), "b", Role.RESPONDER);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (AuthUser u : List.of(ua, ub)) {
            futures.add(pool.submit(() -> { go.await(); return commands.acknowledge(id, u, null); }));
        }
        go.countDown();

        int ok = 0, conflicts = 0;
        for (var f : futures) {
            try {
                f.get(20, TimeUnit.SECONDS);
                ok++;
            } catch (ExecutionException e) {
                // either the loser read the committed ACKNOWLEDGED state (ConflictException)
                // or it read the same version and lost at flush (optimistic lock)
                String name = e.getCause().getClass().getSimpleName();
                assertThat(name).matches("ConflictException|.*OptimisticLock.*");
                conflicts++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        // and the database agrees: one ACKNOWLEDGED event, version 1
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_events WHERE incident_id=? AND type='ACKNOWLEDGED'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM incidents WHERE id=?", Integer.class, id)).isEqualTo(1);
    }
}
