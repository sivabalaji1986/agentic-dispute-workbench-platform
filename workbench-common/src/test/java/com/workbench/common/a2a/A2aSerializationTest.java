package com.workbench.common.a2a;

import com.workbench.common.agui.EvidenceItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aSerializationTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void caseReviewResultRoundTripsWithCamelCaseFieldNames() {
        CaseReviewResult original = new CaseReviewResult(
                "D-10291", true, "SGD 250", "available", "Item was delivered",
                List.of(new EvidenceItem("Transaction record", true), new EvidenceItem("Merchant response", true)),
                "OPEN");

        String json = objectMapper.writeValueAsString(original);

        assertTrue(json.contains("\"caseId\":\"D-10291\""));
        assertTrue(json.contains("\"transactionFound\":true"));
        assertTrue(json.contains("\"transactionAmount\":\"SGD 250\""));
        assertTrue(json.contains("\"merchantResponse\":\"available\""));
        assertTrue(json.contains("\"merchantPosition\":\"Item was delivered\""));
        assertTrue(json.contains(
                "\"availableDocuments\":[{\"label\":\"Transaction record\",\"present\":true},"
                        + "{\"label\":\"Merchant response\",\"present\":true}]"));
        assertTrue(json.contains("\"caseStatus\":\"OPEN\""));

        CaseReviewResult roundTripped = objectMapper.readValue(json, CaseReviewResult.class);
        assertEquals(original, roundTripped);
    }

    @Test
    void caseReviewResultDefensivelyCopiesAvailableDocuments() {
        List<EvidenceItem> mutableDocs = new ArrayList<>();
        mutableDocs.add(new EvidenceItem("Transaction record", true));

        CaseReviewResult result = new CaseReviewResult(
                "D-10291", true, "SGD 250", "available", "Item was delivered", mutableDocs, "OPEN");
        mutableDocs.add(new EvidenceItem("Merchant response", true));

        assertEquals(1, result.availableDocuments().size());
    }

    @Test
    void policyResultRoundTripsWithCamelCaseFieldNames() {
        PolicyResult original = new PolicyResult(
                "GOODS_NOT_RECEIVED",
                "Section 4.2 — Goods Not Received",
                "This case qualifies as Goods Not Received because the customer claims "
                        + "non-delivery while the merchant asserts delivery.",
                List.of("TRANSACTION_RECORD", "MERCHANT_RESPONSE", "CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF"),
                "Potentially eligible, but evidence is incomplete.");

        String json = objectMapper.writeValueAsString(original);

        assertTrue(json.contains("\"disputeType\":\"GOODS_NOT_RECEIVED\""));
        assertTrue(json.contains("\"policySection\":\"Section 4.2"));
        assertTrue(json.contains("\"policyInterpretation\":\""));
        assertTrue(json.contains("\"requiredEvidence\":[\"TRANSACTION_RECORD\""));
        assertTrue(json.contains("\"policyOutcome\":\"Potentially eligible, but evidence is incomplete.\""));

        PolicyResult roundTripped = objectMapper.readValue(json, PolicyResult.class);
        assertEquals(original, roundTripped);
    }

    @Test
    void policyResultDefensivelyCopiesRequiredEvidence() {
        List<String> mutableEvidence = new ArrayList<>();
        mutableEvidence.add("TRANSACTION_RECORD");

        PolicyResult result = new PolicyResult(
                "GOODS_NOT_RECEIVED", "Section 4.2", "Interpretation", mutableEvidence, "Outcome");
        mutableEvidence.add("MERCHANT_RESPONSE");

        assertEquals(1, result.requiredEvidence().size());
    }

    @Test
    void caseReviewRequestAndPolicyRequestSerializeWithCamelCaseFieldNames() {
        String caseReviewJson = objectMapper.writeValueAsString(new CaseReviewRequest("D-10291", "GOODS_NOT_RECEIVED"));
        assertTrue(caseReviewJson.contains("\"caseId\":\"D-10291\""));
        assertTrue(caseReviewJson.contains("\"disputeType\":\"GOODS_NOT_RECEIVED\""));

        String policyJson = objectMapper.writeValueAsString(new PolicyRequest("GOODS_NOT_RECEIVED"));
        assertTrue(policyJson.contains("\"disputeType\":\"GOODS_NOT_RECEIVED\""));
    }
}
