package com.workbench.common.merge;

import com.workbench.common.agui.EvidenceItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceReadinessTest {

    private static final List<String> ALL_REQUIRED = List.of(
            "TRANSACTION_RECORD", "MERCHANT_RESPONSE", "CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF");

    @Test
    void allPresentYieldsNoMissingItems() {
        List<EvidenceItem> available = List.of(
                new EvidenceItem("Transaction record", true),
                new EvidenceItem("Merchant response", true),
                new EvidenceItem("Customer declaration", true),
                new EvidenceItem("Delivery / non-delivery proof", true));

        EvidenceReadiness result = EvidenceReadiness.compute(available, ALL_REQUIRED);

        assertEquals(4, result.present());
        assertEquals(4, result.required());
        assertTrue(result.missingItems().isEmpty());
        assertEquals("4 of 4 required items present", result.label());
    }

    @Test
    void demoCaseTwoOfFourPresent() {
        List<EvidenceItem> available = List.of(
                new EvidenceItem("Transaction record", true),
                new EvidenceItem("Merchant response", true));

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
        List<EvidenceItem> available = List.of(
                new EvidenceItem("transaction record", true),
                new EvidenceItem("MERCHANT RESPONSE", true));

        EvidenceReadiness result = EvidenceReadiness.compute(available, ALL_REQUIRED);

        assertEquals(2, result.present());
        assertEquals(List.of("Customer declaration", "Delivery / non-delivery proof"), result.missingItems());
        assertEquals("2 of 4 required items present", result.label());
    }

    @Test
    void compactConstructorDefensivelyCopiesMissingItems() {
        List<String> mutableMissing = new ArrayList<>();
        mutableMissing.add("Customer declaration");

        EvidenceReadiness result = new EvidenceReadiness(3, 4, mutableMissing, "3 of 4 required items present");
        mutableMissing.add("Delivery / non-delivery proof");

        assertEquals(1, result.missingItems().size());
    }
}
