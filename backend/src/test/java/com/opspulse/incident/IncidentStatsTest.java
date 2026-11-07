package com.opspulse.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.opspulse.api.ApiTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Other tests share the database, so this asserts DELTAS around its own
 * actions rather than absolute numbers.
 */
class IncidentStatsTest extends ApiTestSupport {

    @Test
    void statsReflectNewIncidentsAcksAndResolutions() throws Exception {
        String t = actor("RESPONDER").token();
        JsonNode before = stats(t);

        Sender s = newSender();
        String sev1 = body(alert(s, trigger("st-1", "SEV1"))).get("incidentId").asText();
        body(alert(s, trigger("st-2", "SEV3")));
        String toResolve = body(alert(s, trigger("st-3", "SEV2"))).get("incidentId").asText();
        send(post("/api/v1/incidents/" + sev1 + "/acknowledge"), t, null).andExpect(status().isOk());
        send(post("/api/v1/incidents/" + toResolve + "/resolve"), t, null).andExpect(status().isOk());

        JsonNode after = stats(t);
        assertThat(after.get("open").asLong() - before.get("open").asLong()).isEqualTo(2);
        assertThat(after.get("acknowledged").asLong() - before.get("acknowledged").asLong()).isEqualTo(1);
        assertThat(after.get("openBySeverity").get("SEV1").asLong() - before.get("openBySeverity").get("SEV1").asLong()).isEqualTo(1);
        assertThat(after.get("openBySeverity").get("SEV2").asLong() - before.get("openBySeverity").get("SEV2").asLong()).isZero();
        assertThat(after.get("resolvedLast24h").asLong() - before.get("resolvedLast24h").asLong()).isEqualTo(1);
        assertThat(after.get("openBySeverity").size()).isEqualTo(4);
        assertThat(after.get("mttaSeconds7d").isNumber()).isTrue();
        assertThat(after.get("mttrSeconds7d").isNumber()).isTrue();
    }

    private JsonNode stats(String token) throws Exception {
        return body(getAs("/api/v1/incidents/stats", token).andExpect(status().isOk()));
    }

    @Test
    void listIsOrderedForTriage() throws Exception {
        Sender s = newSender();
        String t = actor("RESPONDER").token();
        String sev4 = body(alert(s, trigger("o-4", "SEV4"))).get("incidentId").asText();
        String sev1acked = body(alert(s, trigger("o-1a", "SEV1"))).get("incidentId").asText();
        String sev2 = body(alert(s, trigger("o-2", "SEV2"))).get("incidentId").asText();
        String sev1 = body(alert(s, trigger("o-1", "SEV1"))).get("incidentId").asText();
        send(post("/api/v1/incidents/" + sev1acked + "/acknowledge"), t, null).andExpect(status().isOk());

        JsonNode content = body(getAs("/api/v1/incidents?openOnly=true&serviceId=" + s.serviceId(), t)).get("content");
        // triggered first (worst severity first), then acknowledged
        assertThat(java.util.stream.StreamSupport.stream(content.spliterator(), false).map(n -> n.get("id").asText()))
                .containsExactly(sev1, sev2, sev4, sev1acked);
    }
}
