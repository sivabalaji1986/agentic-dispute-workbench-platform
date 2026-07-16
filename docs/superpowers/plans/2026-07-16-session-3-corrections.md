# Session 3 Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply three mandatory corrections to `case-review-agent` (and shared types
in `workbench-common`) before Session 4 begins: (1) derive merchant-response
availability from real MCP data instead of hardcoding it, (2) let the orchestrator
distinguish "case genuinely doesn't exist" from "MCP/transport failure", and (3) move
the `AgentResponse` wrapper into `workbench-common` as a generic type so policy-agent
(Session 4) and the orchestrator (Session 5) can share one wire format.

**Architecture:** No new modules. `AgentResponse<T>` and an extended
`CaseReviewResult` (new `errorMessage` field) move/change in `workbench-common`.
`case-review-agent` gains one new exception type (`CaseNotFoundException`), and
`CaseMcpClient`/`CaseReviewAgentExecutor` are rewritten to use both. `contract-tests`
gains one new test (`AgentResponseContractTest`) locking in the shared wire format for
Session 4/5.

**Tech Stack:** Same as Session 3 — Spring Boot 4, Spring AI 2.0, Jackson 3
(`tools.jackson.*`), JUnit 5, Mockito.

## Global Constraints

- Only `workbench-common`, `case-review-agent`, and `contract-tests` may change.
  `case-system-mcp` is explicitly out of scope for this session (per the corrections
  prompt) — this shaped Fix 1's design below.
- `mvn test` from the repo root must be green after every task, not just at the end.
- Jackson 3 only (`tools.jackson.core`/`tools.jackson.databind`) in any module's own
  source; the one platform-wide exception remains
  `com.fasterxml.jackson.annotation.*` (Jackson 3 never renamed its annotations
  module). No `com.fasterxml.jackson.core`/`com.fasterxml.jackson.databind` imports
  anywhere in `workbench-common` or `case-review-agent` source.
- **Verified correction — Fix 1's data source.** The corrections prompt suggests
  deriving merchant-response-availability from "the `get_case` MCP response data
  (e.g. a `merchantResponse` field ... or `merchantPosition`)". Verified against the
  real code (`case-system-mcp`'s `CaseSystemTools.getCase()`, `CaseEntity`, and
  `infra/seed/schema.sql`): `get_case` only ever queries the `cases` table and never
  returns any merchant/transaction field — `merchant_position` lives in a *different*
  table (`workbench.transactions`) that `get_case` never touches. Extending
  `get_case` to add this field would require editing `case-system-mcp`, which this
  session's own scope constraint forbids. The equivalent, already-real MCP-sourced
  boolean fact is the `MERCHANT_RESPONSE` entry's `present` flag returned by
  `list_case_documents` (confirmed against `infra/seed/seed-data.sql`: case D-10291
  has exactly this document row with `present = TRUE`). Fix 1 below uses
  `list_case_documents`'s data, not `get_case`'s — this satisfies the fix's actual
  intent (a real MCP-sourced boolean fact, not a hardcoded line) while staying inside
  the session's module scope.
- **Verified correction — `CaseNotFoundException` visibility.** The corrections
  prompt calls for a "package-private exception in `case-review-agent`". Verified:
  `CaseMcpClient` (which must throw it) lives in `com.workbench.caseagent.mcp`;
  `CaseReviewAgentExecutor` (which must catch it specifically) lives in the parent
  package `com.workbench.caseagent`. Java access rules make a package-private type
  in `.mcp` uncatchable from the parent package — there is no package that is
  simultaneously "where `CaseMcpClient` lives" and "where `CaseReviewAgentExecutor`
  lives". `CaseNotFoundException` is therefore declared `public` (still internal to
  `case-review-agent` — not part of `workbench-common`'s cross-module contract, so
  "package-private" in spirit, `public` only because Java requires it to cross this
  module-internal package boundary).
