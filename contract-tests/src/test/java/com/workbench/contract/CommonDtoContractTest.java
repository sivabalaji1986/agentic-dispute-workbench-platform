package com.workbench.contract;

import com.workbench.common.a2a.CaseReviewResult;
import com.workbench.common.a2a.PolicyResult;
import com.workbench.common.merge.EvidenceReadiness;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonDtoContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void caseReviewResultMatchesFixture() throws IOException {
        String fixture = loadFixture("case-review-result.json");

        CaseReviewResult deserialized = objectMapper.readValue(fixture, CaseReviewResult.class);
        String reserialized = objectMapper.writeValueAsString(deserialized);

        assertEquals(fixture, reserialized);
    }

    @Test
    void policyResultMatchesFixture() throws IOException {
        String fixture = loadFixture("policy-result.json");

        PolicyResult deserialized = objectMapper.readValue(fixture, PolicyResult.class);
        String reserialized = objectMapper.writeValueAsString(deserialized);

        assertEquals(fixture, reserialized);
    }

    @Test
    void evidenceReadinessMatchesFixture() throws IOException {
        String fixture = loadFixture("evidence-readiness.json");

        EvidenceReadiness deserialized = objectMapper.readValue(fixture, EvidenceReadiness.class);
        String reserialized = objectMapper.writeValueAsString(deserialized);

        assertEquals(fixture, reserialized);
    }

    private static String loadFixture(String name) throws IOException {
        try (InputStream in = CommonDtoContractTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }
}
