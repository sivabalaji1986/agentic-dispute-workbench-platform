package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicyResult(
        String disputeType,
        String policySection,
        String policyInterpretation,
        List<String> requiredEvidence,
        String policyOutcome) {
}
