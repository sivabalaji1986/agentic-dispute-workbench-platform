package com.workbench.caseagent.mcp;

/**
 * Thrown by {@link CaseMcpClient#getCase(String)} when the get_case MCP tool
 * indicates the case genuinely does not exist. case-system-mcp's getCase()
 * throws IllegalArgumentException("Case not found: " + caseId) server-side;
 * Spring AI's MCP server auto-configuration converts any @Tool method exception
 * into an isError CallToolResult whose text content carries that exact message
 * unchanged (verified against spring-ai-mcp/spring-ai-model 2.0.0 sources —
 * ToolExecutionException.getMessage() == the original cause's message). This
 * class distinguishes that specific business-absence case from any other
 * technical/transport failure, so CaseReviewAgentExecutor can report the
 * correct retryable flag to the orchestrator.
 *
 * <p>Declared public rather than package-private: CaseReviewAgentExecutor,
 * which must catch this specifically, lives in the parent com.workbench.caseagent
 * package. A package-private type here would be uncatchable from there. This
 * class remains internal to case-review-agent — not part of workbench-common's
 * cross-module contract.
 */
public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(String message) {
        super(message);
    }
}
