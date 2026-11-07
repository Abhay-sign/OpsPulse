package com.opspulse.incident;

import com.opspulse.api.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IncidentLifecycleTest extends ApiTestSupport {

    @Autowired StringRedisTemplate redis;

    private String openIncident() throws Exception {
        return body(alert(newSender(), trigger(unique("fp"), "SEV2"))).get("incidentId").asText();
    }

    private String url(String id, String action) { return "/api/v1/incidents/" + id + "/" + action; }

    @Test
    void acknowledgeTakesOwnershipAndIsOnTheTimeline() throws Exception {
        String id = openIncident();
        Actor alice = actor("RESPONDER");

        send(post(url(id, "acknowledge")), alice.token(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedAt").isNotEmpty())
                .andExpect(jsonPath("$.assignedUserId").value(alice.id()))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

        getAs(url(id, "timeline"), alice.token())
                .andExpect(jsonPath("$[1].type").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$[1].actorId").value(alice.id()));
    }

    @Test
    void illegalTransitionsAreConflicts() throws Exception {
        String id = openIncident();
        String t = actor("RESPONDER").token();
        send(post(url(id, "acknowledge")), t, null).andExpect(status().isOk());
        send(post(url(id, "acknowledge")), t, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cannot acknowledge an incident that is already ACKNOWLEDGED"));
        send(post(url(id, "resolve")), t, null).andExpect(status().isOk());
        send(post(url(id, "resolve")), t, null).andExpect(status().isConflict());
        send(post(url(id, "acknowledge")), t, null).andExpect(status().isConflict());
        send(patch(url(id, "severity")), t, Map.of("severity", "SEV1")).andExpect(status().isConflict());
    }

    @Test
    void resolveWithoutAckIsAllowedAndRecordsWhoAndWhy() throws Exception {
        String id = openIncident();
        Actor bob = actor("RESPONDER");
        send(post(url(id, "resolve")), bob.token(), Map.of("note", "rolled back deploy 4812"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedByUserId").value(bob.id()));
        getAs(url(id, "timeline"), bob.token())
                .andExpect(jsonPath("$[1].message").value(org.hamcrest.Matchers.endsWith(": rolled back deploy 4812")));
    }

    @Test
    void humanResolveDropsTheDedupPointerSoTheNextAlertOpensANewIncident() throws Exception {
        Sender s = newSender();
        var first = body(alert(s, trigger("db-conn", "SEV2")));
        String key = "dedup:" + first.get("dedupKey").asText();
        assertThat(redis.hasKey(key)).isTrue();

        send(post(url(first.get("incidentId").asText(), "resolve")), actor("RESPONDER").token(), null)
                .andExpect(status().isOk());
        assertThat(redis.hasKey(key)).isFalse(); // evicted by the after-commit listener

        alert(s, trigger("db-conn", "SEV2")).andExpect(jsonPath("$.outcome").value("CREATED"));
    }

    @Test
    void viewersCannotAct() throws Exception {
        String id = openIncident();
        String viewer = tokenFor("VIEWER");
        getAs("/api/v1/incidents/" + id, viewer).andExpect(status().isOk());
        send(post(url(id, "acknowledge")), viewer, null).andExpect(status().isForbidden());
        send(post(url(id, "notes")), viewer, Map.of("message", "hi")).andExpect(status().isForbidden());
    }

    @Test
    void reassignRules() throws Exception {
        String id = openIncident();
        Actor lead = actor("RESPONDER");
        Actor carol = actor("RESPONDER");
        Actor viewer = actor("VIEWER");

        send(post(url(id, "reassign")), lead.token(), Map.of("userId", carol.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedUserId").value(carol.id()));
        getAs(url(id, "timeline"), lead.token())
                .andExpect(jsonPath("$[1].type").value("REASSIGNED"))
                .andExpect(jsonPath("$[1].payload.toUserId").value(carol.id()));

        // reassigning to the current owner is a no-op: no new version, no timeline noise
        send(post(url(id, "reassign")), lead.token(), Map.of("userId", carol.id()))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));
        getAs(url(id, "timeline"), lead.token()).andExpect(jsonPath("$.length()").value(2));

        send(post(url(id, "reassign")), lead.token(), Map.of("userId", viewer.id())).andExpect(status().isBadRequest());
        send(post(url(id, "reassign")), lead.token(), Map.of("userId", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());

        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/users/" + carol.id()),
                adminToken(), null);
        Actor dave = actor("RESPONDER");
        send(post(url(id, "reassign")), lead.token(), Map.of("userId", dave.id())).andExpect(status().isOk());
        send(post(url(id, "reassign")), lead.token(), Map.of("userId", carol.id())).andExpect(status().isBadRequest());
    }

    @Test
    void notesAreAllowedAfterResolutionAndDontBumpTheVersion() throws Exception {
        String id = openIncident();
        String t = actor("RESPONDER").token();
        send(post(url(id, "resolve")), t, null);
        String etag = getAs("/api/v1/incidents/" + id, t).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        send(post(url(id, "notes")), t, Map.of("message", "Root cause: expired TLS cert")).andExpect(status().isCreated());
        send(post(url(id, "notes")), t, Map.of("message", "  ")).andExpect(status().isBadRequest());

        getAs("/api/v1/incidents/" + id, t).andExpect(header().string(HttpHeaders.ETAG, etag));
        getAs(url(id, "timeline"), t).andExpect(jsonPath("$[2].type").value("NOTE_ADDED"))
                .andExpect(jsonPath("$[2].message").value("Root cause: expired TLS cert"));
    }

    @Test
    void manualSeverityChange() throws Exception {
        String id = openIncident();
        String t = actor("RESPONDER").token();
        send(patch(url(id, "severity")), t, Map.of("severity", "SEV4"))
                .andExpect(jsonPath("$.severity").value("SEV4"));
        getAs(url(id, "timeline"), t)
                .andExpect(jsonPath("$[1].type").value("SEVERITY_CHANGED"))
                .andExpect(jsonPath("$[1].payload.from").value("SEV2"));
    }

    @Test
    void ifMatchRejectsActionsOnAStaleView() throws Exception {
        String id = openIncident();
        String alice = actor("RESPONDER").token();
        String bob = actor("RESPONDER").token();

        // both open the incident page at version 0
        String seen = getAs("/api/v1/incidents/" + id, alice).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(seen).isEqualTo("\"0\"");

        send(post(url(id, "acknowledge")).header(HttpHeaders.IF_MATCH, seen), alice, null).andExpect(status().isOk());

        // bob still looks at version 0 and clicks resolve: refused, he must look again first
        send(post(url(id, "resolve")).header(HttpHeaders.IF_MATCH, seen), bob, null)
                .andExpect(status().isPreconditionFailed());
        send(post(url(id, "resolve")).header(HttpHeaders.IF_MATCH, "\"1\""), bob, null).andExpect(status().isOk());
    }

    @Test
    void newAlertsDoNotInvalidateARespondersView() throws Exception {
        // the reason ingest bumps alert_count with raw SQL instead of through @Version
        Sender s = newSender();
        String id = body(alert(s, trigger("flappy", "SEV3"))).get("incidentId").asText();
        String t = actor("RESPONDER").token();
        String etag = send(post(url(id, "acknowledge")), t, null).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        for (int i = 0; i < 3; i++) alert(s, trigger("flappy", "SEV3")).andExpect(jsonPath("$.outcome").value("ATTACHED"));

        send(post(url(id, "resolve")).header(HttpHeaders.IF_MATCH, etag), t, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertCount").value(4)); // and @DynamicUpdate kept the counter intact
    }

    @Test
    void malformedIfMatchIsABadRequest() throws Exception {
        send(post(url(openIncident(), "acknowledge")).header(HttpHeaders.IF_MATCH, "abc"),
                actor("RESPONDER").token(), null).andExpect(status().isBadRequest());
    }
}
