package com.workbench.caseagent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.workbench.common.a2a.CaseReviewResult;

import java.util.List;

/**
 * The A2A Java SDK has no built-in mechanism to attach both a structured result AND
 * a list of progress lines to a single response (see PLATFORM_CONTRACT.md §8.2's
 * "Progress lines emitted during execution" vs. the structured JSON payload — these
 * are two logically separate things the orchestrator needs, but A2A's Message type
 * carries one text payload). This wrapper is serialized as the single JSON text
 * response; the orchestrator (Session 5) deserializes this same wrapper shape.
 * policy-agent (Session 4) must use this identical pattern (PLATFORM_CONTRACT.md
 * §H forward-note).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResponse(CaseReviewResult result, List<String> progressLines) {
}
