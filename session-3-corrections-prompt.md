# Session 3 — Mandatory corrections before Session 4

Three targeted fixes only. Do not add features, refactor style, or touch
anything not listed. PLATFORM_CONTRACT.md is still the source of truth.
All 3 fixes must land in `workbench-common` and/or `case-review-agent`.
`mvn test` from repo root must be green after each fix.

---

## Fix 1 — Merchant response availability from MCP data, not hardcoded

**Problem:** `progressLines.add("Merchant response available")` is emitted
unconditionally regardless of what the MCP data says. An analyst could be
told a merchant response exists when it does not.

**Fix:** Derive merchant response availability directly from the `get_case`
MCP response data (e.g. a `merchantResponse` field, or infer from whether
`merchantPosition` is non-null/non-empty). Split responsibilities cleanly:

```
MCP data  → whether merchant responded (boolean fact)
LLM       → concise one-sentence summary of the merchant's position
             (only called when merchant has responded)
```

Emit the correct conditional progress line:
```java
if (merchantResponded) {
    progressLines.add("Merchant response available");
} else {
    progressLines.add("No merchant response on file");
}
```

Set `CaseReviewResult.merchantResponse` to `"available"` or `"not available"`
based on the MCP data, not the LLM output.
Only call the LLM to summarise `merchantPosition` when `merchantResponded` is
true; when false, set `merchantPosition` to `"No merchant response on file"`.

Update tests:
- Add a test case where the MCP data indicates no merchant response — assert
  progress line is `"No merchant response on file"` and
  `merchantPosition` is the fallback string.
- Existing `execute_validCase_progressLinesPresent` test must still pass
  (demo case D-10291 has a merchant response).

---

## Fix 2 — Differentiate business absence from technical failure

**Problem:** Any exception from `getCase()` — including MCP server
unavailable, timeout, malformed response — is currently caught and reported
as `"Case not found: <caseId>"`. The orchestrator cannot distinguish a
missing case from a broken dependency.

**Fix:** Use at minimum two distinct outcomes:

```java
try {
    mcpClient.getCase(caseId);
} catch (CaseNotFoundException e) {       // case genuinely absent (404/empty)
    return errorResult("Case not found: " + caseId, false);
} catch (Exception e) {                   // transport / MCP / system failure
    return errorResult("Unable to retrieve case data: " + e.getMessage(), true);
}
```

Where `errorResult` produces a `CaseReviewResult` with `transactionFound=false`
and a descriptive message in an appropriate field (add a `String errorMessage`
field to `CaseReviewResult` in `workbench-common` if not already present,
nullable, `@JsonInclude(NON_NULL)`).

Define `CaseNotFoundException` as a package-private exception in
`case-review-agent` — thrown by `CaseMcpClient.getCase()` when the MCP
response indicates the case does not exist (HTTP 404, empty result, or
explicit not-found indicator in the response body).

The `retryable` boolean in the error distinguishes the two cases for the
orchestrator: `false` for business absence (don't retry), `true` for
technical failure (retry is meaningful). The orchestrator in Session 5 will
use this — carry the field through `AgentResponse` (see Fix 3).

Update tests:
- Rename existing `execute_unknownCase_returnsErrorGracefully` to
  `execute_unknownCase_returnsNotFoundError` — assert `transactionFound=false`
  and error message contains the caseId.
- Add `execute_mcpUnavailable_returnsRetryableError` — stub MCP client to
  throw a generic `RuntimeException`; assert error message contains
  "Unable to retrieve" and the result indicates retryable.

---

## Fix 3 — Move AgentResponse to workbench-common as a generic type

**Problem:** `AgentResponse` currently lives only in `case-review-agent`.
The Policy Agent (Session 4) must produce an identical wrapper and the
orchestrator (Session 5) must deserialize both. Two separate definitions
will drift.

**Fix:** Move to `workbench-common`:

```java
// com.workbench.common.a2a.AgentResponse
public record AgentResponse<T>(
    T result,
    List<String> progressLines,
    boolean retryable             // propagated from Fix 2 error results
) {
    // Defensive compact constructor
    public AgentResponse {
        progressLines = progressLines == null
            ? List.of()
            : List.copyOf(progressLines);
    }
}
```

Delete `AgentResponse` from `case-review-agent`.
Update `case-review-agent` to import from `workbench-common`.
Update all references in `CaseReviewAgentExecutor` and tests.

`AgentResponse<CaseReviewResult>` is now the return type from
`CaseReviewAgentExecutor`.
Session 4 will use `AgentResponse<PolicyResult>`.
Session 5 orchestrator will handle both uniformly.

Add to `contract-tests`:
- `AgentResponseContractTest` — serializes an `AgentResponse<CaseReviewResult>`
  and asserts: `result` field contains the nested `CaseReviewResult`,
  `progressLines` is a JSON array, `retryable` is a boolean. Round-trip
  deserialization must recover the original object. This is the contract
  the Session 5 orchestrator deserializes against.

---

## Acceptance

- `mvn test` from repo root: all modules green.
- New tests added per each fix — show test names and pass counts.
- `AgentResponse` exists only in `workbench-common`, not in
  `case-review-agent` (verify with
  `find case-review-agent/src -name "AgentResponse.java"` — must return
  nothing).
- `contract-tests` includes `AgentResponseContractTest` and passes.
- No `com.fasterxml.jackson.core` or `com.fasterxml.jackson.databind`
  imports in `workbench-common` or `case-review-agent`:
  ```bash
  grep -r "com\.fasterxml\.jackson\.core\|com\.fasterxml\.jackson\.databind" \
    workbench-common/src case-review-agent/src 2>/dev/null
  ```
  Must return nothing.
- Show the serialized JSON of `AgentResponse<CaseReviewResult>` from the
  contract test — this is the wire format Session 5 must parse.
