package com.opspulse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the two schema-level invariants the whole system rests on. If either
 * of these breaks, the application layer will keep compiling and the bug will
 * only show up as duplicate pages at 3am.
 */
class SchemaIntegrityTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void hibernateValidatesAgainstFlywaySchema() {
        // If the context started at all, ddl-auto=validate already passed:
        // every entity mapping matches a real column. This just asserts the
        // migration ran rather than Hibernate having created anything.
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void openIncidentsCannotShareADedupKey() {
        var ids = seedServiceAndTeam();

        jdbc.update("""
                INSERT INTO incidents (service_id, title, severity, status, dedup_key)
                VALUES (?::uuid, 'disk full on web-01', 'SEV2', 'TRIGGERED', 'dk-alpha')
                """, ids);

        // The whole point: a second OPEN incident with the same key is refused
        // by the database, not by application code that a race could bypass.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO incidents (service_id, title, severity, status, dedup_key)
                VALUES (?::uuid, 'disk full on web-01 again', 'SEV2', 'TRIGGERED', 'dk-alpha')
                """, ids))
                .hasMessageContaining("uq_incidents_open_dedup");
    }

    @Test
    void resolvingFreesTheDedupKeyForARecurrence() {
        var ids = seedServiceAndTeam();

        jdbc.update("""
                INSERT INTO incidents (service_id, title, severity, status, dedup_key)
                VALUES (?::uuid, 'latency spike', 'SEV3', 'TRIGGERED', 'dk-beta')
                """, ids);

        jdbc.update("UPDATE incidents SET status='RESOLVED', resolved_at=now() WHERE dedup_key='dk-beta'");

        // Same problem next week must open a NEW incident, not reopen the old
        // one. The index is partial precisely so this insert succeeds.
        jdbc.update("""
                INSERT INTO incidents (service_id, title, severity, status, dedup_key)
                VALUES (?::uuid, 'latency spike', 'SEV3', 'TRIGGERED', 'dk-beta')
                """, ids);

        Integer open = jdbc.queryForObject(
                "SELECT count(*) FROM incidents WHERE dedup_key='dk-beta' AND status <> 'RESOLVED'",
                Integer.class);
        assertThat(open).isEqualTo(1);
    }

    private String seedServiceAndTeam() {
        String teamId = jdbc.queryForObject(
                "INSERT INTO teams (name, slug) VALUES ('Platform', 'platform-" + System.nanoTime()
                        + "') RETURNING id::text", String.class);
        return jdbc.queryForObject("""
                INSERT INTO services (name, service_key, team_id)
                VALUES ('checkout-api', 'checkout-api-""" + System.nanoTime() + """
                ', ?::uuid) RETURNING id::text
                """, String.class, teamId);
    }
}