- **Verified mechanism — how a server-side "not found" reaches the client.**
  Decompiled/read real sources (`spring-ai-mcp:2.0.0`'s `McpToolUtils`,
  `spring-ai-model:2.0.0`'s `MethodToolCallback`/`ToolExecutionException`,
  `mcp-core:2.0.0`'s `McpSchema`) to confirm the exact wire behavior: when a
  `@Tool` method throws (e.g. `case-system-mcp`'s `getCase()` throws
  `IllegalArgumentException("Case not found: " + caseId)`), Spring AI's MCP server
  catches it, wraps it in `ToolExecutionException` (whose `getMessage()` is defined
  as `cause.getMessage()` — the original message is preserved unchanged), and
  `McpToolUtils` converts that into `CallToolResult(content=[TextContent(exact
  original message)], isError=true)`. So the client genuinely receives the literal
  string `"Case not found: <caseId>"` in the result's text content when
  `isError=true`. `CaseMcpClient.getCase()` below detects this via a prefix check on
  that extracted text. This is a deliberate coupling to `case-system-mcp`'s exact
  exception message text (documented in code) — it is the only "explicit not-found
  indicator in the response body" available without touching `case-system-mcp`.
- Jackson 3's generic-type round-trip deserialization uses
  `tools.jackson.core.type.TypeReference<T>` (verified present in
  `jackson-databind:3.1.4`'s compiled API — `ObjectMapper.readValue(String,
  TypeReference<T>)` exists), used in the new contract test.

---

### Task 1: Move `AgentResponse` to `workbench-common` as a generic type (Fix 3)

**Files:**
- Create: `workbench-common/src/main/java/com/workbench/common/a2a/AgentResponse.java`
- Modify: `workbench-common/src/main/java/com/workbench/common/a2a/CaseReviewResult.java`
  (no field changes yet — Task 2 adds `errorMessage`; Task 1 touches this file only
  if needed for compilation, which it is not, so leave unchanged)
- Delete: `case-review-agent/src/main/java/com/workbench/caseagent/AgentResponse.java`
- Modify: `case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentExecutor.java`
  (import + construction call sites only — no behavior change yet)
- Create: `contract-tests/src/test/java/com/workbench/contract/AgentResponseContractTest.java`

**Interfaces:**
- Produces: `com.workbench.common.a2a.AgentResponse<T>(T result, List<String>
  progressLines, boolean retryable)` — the shared wrapper Task 2 (this session),
  Session 4's policy-agent, and Session 5's orchestrator all use. `retryable` is
  `false` for every response in this task (no retryable-aware logic exists yet —
  that's Task 2); this task only relocates and generifies the type.
- Consumes: nothing new — reuses `CaseReviewResult` as-is.

- [ ] **Step 1: Create the generic `AgentResponse<T>` in `workbench-common`**

`workbench-common/src/main/java/com/workbench/common/a2a/AgentResponse.java`:
```java
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
```

- [ ] **Step 2: Delete the old `case-review-agent`-local `AgentResponse`**

```bash
rm case-review-agent/src/main/java/com/workbench/caseagent/AgentResponse.java
```

- [ ] **Step 3: Update `CaseReviewAgentExecutor` to import and construct the new type**

In `case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentExecutor.java`:

Replace the import block's absence of an `AgentResponse` import (previously it was
in the same package, so there was no import) with:
```java
import com.workbench.common.a2a.AgentResponse;
```
(add this line alongside the existing `import com.workbench.common.a2a.CaseReviewResult;`)

Change the two construction sites and the `serialize` helper's parameter type:
```java
return serialize(new AgentResponse<>(result, progressLines, false));
```
(was: `new AgentResponse(result, progressLines)`, in the main success path)

```java
return serialize(new AgentResponse<>(errorResult, List.of(message), false));
```
(was: `new AgentResponse(errorResult, List.of(message))`, in `errorResponse(...)`)

```java
private String serialize(AgentResponse<CaseReviewResult> response) {
    return objectMapper.writeValueAsString(response);
}
```
(was: `private String serialize(AgentResponse response) { ... }`)

No other logic in this file changes in this task — `errorResponse(String, String)`
keeps its current two-argument signature and current body for now; Task 2 rewrites
it to take a `retryable` boolean and use the new `CaseReviewResult.errorMessage`
field.

- [ ] **Step 4: Run the full `case-review-agent` test suite to confirm no regression**

Run: `mvn -pl case-review-agent test`
Expected: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS` — all 8
existing tests pass unchanged. (None of them assert on the JSON's exact top-level key
set, so the new `"retryable":false` field appearing in every response does not break
any existing assertion — confirmed by reading every assertion in
`CaseReviewAgentExecutorTest.java` and `AgentCardSmokeTest.java` before writing this
step.)

- [ ] **Step 5: Write the new contract test**

`contract-tests/src/test/java/com/workbench/contract/AgentResponseContractTest.java`:
```java
package com.workbench.contract;

import com.workbench.common.a2a.AgentResponse;
import com.workbench.common.a2a.CaseReviewResult;
import com.workbench.common.agui.EvidenceItem;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serializes an AgentResponse<CaseReviewResult> matching the demo scenario and
 * asserts the wire shape the orchestrator (Session 5) deserializes against:
 * result, progressLines, and retryable are siblings at the top level (retryable
 * is not nested under result). This is the generic wrapper policy-agent
 * (Session 4) and any future A2A server module reuses — see PLATFORM_CONTRACT.md
 * §10.
 */
class AgentResponseContractTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void agentResponseSerializesWithNestedResultAndTopLevelRetryable() {
        CaseReviewResult result = new CaseReviewResult(
                "D-10291", true, "SGD 250", "available", "Item was delivered",
                List.of(new EvidenceItem("Transaction record", true), new EvidenceItem("Merchant response", true)),
                "OPEN");
        AgentResponse<CaseReviewResult> response = new AgentResponse<>(
                result,
                List.of("Checking transaction status...", "Transaction found for SGD 250"),
                false);

        String json = objectMapper.writeValueAsString(response);
        JsonNode node = objectMapper.readTree(json);

        assertTrue(node.get("result").isObject());
        assertEquals("D-10291", node.get("result").get("caseId").asString());
        assertTrue(node.get("progressLines").isArray());
        assertEquals(2, node.get("progressLines").size());
        assertFalse(node.get("retryable").asBoolean());
        assertTrue(json.contains("\"retryable\":false"));

        AgentResponse<CaseReviewResult> roundTripped =
                objectMapper.readValue(json, new TypeReference<AgentResponse<CaseReviewResult>>() { });
        assertEquals(response, roundTripped);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -pl contract-tests test -Dtest=AgentResponseContractTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. If the round-trip
deserialization assertion fails, investigate whether `TypeReference`-based generic
resolution needs a different construction — do not weaken the assertion without
understanding why first.

- [ ] **Step 7: Run the full contract-tests suite to confirm no regressions**

Run: `mvn -pl contract-tests test`
Expected: `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0` (16 pre-existing + 1 new).

- [ ] **Step 7a: Show the serialized `AgentResponse<CaseReviewResult>` JSON from the contract test**

This is the wire format the orchestrator (Session 5) must parse — capture it for
the acceptance summary. Temporarily add
`System.out.println("AGENT_RESPONSE_JSON: " + json);` in
`AgentResponseContractTest.agentResponseSerializesWithNestedResultAndTopLevelRetryable`
right after the `json` variable is assigned, rerun
`mvn -pl contract-tests test -Dtest=AgentResponseContractTest`, capture the printed
line, then revert the printout and confirm `git status` shows the test file
unchanged afterward.

- [ ] **Step 8: Verify the old `AgentResponse` is gone from `case-review-agent`**

Run: `find case-review-agent/src -name "AgentResponse.java"`
Expected: no output.

- [ ] **Step 9: Commit**

```bash
git add workbench-common/src/main/java/com/workbench/common/a2a/AgentResponse.java \
        case-review-agent/src/main/java/com/workbench/caseagent/AgentResponse.java \
        case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentExecutor.java \
        contract-tests/src/test/java/com/workbench/contract/AgentResponseContractTest.java
git commit -m "Move AgentResponse to workbench-common as a generic type (Fix 3)"
```

---

### Task 2: Error differentiation (Fix 2) + merchant-response from MCP data (Fix 1)

**Files:**
- Modify: `workbench-common/src/main/java/com/workbench/common/a2a/CaseReviewResult.java`
- Create: `case-review-agent/src/main/java/com/workbench/caseagent/mcp/CaseNotFoundException.java`
- Modify: `case-review-agent/src/main/java/com/workbench/caseagent/mcp/CaseMcpClient.java`
- Modify: `case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentExecutor.java`
- Modify: `case-review-agent/src/test/java/com/workbench/caseagent/mcp/CaseMcpClientTest.java`
- Modify: `case-review-agent/src/test/java/com/workbench/caseagent/CaseReviewAgentExecutorTest.java`

**Interfaces:**
- Consumes: `AgentResponse<T>` from Task 1 (`com.workbench.common.a2a.AgentResponse`).
- Produces: `CaseReviewResult` now has an 8th component, `errorMessage` (nullable,
  `@JsonInclude(NON_NULL)`, omitted when null) — a **backward-compatible** addition:
  the pre-existing 7-argument constructor still exists (delegates to the new 8-arg
  canonical constructor with `errorMessage = null`), so `A2aSerializationTest.java`
  and the existing `CaseReviewResultContractTest.java` (both outside this session's
  file-change list) do not need to change and are not touched by this task.
  `com.workbench.caseagent.mcp.CaseNotFoundException` (public, `RuntimeException`)
  is a new type later tasks/sessions do not depend on — it is entirely internal to
  `case-review-agent`.

Fix 1 and Fix 2 both restructure `CaseReviewAgentExecutor.execute()`'s body in
overlapping, interleaved ways (both touch the "figure out merchant status, build
progress lines, build the result" section), so they are implemented together in one
task rather than two — a reviewer cannot meaningfully evaluate one without the other
once they're interleaved in the same method.

- [ ] **Step 1: Add `errorMessage` to `CaseReviewResult`, preserving the old constructor**

`workbench-common/src/main/java/com/workbench/common/a2a/CaseReviewResult.java`
(full replacement):
```java
package com.workbench.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.workbench.common.agui.EvidenceItem;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseReviewResult(
        String caseId,
        boolean transactionFound,
        String transactionAmount,
        String merchantResponse,
        String merchantPosition,
        List<EvidenceItem> availableDocuments,
        String caseStatus,
        String errorMessage) {

    public CaseReviewResult {
        availableDocuments = availableDocuments == null ? List.of() : List.copyOf(availableDocuments);
    }

    /**
     * Backward-compatible constructor for the success path, where there is no
     * error message. Existing callers (A2aSerializationTest,
     * CaseReviewResultContractTest) keep working unchanged.
     */
    public CaseReviewResult(
            String caseId,
            boolean transactionFound,
            String transactionAmount,
            String merchantResponse,
            String merchantPosition,
            List<EvidenceItem> availableDocuments,
            String caseStatus) {
        this(caseId, transactionFound, transactionAmount, merchantResponse, merchantPosition,
                availableDocuments, caseStatus, null);
    }
}
```

- [ ] **Step 2: Run workbench-common's tests to confirm the old constructor still works**

Run: `mvn -pl workbench-common test`
Expected: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` — unchanged from before
this task; `A2aSerializationTest`'s two `CaseReviewResult`-constructing tests compile
and pass with zero edits.

- [ ] **Step 3: Create `CaseNotFoundException`**

`case-review-agent/src/main/java/com/workbench/caseagent/mcp/CaseNotFoundException.java`:
```java
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
```

- [ ] **Step 4: Rewrite `CaseMcpClient` to detect not-found vs. generic MCP failure**

`case-review-agent/src/main/java/com/workbench/caseagent/mcp/CaseMcpClient.java`
(full replacement):
```java
package com.workbench.caseagent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CaseMcpClient {

    private static final String CASE_NOT_FOUND_PREFIX = "Case not found:";

    private final McpSyncClient client;

    public CaseMcpClient(List<McpSyncClient> syncClients) {
        if (syncClients.isEmpty()) {
            throw new IllegalStateException("No MCP sync client configured for case-system-mcp");
        }
        this.client = syncClients.get(0);
    }

    public Map<String, Object> getCase(String caseId) {
        McpSchema.CallToolResult result = call("get_case", Map.of("caseId", caseId));
        if (Boolean.TRUE.equals(result.isError())) {
            String errorText = extractErrorText(result);
            if (errorText.startsWith(CASE_NOT_FOUND_PREFIX)) {
                throw new CaseNotFoundException(errorText);
            }
            throw new IllegalStateException("MCP tool call failed: get_case: " + errorText);
        }
        return extractStructuredContent(result, "get_case");
    }

    public Map<String, Object> listCaseDocuments(String caseId) {
        return callTool("list_case_documents", Map.of("caseId", caseId));
    }

    private Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = call(toolName, arguments);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP tool call failed: " + toolName + ": " + extractErrorText(result));
        }
        return extractStructuredContent(result, toolName);
    }

    private McpSchema.CallToolResult call(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(toolName)
                .arguments(arguments)
                .build();
        return client.callTool(request);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractStructuredContent(McpSchema.CallToolResult result, String toolName) {
        Object structured = result.structuredContent();
        if (structured instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalStateException("MCP tool " + toolName + " returned no structured content");
    }

    private static String extractErrorText(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst()
                .orElse("Unknown MCP error");
    }
}
```

- [ ] **Step 5: Update `CaseMcpClientTest` with two new tests for the not-found path**

Add these two tests to
`case-review-agent/src/test/java/com/workbench/caseagent/mcp/CaseMcpClientTest.java`.
Add one new import, `import static org.junit.jupiter.api.Assertions.assertThrows;`
(the existing file does not import it; `CaseNotFoundException` needs no import —
this test class is already in the same `com.workbench.caseagent.mcp` package):

```java
@Test
void getCase_caseNotFound_throwsCaseNotFoundException() {
    McpSyncClient mockClient = mock(McpSyncClient.class);
    McpSchema.CallToolResult errorResult = new McpSchema.CallToolResult(
            List.of(McpSchema.TextContent.builder("Case not found: D-99999").build()),
            true, null, Map.of());
    when(mockClient.callTool(any())).thenReturn(errorResult);

    CaseMcpClient client = new CaseMcpClient(List.of(mockClient));

    CaseNotFoundException exception =
            assertThrows(CaseNotFoundException.class, () -> client.getCase("D-99999"));
    assertTrue(exception.getMessage().contains("D-99999"));
}

@Test
void getCase_genericMcpError_throwsIllegalStateException() {
    McpSyncClient mockClient = mock(McpSyncClient.class);
    McpSchema.CallToolResult errorResult = new McpSchema.CallToolResult(
            List.of(McpSchema.TextContent.builder("Database connection refused").build()),
            true, null, Map.of());
    when(mockClient.callTool(any())).thenReturn(errorResult);

    CaseMcpClient client = new CaseMcpClient(List.of(mockClient));

    IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> client.getCase("D-10291"));
    assertTrue(exception.getMessage().contains("Database connection refused"));
}
```

- [ ] **Step 6: Run `CaseMcpClientTest` to verify all 4 tests pass**

Run: `mvn -pl case-review-agent test -Dtest=CaseMcpClientTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` (2 pre-existing + 2 new).

- [ ] **Step 7: Rewrite `CaseReviewAgentExecutor`**

`case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentExecutor.java`
(full replacement):
```java
package com.workbench.caseagent;

import com.workbench.caseagent.mcp.CaseMcpClient;
import com.workbench.caseagent.mcp.CaseNotFoundException;
import com.workbench.common.a2a.AgentResponse;
import com.workbench.common.a2a.CaseReviewResult;
import com.workbench.common.agui.EvidenceItem;
import com.workbench.common.merge.DocumentTypes;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CaseReviewAgentExecutor {

    private static final Pattern INPUT_PATTERN =
            Pattern.compile("dispute case ([\\w-]+), dispute type (\\w+)");

    private static final Set<String> CUSTOMER_DOC_TYPES =
            Set.of("CUSTOMER_DECLARATION", "DELIVERY_DISPUTE_PROOF");

    private static final String MERCHANT_RESPONSE_DOC_TYPE = "MERCHANT_RESPONSE";

    private final CaseMcpClient caseMcpClient;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    public CaseReviewAgentExecutor(CaseMcpClient caseMcpClient, ChatClient.Builder chatClientBuilder) {
        this.caseMcpClient = caseMcpClient;
        this.chatClient = chatClientBuilder.build();
    }

    public String execute(String messageText) {
        Matcher matcher = INPUT_PATTERN.matcher(messageText == null ? "" : messageText);
        if (!matcher.find()) {
            return errorResult("UNKNOWN", "Unable to parse dispute case request", false);
        }
        String caseId = matcher.group(1);

        List<String> progressLines = new ArrayList<>();
        progressLines.add("Checking transaction status...");

        Map<String, Object> caseData;
        try {
            caseData = caseMcpClient.getCase(caseId);
        } catch (CaseNotFoundException e) {
            return errorResult(caseId, "Case not found: " + caseId, false);
        } catch (RuntimeException e) {
            return errorResult(caseId, "Unable to retrieve case data: " + e.getMessage(), true);
        }

        String transactionAmount = formatAmount(caseData);
        progressLines.add("Transaction found for " + transactionAmount);

        Map<String, Object> documentsResponse;
        try {
            documentsResponse = caseMcpClient.listCaseDocuments(caseId);
        } catch (RuntimeException e) {
            return errorResult(caseId, "Unable to retrieve case documents: " + e.getMessage(), true);
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents =
                (List<Map<String, Object>>) documentsResponse.getOrDefault("documents", List.of());

        boolean merchantResponded = documents.stream().anyMatch(doc ->
                MERCHANT_RESPONSE_DOC_TYPE.equals(doc.get("docType")) && Boolean.TRUE.equals(doc.get("present")));
        progressLines.add(merchantResponded ? "Merchant response available" : "No merchant response on file");

        List<EvidenceItem> availableDocuments = new ArrayList<>();
        boolean hasCustomerDocs = false;
        for (Map<String, Object> doc : documents) {
            String docType = (String) doc.get("docType");
            boolean present = Boolean.TRUE.equals(doc.get("present"));
            availableDocuments.add(new EvidenceItem(DocumentTypes.humanReadable(docType), present));
            if (present) {
                progressLines.add("Case file contains " + DocumentTypes.humanReadable(docType).toLowerCase());
            }
            if (CUSTOMER_DOC_TYPES.contains(docType) && present) {
                hasCustomerDocs = true;
            }
        }
        if (!hasCustomerDocs) {
            progressLines.add("No additional customer documents found in case file");
        }

        String merchantResponseStatus = merchantResponded ? "available" : "not available";
        String merchantPosition = merchantResponded
                ? summarizeMerchantPosition(caseData)
                : "No merchant response on file";

        CaseReviewResult result = new CaseReviewResult(
                caseId,
                true,
                transactionAmount,
                merchantResponseStatus,
                merchantPosition,
                availableDocuments,
                (String) caseData.get("caseStatus"));

        return serialize(new AgentResponse<>(result, progressLines, false));
    }

    private String summarizeMerchantPosition(Map<String, Object> caseData) {
        String caseJson = objectMapper.writeValueAsString(caseData);
        String prompt = "Given this case data: " + caseJson + "\n"
                + "Summarise the merchant's position in one sentence.\n"
                + "Respond only with JSON: {\"merchantPosition\": \"...\"}";
        try {
            String content = chatClient.prompt().user(prompt).call().content();
            JsonNode node = objectMapper.readTree(content);
            return node.get("merchantPosition").asString();
        } catch (RuntimeException e) {
            return "Unable to determine";
        }
    }

    private static String formatAmount(Map<String, Object> caseData) {
        Object amountObj = caseData.get("amount");
        Object currencyObj = caseData.get("currency");
        String currency = currencyObj == null ? "" : currencyObj.toString();
        if (amountObj instanceof Number number) {
            BigDecimal amount = new BigDecimal(number.toString());
            return (currency + " " + amount.stripTrailingZeros().toPlainString()).trim();
        }
        return (currency + " " + amountObj).trim();
    }

    private String errorResult(String caseId, String message, boolean retryable) {
        CaseReviewResult result = new CaseReviewResult(
                caseId, false, null, "unknown", null, List.of(), "UNKNOWN", message);
        return serialize(new AgentResponse<>(result, List.of(message), retryable));
    }

    private String serialize(AgentResponse<CaseReviewResult> response) {
        return objectMapper.writeValueAsString(response);
    }
}
```

Note what changed structurally versus the pre-Task-2 version: the `LlmSummary`
record is gone (the LLM now returns only `merchantPosition`, not a
`{merchantResponse, merchantPosition}` pair — `merchantResponse` is now always
MCP-derived); `errorResponse(String,String)` is replaced by `errorResult(String,
String, boolean)` which also sets `CaseReviewResult.errorMessage`; the
`"Merchant response available"` progress line moved to after `listCaseDocuments`
succeeds (it now depends on that data) and is conditional; `getCase`'s catch block
splits into two catches; `listCaseDocuments`'s catch block now reports
`retryable=true` and includes `e.getMessage()`.

- [ ] **Step 8: Update `CaseReviewAgentExecutorTest`**

Full replacement of
`case-review-agent/src/test/java/com/workbench/caseagent/CaseReviewAgentExecutorTest.java`:
```java
package com.workbench.caseagent;

import com.workbench.caseagent.mcp.CaseMcpClient;
import com.workbench.caseagent.mcp.CaseNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseReviewAgentExecutorTest {

    private static final String DEMO_MESSAGE =
            "Check transaction, merchant response, case status and available evidence "
                    + "for dispute case D-10291, dispute type GOODS_NOT_RECEIVED.";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private CaseMcpClient mockMcpClient() {
        return mockMcpClient(true);
    }

    private CaseMcpClient mockMcpClient(boolean merchantResponsePresent) {
        CaseMcpClient client = mock(CaseMcpClient.class);
        when(client.getCase("D-10291")).thenReturn(Map.of(
                "caseId", "D-10291",
                "disputeText", "I paid SGD 250 for an item, but I never received it. "
                        + "The merchant says the item was delivered, but I disagree.",
                "disputeType", "GOODS_NOT_RECEIVED",
                "caseStatus", "OPEN",
                "amount", 250.00,
                "currency", "SGD"));
        List<Map<String, Object>> documents = merchantResponsePresent
                ? List.of(
                        Map.of("docType", "TRANSACTION_RECORD", "present", true),
                        Map.of("docType", "MERCHANT_RESPONSE", "present", true))
                : List.of(Map.of("docType", "TRANSACTION_RECORD", "present", true));
        when(client.listCaseDocuments("D-10291")).thenReturn(Map.of("documents", documents));
        return client;
    }

    private ChatClient.Builder chatClientBuilderReturning(String jsonContent) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(jsonContent);
        return builder;
    }

    @Test
    void execute_validCase_returnsStructuredResult() {
        String llmJson = "{\"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode node = objectMapper.readTree(responseJson);
        JsonNode result = node.get("result");

        assertTrue(result.get("transactionFound").asBoolean());
        assertEquals("SGD 250", result.get("transactionAmount").asString());
        assertEquals(2, result.get("availableDocuments").size());
        assertEquals("available", result.get("merchantResponse").asString());
        assertEquals("Item was delivered", result.get("merchantPosition").asString());
    }

    @Test
    void execute_validCase_progressLinesPresent() {
        String llmJson = "{\"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode progressLines = objectMapper.readTree(responseJson).get("progressLines");

        List<String> lines = new java.util.ArrayList<>();
        progressLines.forEach(n -> lines.add(n.asString()));

        assertTrue(lines.contains("Checking transaction status..."));
        assertTrue(lines.contains("Transaction found for SGD 250"));
        assertTrue(lines.contains("Merchant response available"));
        assertTrue(lines.contains("Case file contains transaction record"));
        assertTrue(lines.contains("Case file contains merchant response"));
        assertTrue(lines.contains("No additional customer documents found in case file"));
    }

    @Test
    void execute_documentsUseHumanReadableLabels() {
        String llmJson = "{\"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode documents = objectMapper.readTree(responseJson).get("result").get("availableDocuments");

        List<String> labels = new java.util.ArrayList<>();
        documents.forEach(n -> labels.add(n.get("label").asString()));

        assertFalse(labels.contains("TRANSACTION_RECORD"));
        assertFalse(labels.contains("MERCHANT_RESPONSE"));
        assertTrue(labels.contains("Transaction record"));
        assertTrue(labels.contains("Merchant response"));
    }

    @Test
    void execute_noMerchantResponseDocument_reflectsAbsenceNotHallucination() {
        String llmJson = "{\"merchantPosition\": \"Item was delivered\"}";
        CaseReviewAgentExecutor executor =
                new CaseReviewAgentExecutor(mockMcpClient(false), chatClientBuilderReturning(llmJson));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode node = objectMapper.readTree(responseJson);
        JsonNode result = node.get("result");
        List<String> lines = new java.util.ArrayList<>();
        node.get("progressLines").forEach(n -> lines.add(n.asString()));

        assertTrue(lines.contains("No merchant response on file"));
        assertFalse(lines.contains("Merchant response available"));
        assertEquals("not available", result.get("merchantResponse").asString());
        assertEquals("No merchant response on file", result.get("merchantPosition").asString());
    }

    @Test
    void execute_unknownCase_returnsNotFoundError() {
        CaseMcpClient client = mock(CaseMcpClient.class);
        when(client.getCase("D-UNKNOWN")).thenThrow(new CaseNotFoundException("Case not found: D-UNKNOWN"));
        String message = "Check transaction, merchant response, case status and available evidence "
                + "for dispute case D-UNKNOWN, dispute type GOODS_NOT_RECEIVED.";

        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(client, chatClientBuilderReturning("{}"));

        String responseJson = executor.execute(message);
        JsonNode node = objectMapper.readTree(responseJson);
        JsonNode result = node.get("result");

        assertFalse(result.get("transactionFound").asBoolean());
        assertTrue(result.get("errorMessage").asString().contains("D-UNKNOWN"));
        assertFalse(node.get("retryable").asBoolean());
    }

    @Test
    void execute_mcpUnavailable_returnsRetryableError() {
        CaseMcpClient client = mock(CaseMcpClient.class);
        when(client.getCase("D-10291")).thenThrow(new IllegalStateException("MCP tool call failed: get_case: timeout"));

        CaseReviewAgentExecutor executor = new CaseReviewAgentExecutor(client, chatClientBuilderReturning("{}"));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode node = objectMapper.readTree(responseJson);
        JsonNode result = node.get("result");

        assertTrue(result.get("errorMessage").asString().contains("Unable to retrieve"));
        assertTrue(node.get("retryable").asBoolean());
    }

    @Test
    void execute_malformedLlmOutput_defaultsGracefully() {
        CaseReviewAgentExecutor executor =
                new CaseReviewAgentExecutor(mockMcpClient(), chatClientBuilderReturning("not valid json"));

        String responseJson = executor.execute(DEMO_MESSAGE);
        JsonNode result = objectMapper.readTree(responseJson).get("result");

        assertEquals("available", result.get("merchantResponse").asString());
        assertEquals("Unable to determine", result.get("merchantPosition").asString());
    }
}
```

Note what changed versus the pre-Task-2 test file: `mockMcpClient()` now takes an
optional `merchantResponsePresent` flag (defaulted to `true` via an overload) so the
new absence-scenario test can reuse the same case data with different document
data; the LLM mock JSON shrinks to just `{"merchantPosition": ...}` (no more
`merchantResponse` key, since that's MCP-derived now);
`execute_unknownCase_returnsErrorGracefully` is renamed to
`execute_unknownCase_returnsNotFoundError` and now mocks `CaseNotFoundException`
directly and asserts on the new `errorMessage` field and top-level `retryable`;
`execute_mcpUnavailable_returnsRetryableError` is new;
`execute_noMerchantResponseDocument_reflectsAbsenceNotHallucination` is new;
`execute_malformedLlmOutput_defaultsGracefully`'s assertions change because
`merchantResponse` is no longer LLM-derived — for the demo case (which does have a
present `MERCHANT_RESPONSE` document), it stays `"available"` regardless of whether
the LLM call succeeds; only `merchantPosition` falls back to `"Unable to determine"`.

- [ ] **Step 9: Run the full `case-review-agent` test suite**

Run: `mvn -pl case-review-agent test`
Expected: `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` (4 `CaseMcpClientTest` +
7 `CaseReviewAgentExecutorTest` + 1 `AgentCardSmokeTest`). `BUILD SUCCESS`.

- [ ] **Step 10: Run the full reactor to confirm no cross-module regression**

Run: `mvn install -pl case-system-mcp -am -DskipTests && mvn test` from the repo
root.
Expected: `BUILD SUCCESS`. `workbench-common`: 12. `agui-support`: 19.
`case-system-mcp`: 7. `contract-tests`: 17. `case-review-agent`: 12. Total: 67.

- [ ] **Step 11: Jackson import check**

Run:
```bash
grep -r "com\.fasterxml\.jackson\.core\|com\.fasterxml\.jackson\.databind" \
  workbench-common/src case-review-agent/src 2>/dev/null
```
Expected: no output.

- [ ] **Step 12: Commit**

```bash
git add workbench-common/src/main/java/com/workbench/common/a2a/CaseReviewResult.java \
        case-review-agent/src/main/java/com/workbench/caseagent/mcp/CaseNotFoundException.java \
        case-review-agent/src/main/java/com/workbench/caseagent/mcp/CaseMcpClient.java \
        case-review-agent/src/main/java/com/workbench/caseagent/CaseReviewAgentExecutor.java \
        case-review-agent/src/test/java/com/workbench/caseagent/mcp/CaseMcpClientTest.java \
        case-review-agent/src/test/java/com/workbench/caseagent/CaseReviewAgentExecutorTest.java
git commit -m "Differentiate not-found vs. technical MCP failures; derive merchant response from MCP data (Fix 1 + Fix 2)"
```

---

### Task 3: Documentation sync + final acceptance verification

**Files:** `PLATFORM_CONTRACT.md` only — everything else is verification.

**Interfaces:**
- Consumes: everything from Tasks 1–2.
- Produces: nothing new; this is the acceptance gate.

- [ ] **Step 1: Update PLATFORM_CONTRACT.md §8.2 to reflect the new response shape**

In `PLATFORM_CONTRACT.md`, find this exact block (the `**Response payload...**`
paragraph through the `Locked in by ... CaseReviewResultContractTest.` sentence
that follows the JSON, currently around lines 421–454):

```
**Response payload (single JSON text response; see §10's `AgentResponse` wrapper —
the A2A Java SDK has no built-in way to attach both a structured result AND a list
of progress lines to one response, so `result` and `progressLines` below are both
fields of one wrapper object, not two separate payloads):**
```json
{
  "result": {
    "caseId": "D-10291",
    "transactionFound": true,
    "transactionAmount": "SGD 250",
    "merchantResponse": "available",
    "merchantPosition": "Item was delivered",
    "availableDocuments": [
      {"label": "Transaction record", "present": true},
      {"label": "Merchant response", "present": true}
    ],
    "caseStatus": "OPEN"
  },
  "progressLines": [
    "Checking transaction status...",
    "Transaction found for SGD 250",
    "Merchant response available",
    "Case file contains transaction record",
    "Case file contains merchant response",
    "No additional customer documents found in case file"
  ]
}
```

`availableDocuments` is a list of `{label, present}` objects with human-readable
labels (e.g. `"Transaction record"`), never raw `docType` codes like
`"TRANSACTION_RECORD"` — see §10's `EvidenceItem` and `DocumentTypes.humanReadable`
in `workbench-common`. Locked in by `contract-tests`'
`CaseReviewResultContractTest`.
```

Replace it with:

```
**Response payload (single JSON text response; see §10's `AgentResponse<T>`
wrapper — the A2A Java SDK has no built-in way to attach a structured result,
progress lines, AND a retry hint to one response, so `result`, `progressLines`,
and `retryable` below are all fields of one wrapper object, not separate
payloads):**
```json
{
  "result": {
    "caseId": "D-10291",
    "transactionFound": true,
    "transactionAmount": "SGD 250",
    "merchantResponse": "available",
    "merchantPosition": "Item was delivered",
    "availableDocuments": [
      {"label": "Transaction record", "present": true},
      {"label": "Merchant response", "present": true}
    ],
    "caseStatus": "OPEN"
  },
  "progressLines": [
    "Checking transaction status...",
    "Transaction found for SGD 250",
    "Merchant response available",
    "Case file contains transaction record",
    "Case file contains merchant response",
    "No additional customer documents found in case file"
  ],
  "retryable": false
}
```

`availableDocuments` is a list of `{label, present}` objects with human-readable
labels (e.g. `"Transaction record"`), never raw `docType` codes like
`"TRANSACTION_RECORD"` — see §10's `EvidenceItem` and `DocumentTypes.humanReadable`
in `workbench-common`. Locked in by `contract-tests`'
`CaseReviewResultContractTest`.

`merchantResponse`/`merchantPosition` are no longer both LLM-derived:
`merchantResponse` (`"available"` or `"not available"`) is computed directly from
whether `list_case_documents` returns a `MERCHANT_RESPONSE` entry with
`present: true` — a real MCP-sourced fact, not an LLM guess. The LLM is only
called to summarise `merchantPosition` into one sentence, and only when
`merchantResponse` is `"available"`; otherwise `merchantPosition` is set to the
fixed string `"No merchant response on file"` and the progress line
`"Merchant response available"` is replaced with `"No merchant response on
file"`.

`result.errorMessage` (nullable, omitted when absent) and the top-level
`retryable` boolean distinguish two error outcomes. Case genuinely absent
(`get_case` returns not-found) — `retryable: false`, don't retry:
```json
{
  "result": {
    "caseId": "D-99999",
    "transactionFound": false,
    "merchantResponse": "unknown",
    "availableDocuments": [],
    "caseStatus": "UNKNOWN",
    "errorMessage": "Case not found: D-99999"
  },
  "progressLines": ["Case not found: D-99999"],
  "retryable": false
}
```
MCP/transport failure (server unreachable, timeout, malformed response) —
`retryable: true`, retry may succeed:
```json
{
  "result": {
    "caseId": "D-10291",
    "transactionFound": false,
    "merchantResponse": "unknown",
    "availableDocuments": [],
    "caseStatus": "UNKNOWN",
    "errorMessage": "Unable to retrieve case data: <underlying MCP error text>"
  },
  "progressLines": ["Unable to retrieve case data: <underlying MCP error text>"],
  "retryable": true
}
```
```

- [ ] **Step 2: Update PLATFORM_CONTRACT.md §10 record definitions**

In `PLATFORM_CONTRACT.md`, find this exact block (currently around lines 525–537):

```
// A2A request/response
record CaseReviewRequest(String caseId, String disputeType) {}
record EvidenceItem(String label, boolean present) {}
record CaseReviewResult(
    String caseId, boolean transactionFound, String transactionAmount,
    String merchantResponse, String merchantPosition,
    List<EvidenceItem> availableDocuments, String caseStatus) {}

// case-review-agent's single-response wrapper (A2A's Message carries one text
// payload; this attaches both the structured result and progress lines to it).
// policy-agent and orchestrator-agent must use this identical pattern.
record AgentResponse(CaseReviewResult result, List<String> progressLines) {}
```

Replace it with:

```
// A2A request/response
record CaseReviewRequest(String caseId, String disputeType) {}
record EvidenceItem(String label, boolean present) {}
record CaseReviewResult(
    String caseId, boolean transactionFound, String transactionAmount,
    String merchantResponse, String merchantPosition,
    List<EvidenceItem> availableDocuments, String caseStatus,
    String errorMessage) {}                      // nullable; null/omitted on success

// Generic single-response wrapper (A2A's Message carries one text payload;
// this attaches a structured result, progress lines, AND a retry hint to it).
// Lives in workbench-common so every A2A server module (case-review-agent,
// policy-agent, and any future module) and the orchestrator share one wire
// format. retryable is only meaningful when the result represents an error
// (see §8.2): false for business absence (e.g. case not found), true for
// technical/transport failure.
record AgentResponse<T>(T result, List<String> progressLines, boolean retryable) {}
```

- [ ] **Step 3: Run the full reactor test suite one more time**

Run: `mvn install -pl case-system-mcp -am -DskipTests && mvn test` from the repo
root.
Expected: `BUILD SUCCESS`, same counts as Task 2 Step 10 (total 67) — this step
exists to catch any regression introduced by the doc-only changes (there should be
none; `PLATFORM_CONTRACT.md` is not compiled) and to give one final, current
confirmation before the branch review.

- [ ] **Step 4: Show the serialized JSON for the demo scenario and the not-found scenario**

Run: `mvn -pl case-review-agent test -Dtest=CaseReviewAgentExecutorTest 2>&1 | tail -5`
to confirm the test class still passes, then temporarily add a
`System.out.println("DEMO_JSON: " + responseJson);` inside
`execute_validCase_returnsStructuredResult` (right after `responseJson` is
assigned) and another `System.out.println("NOT_FOUND_JSON: " + responseJson);`
inside `execute_unknownCase_returnsNotFoundError`, rerun
`mvn -pl case-review-agent test -Dtest=CaseReviewAgentExecutorTest`, capture both
printed lines for the acceptance summary, then revert both printouts and confirm
`git status` shows the test file unchanged afterward — same pattern as prior
sessions' acceptance-verification steps.

- [ ] **Step 5: Final commit if the doc update needs it**

```bash
git add PLATFORM_CONTRACT.md
git commit -m "Sync PLATFORM_CONTRACT.md with session-3-corrections response shape"
```

---
