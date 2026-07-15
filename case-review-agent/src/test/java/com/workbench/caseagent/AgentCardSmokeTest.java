package com.workbench.caseagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the real Spring context (AgentCard bean + A2A autoconfiguration) and hits the
 * real HTTP endpoint. None of this session's other tests exercise this wiring — see
 * this task's brief for why this is added beyond the session prompt's literal test list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentCardSmokeTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void agentCardEndpoint_returnsContractShape() {
        String body = restTemplate.getForObject(
                "http://localhost:" + port + "/.well-known/agent-card.json", String.class);

        assertTrue(body.contains("\"name\":\"case-review-agent\""));
        assertTrue(body.contains("\"description\":\"Reviews operational case facts"));
        assertTrue(body.contains("\"version\":\"1.0.0\""));
        assertTrue(body.contains("\"streaming\":false"));
    }
}
