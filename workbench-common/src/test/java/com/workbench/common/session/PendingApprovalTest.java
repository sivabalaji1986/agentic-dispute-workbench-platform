package com.workbench.common.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingApprovalTest {

    @Test
    void withExecutedReturnsNewRecordWithUpdatedValueAndLeavesOriginalUnchanged() {
        PendingApproval original = new PendingApproval(
                "thread-1", "surface-1", "D-10291", "MISSING_EVIDENCE_REQUEST",
                List.of("CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF"), false);

        PendingApproval executed = original.withExecuted(true);

        assertTrue(executed.executed());
        assertFalse(original.executed());
        assertEquals(original.threadId(), executed.threadId());
        assertEquals(original.surfaceId(), executed.surfaceId());
        assertEquals(original.caseId(), executed.caseId());
        assertEquals(original.taskType(), executed.taskType());
        assertEquals(original.missingItems(), executed.missingItems());
        assertEquals(original, original.withExecuted(false));
    }

    @Test
    void compactConstructorDefensivelyCopiesMissingItems() {
        List<String> mutableMissing = new ArrayList<>();
        mutableMissing.add("CUSTOMER_DECLARATION");

        PendingApproval result = new PendingApproval(
                "thread-1", "surface-1", "D-10291", "MISSING_EVIDENCE_REQUEST", mutableMissing, false);
        mutableMissing.add("DELIVERY_DISPUTE_PROOF");

        assertEquals(1, result.missingItems().size());
    }
}
