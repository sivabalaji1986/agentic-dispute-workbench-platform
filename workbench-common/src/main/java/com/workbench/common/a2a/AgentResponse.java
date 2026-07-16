package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The A2A Java SDK has no built-in mechanism to attach a structured result,
 * progress lines, and a retry hint to a single response (A2A's Message type
 * carries one text payload). This wrapper is serialized as the single JSON text
 * response body; the orchestrator (Session 5) deserializes this same wrapper
 * shape for every agent. case-review-agent, policy-agent (Session 4), and any
 * future A2A server module in this platform use this identical pattern — see
 * PLATFORM_CONTRACT.md §10.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResponse<T>(T result, List<String> progressLines, boolean retryable) {

    public AgentResponse {
        progressLines = progressLines == null ? List.of() : List.copyOf(progressLines);
    }
}
