package com.workbench.common.merge;

import java.util.Map;

/**
 * Document-type-code to human-readable name mapping (PLATFORM_CONTRACT.md §10/§B3).
 * Public: consumed cross-module by case-review-agent (Session 3) to build
 * human-readable EvidenceItem labels, in addition to EvidenceReadiness in this module.
 */
public final class DocumentTypes {

    private static final Map<String, String> HUMAN_READABLE_NAMES = Map.of(
            "TRANSACTION_RECORD", "Transaction record",
            "MERCHANT_RESPONSE", "Merchant response",
            "CUSTOMER_DECLARATION", "Customer declaration",
            "DELIVERY_DISPUTE_PROOF", "Delivery / non-delivery proof");

    private DocumentTypes() {
    }

    public static String humanReadable(String docType) {
        return HUMAN_READABLE_NAMES.getOrDefault(docType, docType);
    }
}
