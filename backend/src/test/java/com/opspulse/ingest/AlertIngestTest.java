package com.opspulse.ingest;

import com.opspulse.api.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AlertIngestTest extends ApiTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @Test
    void firstAlertOpensAnIncidentAndRepeatsAttachToIt() throws Exception {
        Sender s = newSender();

        var first = body(alert(s, trigger("cpu-high:web-03", "SEV2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED")));
        String incidentId = first.get("incidentId").asText();

        for (int i = 0; i < 3; i++) {
            alert(s, trigger("cpu-high:web-03", "SEV2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("ATTACHED"))
                    .andExpect(jsonPath("$.incidentId").value(incidentId));
        }

        String admin = adminToken();
        getAs("/api/v1/incidents/" + incidentId, admin)
                .andExpect(jsonPath("$.status").value("TRIGGERED"))
                .andExpect(jsonPath("$.alertCount").value(4))
                .andExpect(jsonPath("$.title").value("Problem: cpu-high:web-03"))
                .andExpect(jsonPath("$.serviceId").value(s.serviceId().toString()));
        getAs("/api/v1/incidents/" + incidentId + "/alerts", admin)
                .andExpect(jsonPath("$.totalElements").value(4));
        getAs("/api/v1/incidents/" + incidentId + "/timeline", admin)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("CREATED"))
                .andExpect(jsonPath("$[0].actorName").value("system"));
    }

    @Test
    void differentFingerprintsOrServicesAreDifferentIncidents() throws Exception {
        Sender a = newSender();
        Sender b = newSender();
        String i1 = body(alert(a, trigger("disk-full", "SEV3"))).get("incidentId").asText();
        String i2 = body(alert(a, trigger("disk-slow", "SEV3"))).get("incidentId").asText();
        // same fingerprint, other service: the service id is part of the dedup key
        String i3 = body(alert(b, trigger("disk-full", "SEV3")).andExpect(status().isCreated())).get("incidentId").asText();
        assertThat(java.util.Set.of(i1, i2, i3)).hasSize(3);
    }

    @Test
    void aWorseAlertRaisesSeverityButABetterOneNeverLowersIt() throws Exception {
        Sender s = newSender();
        String id = body(alert(s, trigger("latency", "SEV3"))).get("incidentId").asText();
        alert(s, trigger("latency", "SEV1"));
        alert(s, trigger("latency", "SEV4"));

        String admin = adminToken();
        getAs("/api/v1/incidents/" + id, admin).andExpect(jsonPath("$.severity").value("SEV1"));
        getAs("/api/v1/incidents/" + id + "/timeline", admin)
                .andExpect(jsonPath("$[1].type").value("SEVERITY_CHANGED"))
                .andExpect(jsonPath("$[1].payload.from").value("SEV3"))
                .andExpect(jsonPath("$[1].payload.to").value("SEV1"));
    }

    @Test
    void resolveClosesTheIncidentAndARecurrenceOpensANewOne() throws Exception {
        Sender s = newSender();
        String first = body(alert(s, trigger("queue-backlog", "SEV2"))).get("incidentId").asText();

        alert(s, resolveOf("queue-backlog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESOLVED"))
                .andExpect(jsonPath("$.incidentId").value(first));
        getAs("/api/v1/incidents/" + first, adminToken())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        // same problem next week -> NEW incident (this is why the unique index is partial)
        String second = body(alert(s, trigger("queue-backlog", "SEV2"))
                .andExpect(jsonPath("$.outcome").value("CREATED"))).get("incidentId").asText();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void resolveWithNothingOpenIsHarmless() throws Exception {
        alert(newSender(), resolveOf("never-fired"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_OPEN_INCIDENT"))
                .andExpect(jsonPath("$.incidentId").doesNotExist());
    }

    @Test
    void stalePointerInRedisDoesNotAttachToAResolvedIncident() throws Exception {
        Sender s = newSender();
        var r = body(alert(s, trigger("mem-leak", "SEV2")));
        String id = r.get("incidentId").asText();
        assertThat(redis.opsForValue().get("dedup:" + r.get("dedupKey").asText())).isEqualTo(id);

        // a human resolves it on "another replica": Postgres changes, the Redis pointer does not
        jdbc.update("UPDATE incidents SET status='RESOLVED', resolved_at=now() WHERE id=?::uuid", id);

        String next = body(alert(s, trigger("mem-leak", "SEV2"))
                .andExpect(jsonPath("$.outcome").value("CREATED"))).get("incidentId").asText();
        assertThat(next).isNotEqualTo(id);
        assertThat(redis.opsForValue().get("dedup:" + r.get("dedupKey").asText())).isEqualTo(next);
    }

    @Test
    void coldCacheStillDedupsThroughPostgres() throws Exception {
        Sender s = newSender();
        var r = body(alert(s, trigger("cold", "SEV3")));
        redis.delete("dedup:" + r.get("dedupKey").asText());
        alert(s, trigger("cold", "SEV3"))
                .andExpect(jsonPath("$.outcome").value("ATTACHED"))
                .andExpect(jsonPath("$.incidentId").value(r.get("incidentId").asText()));
    }

    @Test
    void validation() throws Exception {
        Sender s = newSender();
        alert(s, Map.of("fingerprint", "x", "summary", "no severity")).andExpect(status().isBadRequest());
        alert(s, Map.of("fingerprint", "x", "severity", "SEV2")).andExpect(status().isBadRequest());
        alert(s, Map.of("severity", "SEV2", "summary", "no fingerprint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fingerprint").exists());
        alert(s, Map.of("fingerprint", "x", "severity", "SEV9", "summary", "bad enum")).andExpect(status().isBadRequest());
    }

    @Test
    void incidentListFilters() throws Exception {
        Sender s = newSender();
        String open = body(alert(s, trigger("f-open", "SEV1"))).get("incidentId").asText();
        String closed = body(alert(s, trigger("f-closed", "SEV3"))).get("incidentId").asText();
        alert(s, resolveOf("f-closed"));
        String admin = adminToken();

        getAs("/api/v1/incidents?serviceId=" + s.serviceId(), admin).andExpect(jsonPath("$.totalElements").value(2));
        getAs("/api/v1/incidents?openOnly=true&serviceId=" + s.serviceId(), admin)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(open));
        getAs("/api/v1/incidents?status=RESOLVED&serviceId=" + s.serviceId(), admin)
                .andExpect(jsonPath("$.content[0].id").value(closed));
        getAs("/api/v1/incidents?severity=SEV1&serviceId=" + s.serviceId(), admin)
                .andExpect(jsonPath("$.totalElements").value(1));
        // viewers can read incidents too
        getAs("/api/v1/incidents?serviceId=" + s.serviceId(), tokenFor("VIEWER")).andExpect(status().isOk());
    }

    @Test
    void ingestRequiresAnApiKeyNotAJwt() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/alerts")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType("application/json").content(json.writeValueAsString(trigger("x", "SEV2"))))
                .andExpect(status().isUnauthorized());
    }
}
