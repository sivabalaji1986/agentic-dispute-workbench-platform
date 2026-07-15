package com.workbench.common.merge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceReadinessTest {

    private static final List<String> ALL_REQUIRED = List.of(
            "TRANSACTION_RECORD", "MERCHANT_RESPONSE", "CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF");

    @Test
    void allPresentYieldsNoMissingItems() {
        EvidenceReadiness result = EvidenceReadiness.compute(ALL_REQUIRED, ALL_REQUIRED);

        assertEquals(4, result.present());
        assertEquals(4, result.required());
        assertTrue(result.missingItems().isEmpty());
        assertEquals("4 of 4 required items present", result.label());
    }

    @Test
    void demoCaseTwoOfFourPresent() {
        List<String> available = List.of("TRANSACTION_RECORD", "MERCHANT_RESPONSE");

        EvidenceReadiness result = EvidenceReadiness.compute(available, ALL_REQUIRED);

        assertEquals(2, result.present());
        assertEquals(4, result.required());
        assertEquals(List.of("Customer declaration", "Delivery / non-delivery proof"), result.missingItems());
        assertEquals("2 of 4 required items present", result.label());
    }

    @Test
    void emptyAvailableMeansAllRequiredAreMissing() {
        EvidenceReadiness result = EvidenceReadiness.compute(List.of(), ALL_REQUIRED);

        assertEquals(0, result.present());
        assertEquals(4, result.required());
        assertEquals(
                List.of("Transaction record", "Merchant response", "Customer declaration",
                        "Delivery / non-delivery proof"),
                result.missingItems());
        assertEquals("0 of 4 required items present", result.label());
    }

    @Test
    void matchIsCaseInsensitive() {
        List<String> available = List.of("transaction_record", "Merchant_Response");

        EvidenceReadiness result = EvidenceReadiness.compute(available, ALL_REQUIRED);

        assertEquals(2, result.present());
        assertEquals(List.of("Customer declaration", "Delivery / non-delivery proof"), result.missingItems());
        assertEquals("2 of 4 required items present", result.label());
    }
}
